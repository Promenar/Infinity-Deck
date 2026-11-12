package com.promenar.infinity.background.cut

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.MainActivity
import com.promenar.infinity.R
import com.promenar.infinity.data.cut.DefaultCutExportController
import kotlinx.coroutines.*

/** 同一时刻仅执行一个导出；任务状态与取消入口由应用级控制器共享。 */
class CutExportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var controller: DefaultCutExportController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var finished = false
    private var latestStartId = 0
    private var requestVersion = 0L
    private var lease = -1L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        controller = (application as InfinityApplication).productionCutRuntime.controller
        if (intent?.action == ACTION_CANCEL) {
            val version = intent.getStringExtra(EXTRA_VERSION)
            scope.launch { if (version != null) controller?.cancel(version) }
            return START_NOT_STICKY
        }
        lease = intent?.getLongExtra(EXTRA_REQUEST, -1L) ?: -1L
        requestVersion++
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.production_cut_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 0, Intent(this, CutExportForegroundService::class.java)
            .setAction(ACTION_CANCEL).putExtra(EXTRA_VERSION, intent?.getStringExtra(EXTRA_VERSION)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.production_cut_exporting)).setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.production_cut_cancel), cancel).build()
        try {
            startForegroundWithFallback(notification)
            if (worker?.isActive != true) {
                finished = false
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Infinity Deck:cut-export").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
                worker = scope.launch {
                    try {
                        val current = requireNotNull(controller)
                        drainCutRequests({ current.runPending(lease) }, { current.hasPending(lease) }) { requestVersion }
                    }
                    finally {
                        finished = true
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        } catch (failure: RuntimeException) {
            com.promenar.infinity.utils.InfinityLogger.logError("成片导出前台服务启动失败", failure)
            val stoppedLease = lease
            scope.launch {
                controller?.serviceStopped(stoppedLease)
                if (lease == stoppedLease) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
            }
        }
        return START_NOT_STICKY
    }

    /** API 35+ 优先 mediaProcessing；部分系统拒绝该类型时退回 dataSync，两者都在清单中声明。 */
    private fun startForegroundWithFallback(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                return
            } catch (failure: RuntimeException) {
                com.promenar.infinity.utils.InfinityLogger.logError("成片导出 mediaProcessing 类型不可用", failure)
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val stoppedLease = lease
        val stoppedWorker = worker
        scope.launch {
            controller?.serviceStopped(stoppedLease)
            stoppedWorker?.cancel()
            if (lease == stoppedLease) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
        }
    }

    override fun onDestroy() {
        if (!finished) {
            val current = controller
            val stoppedLease = lease
            // 清理任务独立于服务 scope，保证销毁时也能回收暂存并更新终态。
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { current?.serviceStopped(stoppedLease) }
        }
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VERSION = "version_id"
        const val EXTRA_REQUEST = "request_lease"
        private const val ACTION_CANCEL = "com.promenar.infinity.CANCEL_CUT_EXPORT"
        private const val CHANNEL = "production_cut_export"
        private const val NOTIFICATION_ID = 0x4355
    }
}
