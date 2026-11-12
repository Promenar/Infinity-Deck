package com.promenar.infinity.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.promenar.infinity.R

internal const val RECORDING_LINK_ACTION = "com.promenar.infinity.action.OPEN_RECORDING"
internal const val RECORDING_LINK_URI = "infinitydeck://recording/open"

internal fun recordingIntent(context: Context, autoStart: Boolean = false) =
    Intent(context, RecordingActivity::class.java).apply {
        if (autoStart) action = "recording.start"
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val open = Runnable {
            val intent = recordingIntent(this, autoStart = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 400, intent, PendingIntent.FLAG_IMMUTABLE))
            } else collapseAndStartLegacy(intent)
        }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    /** API 33 及以下只有 Intent 版本。 */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseAndStartLegacy(intent: Intent) = startActivityAndCollapse(intent)
}

class RecordingService : Service() {
    private val controller get() = recordingController()
    @Volatile private var newestStartId = 0
    private val idle: () -> Unit = {
        val completedStartId = newestStartId
        mainExecutor.execute {
            if (controller.state.value.activeId == null && stopSelfResult(completedStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        newestStartId = startId
        if (intent?.action == "recording.finish") {
            controller.finish(idle)
            return START_NOT_STICKY
        }
        if (intent?.action != "recording.capture") {
            idle()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 7091, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            controller.report(R.string.recording_capture_failed)
            idle()
            return START_NOT_STICKY
        }
        controller.capture(intent.getStringExtra("session"), idle)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.pause()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "recording", getString(R.string.recording_title), NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(this, 401, recordingIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 402,
            Intent(this, RecordingService::class.java).setAction("recording.finish"), PendingIntent.FLAG_IMMUTABLE)
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
        internal fun begin(context: Context, id: String?) {
            context.startForegroundService(Intent(context, RecordingService::class.java)
                .setAction("recording.capture").putExtra("session", id))
        }

        internal fun finish(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction("recording.finish"))
        }
    }
}
