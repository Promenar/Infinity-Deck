package com.promenar.infinity.background.indexing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.promenar.infinity.MainActivity
import com.promenar.infinity.R

internal class IndexNotificationFactory(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    fun create(state: IndexNotificationState?): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state == null) {
            app.getString(R.string.index_notification_starting)
        } else {
            val name = state.docTitle ?: app.getString(R.string.index_notification_untitled)
            app.getString(R.string.index_notification_content, name, state.position, state.total)
        }
        return NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.index_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setProgress(100, state?.progressPercent ?: 0, state?.progressPercent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.index_notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = app.getString(R.string.index_notification_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
        )
    }

    companion object {
        const val CHANNEL_ID = "document_indexing"
        const val NOTIFICATION_ID = 0x4E5A
    }
}
