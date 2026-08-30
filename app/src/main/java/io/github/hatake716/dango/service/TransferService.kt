package io.github.hatake716.dango.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.hatake716.dango.DangoApp
import io.github.hatake716.dango.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * コピー・移動を前面に出すための Foreground Service（SPEC §2, §11）。
 * 実処理は TransferManager が行い、本サービスは通知の表示と生存保証のみを担う。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as DangoApp).container.transferManager
        if (intent?.action == ACTION_CANCEL) {
            manager.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.transfer_preparing), 0, 0))

        collectJob?.cancel()
        collectJob = scope.launch {
            // サービス起動と TransferManager.start の順序は保証されないため、
            // 最初に進捗を観測するまでは null（未開始）で停止しない
            var seenProgress = false
            manager.progress.collect { p ->
                if (p == null) {
                    if (seenProgress) stopSelf()
                    return@collect
                }
                seenProgress = true
                val label = getString(
                    if (p.isMove) R.string.transfer_moving else R.string.transfer_copying,
                    p.doneFiles,
                    p.totalFiles,
                )
                notify(buildNotification(label, (p.fraction * 100).toInt(), 100))
                delay(400) // 通知の更新はスロットリング
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.transfer_cancel), cancelIntent).build(),
            )
            .build()
    }

    private fun notify(notification: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "transfer"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "io.github.hatake716.dango.action.CANCEL_TRANSFER"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
