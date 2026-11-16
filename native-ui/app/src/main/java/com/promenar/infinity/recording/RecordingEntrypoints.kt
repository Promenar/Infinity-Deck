package com.promenar.infinity.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.promenar.infinity.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal const val RECORDING_LINK_ACTION = "com.promenar.infinity.action.OPEN_RECORDING"
internal const val RECORDING_LINK_URI = "infinitydeck://recording/open"

internal fun recordingIntent(context: Context, autoStart: Boolean = false) =
    Intent(context, RecordingActivity::class.java).apply {
        if (autoStart) action = "recording.start"
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

/** 请求将录音听写磁贴添加到系统下拉快捷面板（Android 13+ 系统弹窗确认，低版本提示手动添加）。 */
internal fun requestAddRecordingTile(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(StatusBarManager::class.java)
        if (manager != null) {
            runCatching {
                manager.requestAddTileService(
                    ComponentName(context, RecordingTileService::class.java),
                    context.getString(R.string.recording_title),
                    AndroidIcon.createWithResource(context, R.drawable.ic_recording_mic),
                    context.mainExecutor,
                ) { result ->
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            Toast.makeText(context, R.string.recording_tile_added, Toast.LENGTH_SHORT).show()
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            Toast.makeText(context, R.string.recording_tile_already_added, Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }
    }
    Toast.makeText(context, R.string.recording_tile_manual, Toast.LENGTH_SHORT).show()
}

/** 主动通知系统刷新快捷设置面板中的录音磁贴状态。 */
internal fun requestRecordingTileUpdate(context: Context) {
    runCatching {
        TileService.requestListeningState(context, ComponentName(context, RecordingTileService::class.java))
    }
}

class RecordingLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == RECORDING_LINK_ACTION ||
            (intent.action == Intent.ACTION_VIEW && intent.dataString == RECORDING_LINK_URI)
        ) startActivity(recordingIntent(this))
        finish()
    }
}

class RecordingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val hasActiveSession = runCatching { recordingController().state.value.activeId != null }.getOrDefault(false)
        val open = Runnable {
            val intent = recordingIntent(this, autoStart = !hasActiveSession).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 400, intent, PendingIntent.FLAG_IMMUTABLE))
            } else collapseAndStartLegacy(intent)
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val screenState = runCatching { recordingController().state.value }.getOrNull()
        val activeSession = screenState?.let { s -> s.sessions.firstOrNull { it.id == s.activeId } }
        when (activeSession?.phase) {
            RecordingPhase.Recording -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.recording_tile_subtitle_active)
                }
            }
            RecordingPhase.Paused -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.recording_tile_subtitle_paused)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.recording_tile_subtitle_idle)
                }
            }
        }
        tile.updateTile()
    }

    /** API 33 及以下只有 Intent 版本。 */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseAndStartLegacy(intent: Intent) = startActivityAndCollapse(intent)
}

class RecordingService : Service() {
    private val controller get() = recordingController()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcherJob: Job? = null
    @Volatile private var newestStartId = 0

    private val idle: () -> Unit = {
        val completedStartId = newestStartId
        mainExecutor.execute {
            requestRecordingTileUpdate(this)
            if (controller.state.value.activeId == null) {
                cancelContinueReminder()
                if (stopSelfResult(completedStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONTINUE_ACK) {
            // 通知确认继续：仅收起提醒通知，当前录音采集保持不断流（默认继续）。
            cancelContinueReminder()
            return START_NOT_STICKY
        }
        newestStartId = startId
        if (intent?.action == ACTION_DISCARD) {
            cancelContinueReminder()
            controller.discardCapture(idle)
            requestRecordingTileUpdate(this)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FINISH) {
            cancelContinueReminder()
            controller.finish(idle)
            requestRecordingTileUpdate(this)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CAPTURE) {
            idle()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            controller.report(R.string.recording_capture_failed)
            idle()
            return START_NOT_STICKY
        }
        ensureContinueWatcher()
        controller.capture(intent.getStringExtra("session"), idle)
        requestRecordingTileUpdate(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelContinueReminder()
        watcherJob?.cancel()
        serviceScope.cancel()
        controller.pause()
        requestRecordingTileUpdate(this)
        super.onDestroy()
    }

    private fun ensureContinueWatcher() {
        if (watcherJob?.isActive == true) return
        watcherJob = serviceScope.launch {
            var trackedSessionId: String? = null
            var lastRemindedMinutes = 0
            var lastTileSignature: Pair<String?, RecordingPhase?>? = null
            controller.state.collect { state ->
                val active = state.sessions.firstOrNull { it.id == state.activeId }
                val signature = state.activeId to active?.phase
                if (signature != lastTileSignature) {
                    lastTileSignature = signature
                    requestRecordingTileUpdate(this@RecordingService)
                }
                if (active == null || active.phase != RecordingPhase.Recording) {
                    if (active == null) {
                        trackedSessionId = null
                        lastRemindedMinutes = 0
                    }
                    cancelContinueReminder()
                    return@collect
                }
                if (trackedSessionId != active.id) {
                    trackedSessionId = active.id
                    // 新会话或续录衔接时，记录当前已跨过的 30 分钟节点，避免立即误弹旧节点通知。
                    lastRemindedMinutes = recordingContinueReminderMinutes(state.durationMs) ?: 0
                }
                val milestone = recordingContinueReminderMinutes(state.durationMs)
                if (milestone != null && milestone > lastRemindedMinutes) {
                    lastRemindedMinutes = milestone
                    postContinueReminderNotification(milestone)
                }
            }
        }
    }

    private fun postContinueReminderNotification(minutes: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CONTINUE_CHANNEL_ID,
                getString(R.string.recording_continue_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = PendingIntent.getActivity(this, 403, recordingIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val keepRecording = PendingIntent.getService(
            this,
            404,
            Intent(this, RecordingService::class.java).setAction(ACTION_CONTINUE_ACK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val finishRecording = PendingIntent.getService(
            this,
            405,
            Intent(this, RecordingService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CONTINUE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording_mic)
            .setContentTitle(getString(R.string.recording_continue_title, minutes))
            .setContentText(getString(R.string.recording_continue_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.recording_continue_body)))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(CONTINUE_NOTIFICATION_TIMEOUT_MS)
            .addAction(0, getString(R.string.recording_continue_action_keep), keepRecording)
            .addAction(0, getString(R.string.recording_continue_action_finish), finishRecording)
        runCatching { manager.notify(CONTINUE_NOTIFICATION_ID, builder.build()) }
    }

    private fun cancelContinueReminder() {
        getSystemService(NotificationManager::class.java)?.cancel(CONTINUE_NOTIFICATION_ID)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "recording", getString(R.string.recording_title), NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(this, 401, recordingIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 402,
            Intent(this, RecordingService::class.java).setAction(ACTION_FINISH), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "recording")
            .setSmallIcon(R.drawable.ic_recording_mic)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(getString(R.string.recording_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.recording_finish), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        internal const val ACTION_CAPTURE = "recording.capture"
        internal const val ACTION_FINISH = "recording.finish"
        internal const val ACTION_DISCARD = "recording.discard"
        internal const val ACTION_CONTINUE_ACK = "recording.continue_ack"
        private const val FOREGROUND_NOTIFICATION_ID = 7091
        private const val CONTINUE_NOTIFICATION_ID = 7092
        private const val CONTINUE_CHANNEL_ID = "recording_continue_reminder"
        private const val CONTINUE_NOTIFICATION_TIMEOUT_MS = 60_000L

        internal fun begin(context: Context, id: String?) {
            context.startForegroundService(Intent(context, RecordingService::class.java)
                .setAction(ACTION_CAPTURE).putExtra("session", id))
        }

        internal fun discard(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_DISCARD))
        }

        internal fun finish(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_FINISH))
        }
    }
}
