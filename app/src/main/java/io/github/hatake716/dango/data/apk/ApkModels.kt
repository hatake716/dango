package io.github.hatake716.dango.data.apk

import android.graphics.Bitmap

/** APK が要求する権限（SPEC §6.5: 権限一覧） */
data class ApkPermission(
    val name: String,
    /** 端末言語の説明ラベル。端末が知らない独自権限は null */
    val label: String?,
    /** 実行時に利用者の許可が必要な権限（PROTECTION_DANGEROUS） */
    val dangerous: Boolean,
)

data class InstalledApp(
    val versionName: String?,
    val versionCode: Long,
)

enum class ApkAction { INSTALL, UPDATE, REINSTALL, DOWNGRADE }

/** 事前に分かるインストール不可の理由。システムの失敗コードは粗いため先に判定して理由を示す */
enum class ApkBlocker { NONE, MIN_SDK, UNSIGNED, TEST_ONLY, DOWNGRADE, SIGNATURE_MISMATCH }

data class ApkInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val sizeBytes: Long,
    val icon: Bitmap?,
    /** 要許可の権限が先、その後は名前順 */
    val permissions: List<ApkPermission>,
    /** null は未インストール、またはパッケージ可視性の範囲外で見えない */
    val installed: InstalledApp?,
    val action: ApkAction,
    val blocker: ApkBlocker,
    /** 古い targetSdk はこの端末の下限に掛かりインストールを拒否される可能性がある */
    val oldTargetWarning: Boolean,
    val launchable: Boolean,
)

enum class ApkInstallPhase { IDLE, COPYING, AWAITING_USER, INSTALLING }

data class ApkInstallState(
    val phase: ApkInstallPhase = ApkInstallPhase.IDLE,
    /** 処理中の FsEntry.path.key */
    val targetKey: String? = null,
    val progress: Float = 0f,
    val sessionId: Int = -1,
    /** 結果が出るたびに増える。ページはこれを見て再解析する（インストール済み版・開くボタン） */
    val generation: Int = 0,
)

sealed interface ApkInstallResult {
    data class Success(val key: String?, val packageName: String?) : ApkInstallResult
    data class Failure(val key: String?, val status: Int, val message: String?) : ApkInstallResult
}

class ApkParseException(message: String) : java.io.IOException(message)
