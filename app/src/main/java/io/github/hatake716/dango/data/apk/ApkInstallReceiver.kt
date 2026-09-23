package io.github.hatake716.dango.data.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hatake716.dango.DangoApp

/**
 * PackageInstaller セッションの結果受信。マニフェスト登録なのでプロセスが落ちていても再起動して受け取れる。
 * ここから Activity は起動しない（[ApkInstaller.pendingConfirm] 参照）
 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_STATUS) return
        (context.applicationContext as DangoApp).container.apkInstaller.onStatus(intent)
    }
}
