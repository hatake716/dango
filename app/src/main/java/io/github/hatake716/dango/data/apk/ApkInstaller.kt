package io.github.hatake716.dango.data.apk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.IntentCompat
import io.github.hatake716.dango.data.fs.ProviderRegistry
import io.github.hatake716.dango.domain.model.FsEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.buffer
import java.util.Collections

/**
 * PackageInstaller のセッション API による APK インストール（SPEC §6.5, §11）。
 * 状態は Activity の作り直しやプロセス再生成をまたぐためアプリ単位のここに置く。
 * システムの確認画面は受信側から直接起動せず、[pendingConfirm] に置いて Activity が前面にいる間だけ起動させる
 * （バックグラウンドからの Activity 起動は Android 10 以降で黙って捨てられる）
 */
class ApkInstaller(context: Context, private val registry: ProviderRegistry) {

    private val appContext = context.applicationContext
    private val installer: PackageInstaller get() = appContext.packageManager.packageInstaller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ApkInstallState())
    val state: StateFlow<ApkInstallState> = _state.asStateFlow()

    // 購読者が居ない間（プロセス再生成直後など）に届いた結果も失わない
    private val _results = Channel<ApkInstallResult>(Channel.BUFFERED)
    val results: Flow<ApkInstallResult> = _results.receiveAsFlow()

    private val _confirm = MutableStateFlow<Intent?>(null)
    val pendingConfirm: StateFlow<Intent?> = _confirm.asStateFlow()

    private var copyJob: Job? = null

    /** 利用者が取り消したセッション。後から届く ABORTED は通知しない */
    private val cancelledSessions = Collections.synchronizedSet(mutableSetOf<Int>())

    /** 確認画面を起動済みで、まだ戻ってきていない */
    @Volatile
    private var confirmLaunched = false

    /**
     * 結果の通知が来ないままセッションが終わった場合（確認画面が決定なしで破棄された等）の
     * 保険。少し待っても受信側から結果が届かなければ、ここで終わらせる
     */
    private val sessionWatcher = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}
        override fun onBadgingChanged(sessionId: Int) {}
        override fun onActiveChanged(sessionId: Int, active: Boolean) {}
        override fun onProgressChanged(sessionId: Int, progress: Float) {}
        override fun onFinished(sessionId: Int, success: Boolean) {
            scope.launch {
                delay(SESSION_RESULT_GRACE_MS)
                val s = _state.value
                val waiting = s.sessionId == sessionId &&
                    (s.phase == ApkInstallPhase.AWAITING_USER || s.phase == ApkInstallPhase.INSTALLING)
                if (!waiting) return@launch
                android.util.Log.d("dango", "apk session $sessionId finished without status (success=$success)")
                finish(
                    sessionId,
                    if (success) {
                        ApkInstallResult.Success(s.targetKey, null)
                    } else {
                        ApkInstallResult.Failure(s.targetKey, PackageInstaller.STATUS_FAILURE_ABORTED, null)
                    },
                )
            }
        }
    }

    init {
        runCatching {
            installer.registerSessionCallback(sessionWatcher, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    fun canRequestInstalls(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun isBlockedByPolicy(): Boolean {
        val um = appContext.getSystemService(UserManager::class.java) ?: return false
        return um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }

    fun install(entry: FsEntry, packageName: String?) {
        val key = entry.path.key
        while (true) {
            val s = _state.value
            if (s.phase != ApkInstallPhase.IDLE) return
            val next = s.copy(phase = ApkInstallPhase.COPYING, targetKey = key, progress = 0f, sessionId = -1)
            if (_state.compareAndSet(s, next)) break
        }
        copyJob = scope.launch {
            var sessionId = -1
            try {
                abandonOrphans()
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    packageName?.let(::setAppPackageName)
                    if (entry.size > 0) setSize(entry.size)
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    // 入手元の申告。OS はこれを見て制限付き設定（ユーザー補助など）を適用する
                    if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
                sessionId = installer.createSession(params)
                val id = sessionId
                _state.update { if (it.targetKey == key) it.copy(sessionId = id) else it }
                installer.openSession(sessionId).use { session ->
                    copyInto(session, entry)
                    currentCoroutineContext().ensureActive()
                    session.commit(statusReceiver(sessionId, key).intentSender)
                }
                // 確認待ちの通知が先に届いていたら上書きしない
                _state.update {
                    if (it.sessionId == id && it.phase == ApkInstallPhase.COPYING) {
                        it.copy(phase = ApkInstallPhase.INSTALLING, progress = 1f)
                    } else {
                        it
                    }
                }
            } catch (e: CancellationException) {
                if (sessionId > 0) runCatching { installer.abandonSession(sessionId) }
                throw e
            } catch (e: Exception) {
                if (sessionId > 0) runCatching { installer.abandonSession(sessionId) }
                // cancel() によるストリーム切断は失敗として通知しない
                if (!currentCoroutineContext().isActive || _state.value.targetKey != key) return@launch
                Log.d("dango", "apk install copy failed: $e")
                val status = if (isNoSpace(e)) {
                    PackageInstaller.STATUS_FAILURE_STORAGE
                } else {
                    PackageInstaller.STATUS_FAILURE
                }
                finish(sessionId, ApkInstallResult.Failure(key, status, e.message))
            }
        }
    }

    private suspend fun copyInto(session: PackageInstaller.Session, entry: FsEntry) {
        val total = entry.size
        registry.forPath(entry.path).openRead(entry.path).buffer().use { input ->
            session.openWrite("base.apk", 0, if (total > 0) total else -1).use { out ->
                val buf = ByteArray(COPY_CHUNK)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done.toFloat() / total).coerceIn(0f, 1f)
                        val percent = (p * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            session.setStagingProgress(p)
                            _state.update {
                                if (it.phase == ApkInstallPhase.COPYING && it.targetKey == entry.path.key) {
                                    it.copy(progress = p)
                                } else {
                                    it
                                }
                            }
                        }
                    }
                }
                session.fsync(out)
            }
        }
    }

    /** クラッシュ等で commit されずに残ったセッションのステージング領域を解放する */
    private fun abandonOrphans() {
        installer.mySessions.filter { !it.isCommitted }.forEach { info ->
            runCatching { installer.abandonSession(info.sessionId) }
        }
    }

    private fun statusReceiver(sessionId: Int, key: String): PendingIntent {
        // システムが結果の extra を書き込むため MUTABLE。API 34 以降は可変なら明示 Intent 必須
        val intent = Intent(appContext, ApkInstallReceiver::class.java)
            .setAction(ACTION_INSTALL_STATUS)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_TARGET_KEY, key)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
    }

    fun cancel() {
        copyJob?.cancel()
        val s = _state.value
        if (s.sessionId > 0) {
            cancelledSessions += s.sessionId
            runCatching { installer.abandonSession(s.sessionId) }
        }
        _confirm.value = null
        confirmLaunched = false
        _state.value = ApkInstallState(generation = s.generation + 1)
    }

    fun consumeConfirm() {
        _confirm.value = null
        confirmLaunched = true
    }

    /**
     * ホスト画面が再開した（確認画面から戻った）。承認されていれば実際のインストールが
     * 進行中なので、「確認待ち」ではなく「インストール中」として扱う（取り消しは出さない）。
     * 拒否した場合は ABORTED が届いて終わる
     */
    fun onHostResumed() {
        if (!confirmLaunched) return
        confirmLaunched = false
        _state.update {
            if (it.phase == ApkInstallPhase.AWAITING_USER) it.copy(phase = ApkInstallPhase.INSTALLING) else it
        }
    }

    fun appLabel(packageName: String): String? = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()

    /** [ApkInstallReceiver] から呼ばれる（メインスレッド） */
    internal fun onStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val key = intent.getStringExtra(EXTRA_TARGET_KEY)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.d("dango", "apk install status=$status session=$sessionId pkg=$pkg msg=$msg")
        if (sessionId in cancelledSessions) {
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) cancelledSessions -= sessionId
            return
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    finish(sessionId, ApkInstallResult.Failure(key, PackageInstaller.STATUS_FAILURE, msg))
                    return
                }
                _state.update {
                    it.copy(
                        phase = ApkInstallPhase.AWAITING_USER,
                        targetKey = key ?: it.targetKey,
                        sessionId = sessionId,
                        progress = 1f,
                    )
                }
                _confirm.value = confirm
            }
            PackageInstaller.STATUS_SUCCESS -> finish(sessionId, ApkInstallResult.Success(key, pkg))
            else -> finish(sessionId, ApkInstallResult.Failure(key, status, msg))
        }
    }

    private fun finish(sessionId: Int, result: ApkInstallResult) {
        var ours = false
        _state.update { s ->
            // 別の APK の処理が始まっていたら、そちらの状態は壊さず再解析だけ促す
            ours = s.phase == ApkInstallPhase.IDLE || s.sessionId == sessionId
            if (ours) ApkInstallState(generation = s.generation + 1) else s.copy(generation = s.generation + 1)
        }
        if (ours) {
            _confirm.value = null
            confirmLaunched = false
        }
        _results.trySend(result)
    }

    private fun isNoSpace(e: Throwable): Boolean {
        val m = e.message ?: return false
        return "ENOSPC" in m || "No space" in m
    }

    companion object {
        /** セッション終了後、受信側からの結果を待つ時間 */
        private const val SESSION_RESULT_GRACE_MS = 1_500L

        const val ACTION_INSTALL_STATUS = "io.github.hatake716.dango.action.APK_INSTALL_STATUS"
        const val EXTRA_TARGET_KEY = "io.github.hatake716.dango.extra.APK_TARGET_KEY"
        private const val COPY_CHUNK = 256 * 1024

        fun launchIntent(context: Context, packageName: String): Intent? =
            context.packageManager.getLaunchIntentForPackage(packageName)
    }
}
