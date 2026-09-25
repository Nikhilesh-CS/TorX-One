package com.torxone.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.torxone.app.MainActivity
import com.torxone.app.incoming.ActiveConversationTracker

/**
 * NotificationCoordinator — Centralized notification authority.
 *
 * Rules (Sections 46 & 47):
 * - Notifications only trigger AFTER receive persistence commits.
 * - Suppressed if conversation is currently active in foreground.
 * - Suppressed if conversation is muted.
 */
class NotificationCoordinator(
    private val context: Context,
    private val activeConversationTracker: ActiveConversationTracker
) {
    companion object {
        const val CHANNEL_MESSAGES = "torx_messages"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MESSAGES,
                "TorX Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming end-to-end encrypted messages"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun notifyMessageReceived(
        conversationId: String,
        senderName: String,
        messageText: String,
        messageId: String,
        isMuted: Boolean = false
    ) {
        // 1. Check if conversation is active in foreground
        if (activeConversationTracker.getActiveConversationId() == conversationId) {
            return
        }

        // 2. Check if muted
        if (isMuted) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(senderName)
            .setContentText(messageText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0xFFFF), notification)
        } catch (_: SecurityException) {
            // Permission not granted on Android 13+
        }
    }

    fun cancelNotification(conversationId: String) {
        notificationManager.cancel(NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0xFFFF))
    }
}
