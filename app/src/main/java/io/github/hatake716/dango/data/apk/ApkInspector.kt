package io.github.hatake716.dango.data.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.domain.model.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** APK の中身（ラベル・アイコン・版・権限）とインストール可否の事前判定（SPEC §6.5） */
class ApkInspector(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    /** アーカイブ自体から読める情報。インストール済み状態と違い、ファイルが変わらない限り不変 */
    private class Parsed(
        val packageName: String,
        val label: String,
        val versionName: String?,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val icon: Bitmap?,
        val permissions: List<ApkPermission>,
        val testOnly: Boolean,
        val unsigned: Boolean,
        val certs: Set<Signature>,
    )

    // 数百 MB の APK は解析に数秒かかるため、ページの行き来やインストール後の再判定では使い回す
    private val parsedCache = LruCache<String, Parsed>(PARSED_CACHE_SIZE)

    suspend fun inspect(entry: FsEntry): ApkInfo = withContext(Dispatchers.IO) {
        if (entry.path.scheme != LocalFileSystemProvider.SCHEME) {
            throw ApkParseException("not local: ${entry.path.key}")
        }
        val path = entry.path.displayPath()
        // ラベルと権限の説明は端末言語で読むため言語もキーに含める
        val cacheKey = "$path:${entry.size}:${entry.lastModified}:${java.util.Locale.getDefault().toLanguageTag()}"
        val parsed = parsedCache.get(cacheKey) ?: run {
            val started = SystemClock.elapsedRealtime()
            parse(path).also {
                parsedCache.put(cacheKey, it)
                Log.d("dango", "apk inspect ${it.packageName} ${SystemClock.elapsedRealtime() - started}ms")
            }
        }
        withInstallState(entry, parsed)
    }

    private fun parse(path: String): Parsed {
        val signed = archiveInfo(path, PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
        // 署名の収集に失敗する＝未署名か署名が壊れている。中身の表示だけは続ける
        val pi = signed ?: archiveInfo(path, PackageManager.GET_PERMISSIONS)
        val manifest = ApkManifestReader.read(path, Build.VERSION.SDK_INT)
        if (pi == null) {
            // minSdk が端末より新しい APK は PackageManager が解析自体を拒否する
            if (manifest != null && manifest.minSdk > Build.VERSION.SDK_INT) {
                return Parsed(
                    packageName = manifest.packageName,
                    label = manifest.packageName,
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    minSdk = manifest.minSdk,
                    targetSdk = manifest.targetSdk,
                    icon = null,
                    permissions = permissions(manifest.permissions),
                    testOnly = manifest.testOnly,
                    unsigned = false,
                    certs = emptySet(),
                )
            }
            Log.d("dango", "apk inspect: cannot parse $path")
            throw ApkParseException("cannot parse: $path")
        }
        val ai = pi.applicationInfo ?: throw ApkParseException("no application: $path")
        // 未インストールの APK のリソースからラベル・アイコンを読むため
        ai.sourceDir = path
        ai.publicSourceDir = path
        return Parsed(
            packageName = pi.packageName,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pi.packageName,
            versionName = pi.versionName,
            versionCode = pi.longVersionCode,
            minSdk = ai.minSdkVersion,
            targetSdk = ai.targetSdkVersion,
            icon = if (ai.icon != 0) runCatching { renderIcon(ai) }.getOrNull() else null,
            permissions = permissions(pi.requestedPermissions?.toList().orEmpty()),
            // アーカイブ解析の ApplicationInfo.flags は埋まらない（API 35 で 0）ため、マニフェストも見る
            testOnly = ai.flags and ApplicationInfo.FLAG_TEST_ONLY != 0 || manifest?.testOnly == true,
            unsigned = signed == null,
            certs = signed?.signingInfo.certs(),
        )
    }

    private fun permissions(names: List<String>): List<ApkPermission> =
        names.distinct().map { name ->
            val info = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
            ApkPermission(
                name = name,
                label = info?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() && it != name },
                dangerous = info?.protection == PermissionInfo.PROTECTION_DANGEROUS,
            )
        }.sortedWith(compareBy<ApkPermission> { !it.dangerous }.thenBy { it.name })

    private fun withInstallState(entry: FsEntry, p: Parsed): ApkInfo {
        // <queries> は LAUNCHER を持つアプリのみ可視。見えないアプリは未インストール扱いになる
        val installedPi = runCatching { packageInfo(p.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
        val installed = installedPi?.let { InstalledApp(it.versionName, it.longVersionCode) }
        val action = when {
            installed == null -> ApkAction.INSTALL
            p.versionCode > installed.versionCode -> ApkAction.UPDATE
            p.versionCode == installed.versionCode -> ApkAction.REINSTALL
            else -> ApkAction.DOWNGRADE
        }
        val installedCerts = installedPi?.signingInfo.certs()
        // 鍵のローテーションも考慮し、双方の署名履歴に共通の証明書があれば同じ署名者とみなす
        val mismatch = p.certs.isNotEmpty() && !installedCerts.isNullOrEmpty() &&
            p.certs.none { it in installedCerts }
        val blocker = when {
            p.minSdk > Build.VERSION.SDK_INT -> ApkBlocker.MIN_SDK
            p.unsigned -> ApkBlocker.UNSIGNED
            p.testOnly -> ApkBlocker.TEST_ONLY
            action == ApkAction.DOWNGRADE -> ApkBlocker.DOWNGRADE
            mismatch -> ApkBlocker.SIGNATURE_MISMATCH
            else -> ApkBlocker.NONE
        }
        return ApkInfo(
            packageName = p.packageName,
            label = p.label,
            versionName = p.versionName,
            versionCode = p.versionCode,
            minSdk = p.minSdk,
            targetSdk = p.targetSdk,
            sizeBytes = entry.size,
            icon = p.icon,
            permissions = p.permissions,
            installed = installed,
            action = action,
            blocker = blocker,
            oldTargetWarning = p.targetSdk < minSupportedTargetSdk(),
            launchable = installed != null && pm.getLaunchIntentForPackage(p.packageName) != null,
        )
    }

    private fun renderIcon(ai: ApplicationInfo): Bitmap {
        val px = (ICON_DP * appContext.resources.displayMetrics.density).toInt()
        return ai.loadIcon(pm).toBitmap(px, px)
    }

    private fun archiveInfo(path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, flags)
        }

    private fun packageInfo(packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }

    private fun SigningInfo?.certs(): Set<Signature> {
        val info = this ?: return emptySet()
        return buildSet {
            info.apkContentsSigners?.let { addAll(it) }
            runCatching { info.signingCertificateHistory }.getOrNull()?.let { addAll(it) }
        }
    }

    /** Android 14 は targetSdk 23 未満、15 以降は 24 未満の APK のインストールを拒否する */
    private fun minSupportedTargetSdk(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> 24
        Build.VERSION.SDK_INT == 34 -> 23
        else -> 0
    }

    private companion object {
        const val ICON_DP = 96
        const val PARSED_CACHE_SIZE = 8
    }
}
