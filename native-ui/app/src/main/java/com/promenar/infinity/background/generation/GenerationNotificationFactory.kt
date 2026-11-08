package com.promenar.infinity.background.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.promenar.infinity.MainActivity
import com.promenar.infinity.R
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import com.promenar.infinity.navigation.AppIntentRouter

class GenerationNotificationFactory(
    context: Context,
    private val appIntentRouter: AppIntentRouter,
    private val sessionTitle: (String) -> String? = { null },
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun create(snapshot: GenerationTaskSnapshot): Notification {
        ensureChannel()
        val stopPendingIntent = PendingIntent.getService(
            applicationContext,
            snapshot.taskId.hashCode(),
            stopIntent(snapshot.taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_ONE_SHOT,
        )
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            snapshot.taskId.hashCode(),
            openIntent(snapshot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.generation_notification_title))
            .setContentText(
                applicationContext.getString(
                    R.string.p0_progress,
                    snapshot.progress.rounds, snapshot.progress.calls, snapshot.progress.tokens,
                    if (snapshot.progress.estimated) applicationContext.getString(R.string.p0_estimated) else "",
                ),
            )
            .setSubText(applicationContext.getString(snapshot.phase.notificationTextRes()))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                applicationContext.getString(R.string.generation_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    fun create(snapshots: List<GenerationTaskSnapshot>): Notification {
        require(snapshots.isNotEmpty())
        if (snapshots.size == 1) return create(snapshots.single())
        ensureChannel()
        val latest = snapshots.maxWithOrNull(compareBy<GenerationTaskSnapshot> { it.startedOrder }.thenBy { it.startedAt })!!
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext, latest.taskId.hashCode(), openIntent(latest),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            applicationContext, 0, stopAllIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val title = applicationContext.resources.getQuantityString(
            R.plurals.generation_concurrency_title, snapshots.size, snapshots.size,
        )
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        snapshots.forEach { snapshot ->
            val name = sessionTitle(snapshot.sessionId)?.takeIf(String::isNotBlank)
                ?: applicationContext.getString(R.string.generation_concurrency_untitled)
            val phase = if (snapshot.phase == GenerationPhase.QUEUED) {
                applicationContext.getString(R.string.generation_concurrency_queued)
            } else applicationContext.getString(snapshot.phase.notificationTextRes())
            style.addLine(applicationContext.getString(R.string.generation_concurrency_line, name, phase))
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.generation_concurrency_details))
            .setStyle(style)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, applicationContext.getString(R.string.generation_concurrency_stop_all), stopAll)
            .build()
    }

    internal fun stopAllIntent() = Intent(applicationContext, GenerationForegroundService::class.java).apply {
        action = GenerationForegroundService.ACTION_STOP
        putExtra(GenerationForegroundService.EXTRA_STOP_ALL, true)
        data = Uri.parse("infinity-deck://generation/stop-all")
    }

    internal fun openIntent(snapshot: GenerationTaskSnapshot) =
        Intent(applicationContext, MainActivity::class.java).apply {
            val issued = appIntentRouter.issue(snapshot.taskId, snapshot.sessionId)
            action = AppIntentRouter.ACTION_OPEN_GENERATION
            putExtra(AppIntentRouter.EXTRA_SESSION_ID, issued.sessionId)
            putExtra(AppIntentRouter.EXTRA_REQUEST_ID, issued.requestId)
            data = Uri.parse("infinity-deck://generation/open/${Uri.encode(snapshot.taskId)}")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    internal fun stopIntent(taskId: String) =
        Intent(applicationContext, GenerationForegroundService::class.java).apply {
            action = GenerationForegroundService.ACTION_STOP
            putExtra(GenerationForegroundService.EXTRA_TASK_ID, taskId)
            data = Uri.parse("infinity-deck://generation/stop/${Uri.encode(taskId)}")
        }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.generation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.generation_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun GenerationPhase.notificationTextRes(): Int = when (this) {
        GenerationPhase.QUEUED,
        GenerationPhase.PREPARING,
        GenerationPhase.BUILDING_CONTEXT,
        GenerationPhase.CONNECTING -> R.string.generation_notification_preparing
        GenerationPhase.THINKING -> R.string.generation_notification_thinking
        GenerationPhase.EXECUTING_TOOLS -> R.string.generation_notification_executing_tools
        GenerationPhase.STREAMING,
        GenerationPhase.POST_PROCESSING -> R.string.generation_notification_generating
        GenerationPhase.WAITING_APPROVAL,
        GenerationPhase.WAITING_INPUT,
        GenerationPhase.COMPLETED,
        GenerationPhase.FAILED,
        GenerationPhase.CANCELLED,
        GenerationPhase.PERSISTENCE_FAILED -> R.string.generation_notification_finishing
    }

    companion object {
        const val CHANNEL_ID = "generation_background"
        const val NOTIFICATION_ID = 0x4E58
    }
}
