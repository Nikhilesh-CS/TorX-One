package com.torxone.app.updates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.torxone.app.R
import com.torxone.app.profile.AppSettingsRepository
import kotlinx.coroutines.flow.first
import java.util.UUID

class UpdateNotifications(private val context: Context) {
    companion object {
        const val DOWNLOAD_ID = 91001
        const val UPDATE_ID = 91002
        const val CHANNEL = "torx_updates"
        const val DOWNLOAD_CHANNEL = "torx_update_downloads"
    }
    private fun channel(id: String, name: String, importance: Int) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, name, importance))
    }
    private fun open() = PendingIntent.getActivity(context, UPDATE_ID,
        Intent(context, UpdateActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun download(id: UUID): Notification {
        channel(DOWNLOAD_CHANNEL, "Update downloads", NotificationManager.IMPORTANCE_LOW)
        return NotificationCompat.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_torx).setContentTitle("Downloading TorX One update")
            .setContentText("Open App updates for progress. Installation requires your approval.")
            .setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(id)).build()
    }
    private suspend fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        AppSettingsRepository(context).notificationsEnabled.first()
    suspend fun available(release: UpdateRelease): Boolean {
        if (!allowed()) return false
        channel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try {
            NotificationManagerCompat.from(context).notify(UPDATE_ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_torx)
                    .setContentTitle("TorX One update available").setContentText("Version ${release.versionName} is ready to download")
                    .setContentIntent(open()).setAutoCancel(true).setOnlyAlertOnce(true).build())
            true
        } catch (_: SecurityException) { false }
    }
    suspend fun ready(release: UpdateRelease) {
        if (!allowed()) return
        channel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW)
        try {
            NotificationManagerCompat.from(context).notify(UPDATE_ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_torx)
                    .setContentTitle("TorX One update ready to install").setContentText("Version ${release.versionName} has been verified")
                    .setContentIntent(open()).setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (_: SecurityException) { /* In-app installation remains available. */ }
    }
    fun dismiss() { NotificationManagerCompat.from(context).cancel(UPDATE_ID) }
}
