package io.github.hatake716.dango.data.transfer

import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.domain.model.ConflictChoice
import io.github.hatake716.dango.domain.model.ConflictResolution
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** 長時間処理の種別（コピー/移動に加え、M3 で解凍・圧縮が加わった） */
enum class OperationKind { COPY, MOVE, EXTRACT, COMPRESS }

/** 転送の進捗（ステータスバー・通知の両方がこれを購読する。SPEC §8.3） */
data class TransferProgress(
    val kind: OperationKind,
    val doneBytes: Long,
    val totalBytes: Long,
    val doneFiles: Int,
    val totalFiles: Int,
    val currentName: String,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** 同名衝突の問い合わせ。UI がダイアログを出して response を complete する */
class ConflictRequest(
    val name: String,
    val response: CompletableDeferred<ConflictChoice> = CompletableDeferred(),
)

data class TransferResult(
    val copiedNames: List<String>,
    val skipped: Int,
    val failed: Int,
    val cancelled: Boolean,
)

/**
 * コピー・移動の実行エンジン(SPEC §6.3, §8.3)。
 * ローカル同士は M1 スコープ。ネットワーク対応時に FileSystemProvider 経由へ一般化する。
 */
class TransferManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict.asStateFlow()

    private var currentJob: Job? = null
    private val busy = AtomicBoolean(false)

    val isBusy: Boolean get() = busy.get()

    fun cancel() {
        currentJob?.cancel()
    }

    /**
     * entries を destDir へコピー（move=true なら移動）する。
     * duplicateInPlace=true は「複製」: 衝突照会をせず「〜のコピー」名で同フォルダに複製する。
     */
    fun start(
        entries: List<FsEntry>,
        destDir: FsPath,
        move: Boolean,
        duplicateInPlace: Boolean = false,
        onFinished: (TransferResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        currentJob = scope.launch {
            var result = TransferResult(emptyList(), 0, entries.size, cancelled = true)
            try {
                result = run(entries, destDir, move, duplicateInPlace)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                result = TransferResult(emptyList(), 0, entries.size, cancelled = false)
            } finally {
                _progress.value = null
                _conflict.value = null
                busy.set(false)
                onFinished(result)
            }
        }
    }

    private suspend fun run(
        entries: List<FsEntry>,
        destDir: FsPath,
        move: Boolean,
        duplicateInPlace: Boolean,
    ): TransferResult {
        val dest = File(destDir.displayPath())
        if (!dest.isDirectory) throw IOException("展開先がフォルダではありません")
        val destCanonical = dest.canonicalPath

        val sources = entries.map { File(it.path.displayPath()) }.filter { it.exists() }

        // フォルダを自分自身（または子孫）へは複製できない（無限再帰でディスクを食い潰すため）
        for (src in sources) {
            if (src.isDirectory) {
                val srcCanonical = src.canonicalPath
                if (destCanonical == srcCanonical ||
                    destCanonical.startsWith(srcCanonical + File.separator)
                ) {
                    throw IOException("フォルダを自分自身の中へコピー・移動することはできません")
                }
            }
        }

        // 事前スキャン: 合計サイズとファイル数（進捗の分母を先に確定する）
        var totalBytes = 0L
        var totalFiles = 0
        for (src in sources) {
            walk(src) { f ->
                totalFiles++
                totalBytes += f.length()
            }
        }
        _progress.value = TransferProgress(if (move) OperationKind.MOVE else OperationKind.COPY, 0, totalBytes, 0, totalFiles, "")

        var doneBytes = 0L
        var doneFiles = 0
        var stickyChoice: ConflictResolution? = null
        val copiedNames = mutableListOf<String>()
        var skipped = 0
        var failed = 0

        for (src in sources) {
            coroutineContext.ensureActive()
            val existing = dest.list()?.toSet() ?: emptySet()
            var targetName = src.name
            // /storage の FUSE は大文字小文字を区別しないため、衝突判定も ignoreCase で行う
            val conflictingName = existing.firstOrNull { it.equals(targetName, ignoreCase = true) }
            var replaceVictim: File? = null

            if (duplicateInPlace) {
                targetName = NameUtils.duplicateName(existing, src.name, src.isDirectory)
            } else if (conflictingName != null) {
                val samePath = File(dest, conflictingName).canonicalPath == src.canonicalPath
                if (samePath) {
                    if (move) {
                        // 同じ場所への移動は何もしない
                        skipped++
                        continue
                    }
                    // 同じ場所へのコピーはダイアログを出さず「両方残す」（Finder 同様）
                    targetName = NameUtils.uniqueName(existing, targetName, src.isDirectory)
                } else {
                    val choice = stickyChoice ?: run {
                        val request = ConflictRequest(targetName)
                        _conflict.value = request
                        val answer = request.response.await()
                        _conflict.value = null
                        if (answer.applyToAll) stickyChoice = answer.resolution
                        answer.resolution
                    }
                    when (choice) {
                        ConflictResolution.CANCEL_ALL -> {
                            return TransferResult(copiedNames, skipped, failed, cancelled = true)
                        }
                        ConflictResolution.SKIP -> {
                            skipped++
                            continue
                        }
                        ConflictResolution.KEEP_BOTH -> {
                            targetName = NameUtils.uniqueName(existing, targetName, src.isDirectory)
                        }
                        ConflictResolution.REPLACE -> {
                            // 旧データはコピー成功まで消さない（失敗・キャンセルで両方を失わないため）
                            replaceVictim = File(dest, conflictingName)
                        }
                    }
                }
            }

            // REPLACE は一時名へコピーし、成功後に旧を消して rename で差し替える
            val finalFile = File(dest, targetName)
            val target = if (replaceVictim != null) {
                File(dest, ".dango-replace-${System.currentTimeMillis()}-$targetName")
            } else {
                finalFile
            }

            fun finalizeReplace(): Boolean {
                val victim = replaceVictim ?: return true
                if (!victim.deleteRecursively()) return false
                return target.renameTo(finalFile)
            }

            // 同一ボリューム内の移動は rename で高速化（SPEC §6.3）
            if (move && src.renameTo(target)) {
                if (!finalizeReplace()) {
                    failed++
                    continue
                }
                copiedNames += targetName
                walk(finalFile) { f ->
                    doneFiles++
                    doneBytes += f.length()
                }
                _progress.value = TransferProgress(if (move) OperationKind.MOVE else OperationKind.COPY, doneBytes, totalBytes, doneFiles, totalFiles, targetName)
                continue
            }

            val ok = try {
                copyRecursive(src, target) { deltaBytes, fileDone, name ->
                    doneBytes += deltaBytes
                    if (fileDone) doneFiles++
                    _progress.value =
                        TransferProgress(if (move) OperationKind.MOVE else OperationKind.COPY, doneBytes, totalBytes, doneFiles, totalFiles, name)
                }
                true
            } catch (e: CancellationException) {
                target.deleteRecursively() // 書きかけの成果物だけ捨てる（置き換え先の旧データは無傷）
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok || !finalizeReplace()) {
                failed++
                target.deleteRecursively()
                continue
            }
            copiedNames += targetName
            if (move) {
                src.deleteRecursively()
            }
        }
        return TransferResult(copiedNames, skipped, failed, cancelled = false)
    }

    private suspend fun copyRecursive(
        src: File,
        dst: File,
        onProgress: (deltaBytes: Long, fileDone: Boolean, name: String) -> Unit,
    ) {
        coroutineContext.ensureActive()
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("mkdir failed: $dst")
            // listFiles() == null（列挙不能）を空フォルダ扱いすると、移動時に未コピーの中身ごと
            // ソースを消してしまうため、明示的に失敗させる
            val children = src.listFiles() ?: throw IOException("cannot list: $src")
            children.forEach { child ->
                copyRecursive(child, File(dst, child.name), onProgress)
            }
        } else {
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        onProgress(read.toLong(), false, src.name)
                    }
                }
            }
            dst.setLastModified(src.lastModified())
            onProgress(0, true, src.name)
        }
    }

    private inline fun walk(root: File, onFile: (File) -> Unit) {
        root.walkTopDown().forEach { if (it.isFile) onFile(it) }
    }
}
