package com.promenar.infinity.background.mediagen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.MainActivity
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.queue.MediaGenerationModule
import kotlinx.coroutines.*

/** 沿用应用 dataSync 前台服务与唤醒锁模式；系统超时会取消运行而不删除远端查询快照。 */
class MediaGenerationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var module: MediaGenerationModule? = null
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var completed = false
    private var requestVersion = 0L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        requestVersion++
        module = (application as InfinityApplication).mediaGenerationModule
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.media_queue_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.media_queue_notification_title))
            .setContentText(getString(R.string.media_queue_notification_body))
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: RuntimeException) {
            module?.serviceStopped(interrupted = true)
            stopSelf()
            return START_NOT_STICKY
        }
        val current = module
        if (current == null) {
            completed = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            completed = false
            current.serviceStarted()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Infinity Deck:media-generation").apply {
                setReferenceCounted(false)
                // Android dataSync 服务最长运行时段由系统管理；上限也约束异常退出后的锁。
                acquire(6 * 60 * 60 * 1000L)
            }
            worker = scope.launch {
                try {
                    drainMediaQueue(current.queue::runUntilIdle, current.queue::hasRunnableTasks) { requestVersion }
                    completed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    completed = false
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    current.serviceStopped(!completed)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.cancel()
        module?.serviceStopped(interrupted = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        module?.serviceStopped(!completed)
        super.onDestroy()
    }
    private companion object {
        const val CHANNEL = "media_generation"
        const val NOTIFICATION_ID = 0x4D47
    }
}
