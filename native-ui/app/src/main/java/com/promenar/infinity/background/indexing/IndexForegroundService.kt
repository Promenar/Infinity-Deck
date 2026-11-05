package com.promenar.infinity.background.indexing

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.distribution.DistributionCapabilities
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IndexForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: InfinityApplication
    private lateinit var notifications: IndexNotificationFactory
    private var batch = IndexBatch()
    private var foreground = false
    private var stoppedForEmptyQueue = false
    private val notificationThrottle = IndexNotificationThrottle()
    private var lastSent: IndexNotificationState? = null
    private var latest: IndexNotificationState? = null
    private var pendingNotification: Job? = null
    private var queueObserver: Job? = null
    private var wakeObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        app = application as InfinityApplication
        notifications = IndexNotificationFactory(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = reduceIndexForeground(app.vectorizationQueueState.value, batch) as? IndexForegroundDecision.Active
        stoppedForEmptyQueue = decision == null
        if (!foreground) {
            try {
                ServiceCompat.startForeground(
                    this,
                    IndexNotificationFactory.NOTIFICATION_ID,
                    notifications.create(decision?.notification),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } catch (error: RuntimeException) {
                InfinityLogger.log("[IndexForeground] 提升失败：${error.javaClass.simpleName}")
                if (decision != null) app.indexForegroundController.suppressRestart()
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
            if (decision == null) {
                stopIndexService(queueWasEmpty = true)
                return START_NOT_STICKY
            }
            batch = decision.batch
            lastSent = decision.notification
            notificationThrottle.markSent(SystemClock.elapsedRealtime())
            observeWakeLock()
            observeQueue()
        } else if (decision == null) {
            stopIndexService(queueWasEmpty = true)
        } else {
            batch = decision.batch
            scheduleNotification(decision.notification)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.indexForegroundController.suppressRestart()
        stopIndexService(queueWasEmpty = false)
    }

    override fun onDestroy() {
        pendingNotification?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
        app.indexForegroundController.serviceStopped(stoppedForEmptyQueue)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeQueue() {
        queueObserver?.cancel()
        queueObserver = scope.launch {
            app.vectorizationQueueState.collect { state ->
                when (val decision = reduceIndexForeground(state, batch)) {
                    IndexForegroundDecision.Stop -> {
                        if (reduceIndexForeground(app.vectorizationQueueState.value) is IndexForegroundDecision.Stop) {
                            stopIndexService(queueWasEmpty = true)
                        }
                    }
                    is IndexForegroundDecision.Active -> {
                        batch = decision.batch
                        scheduleNotification(decision.notification)
                    }
                }
            }
        }
    }

    private fun scheduleNotification(state: IndexNotificationState) {
        latest = state
        if (state == lastSent && pendingNotification == null) return
        val wait = notificationThrottle.delayFor(SystemClock.elapsedRealtime())
        if (wait == 0L) {
            pendingNotification?.cancel()
            pendingNotification = null
            sendNotification(state)
        } else if (pendingNotification?.isActive != true) {
            pendingNotification = scope.launch {
                delay(wait)
                pendingNotification = null
                latest?.takeIf { it != lastSent }?.let(::sendNotification)
            }
        }
    }

    private fun sendNotification(state: IndexNotificationState) {
        try {
            getSystemService(NotificationManager::class.java).notify(
                IndexNotificationFactory.NOTIFICATION_ID,
                notifications.create(state),
            )
        } catch (_: SecurityException) {
            // 通知权限被拒绝不影响服务和索引。
        } catch (error: RuntimeException) {
            InfinityLogger.log("[IndexForeground] 通知更新失败：${error.javaClass.simpleName}")
        }
        lastSent = state
        notificationThrottle.markSent(SystemClock.elapsedRealtime())
    }

    private fun observeWakeLock() {
        val lock = try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        } catch (error: RuntimeException) {
            InfinityLogger.log("[IndexForeground] 唤醒锁不可用：${error.javaClass.simpleName}")
            return
        }
        wakeLock = lock
        wakeObserver?.cancel()
        wakeObserver = scope.launch {
            app.indexActivityVisibility.visible
                .map { DistributionCapabilities.backgroundKeepAliveAvailable || it }
                .distinctUntilChanged()
                .collectLatest { allowed ->
                    if (!allowed) return@collectLatest
                    try {
                        while (isActive) {
                            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                            delay(WAKE_LOCK_RENEW_MS)
                            if (lock.isHeld) lock.release()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        InfinityLogger.log("[IndexForeground] 唤醒锁持有失败：${error.javaClass.simpleName}")
                    } finally {
                        releaseWakeLock()
                    }
                }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (error: RuntimeException) {
            InfinityLogger.log("[IndexForeground] 唤醒锁释放失败：${error.javaClass.simpleName}")
        }
    }

    private fun stopIndexService(queueWasEmpty: Boolean) {
        stoppedForEmptyQueue = queueWasEmpty
        queueObserver?.cancel()
        queueObserver = null
        wakeObserver?.cancel()
        wakeObserver = null
        pendingNotification?.cancel()
        pendingNotification = null
        latest = null
        batch = IndexBatch()
        releaseWakeLock()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
        const val WAKE_LOCK_RENEW_MS = 9 * 60 * 1_000L
        const val WAKE_LOCK_TAG = "Infinity Workbench:document-indexing"
    }
}
