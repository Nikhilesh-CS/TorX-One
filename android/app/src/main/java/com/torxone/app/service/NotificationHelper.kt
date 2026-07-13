package com.torxone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.torxone.app.MainActivity
import com.torxone.app.R
import com.torxone.app.data.ContactEntity
import com.torxone.app.data.MessageEntity

object NotificationHelper {
    const val CHANNEL_MESSAGES = "astra_mesh_messages"
    const val CHANNEL_SYSTEM = "astra_mesh_system"
    const val CHANNEL_UPDATES = "astra_mesh_updates"
    const val CHANNEL_CRITICAL = "astra_mesh_critical"

    const val NOTIFICATION_ID_FOREGROUND = 1
    const val NOTIFICATION_ID_SUMMARY = 2

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming TorX One messages"
                enableVibration(true)
            }

            val systemChannel = NotificationChannel(
                CHANNEL_SYSTEM,
                "System",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service connection status"
                setSound(null, null)
                enableVibration(false)
            }

            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                "Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL,
                "Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical connection failures"
                enableVibration(true)
            }

            nm.createNotificationChannels(listOf(messagesChannel, systemChannel, updatesChannel, criticalChannel))
        }
    }

    fun buildForegroundServiceNotification(context: Context, title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun showMessageNotification(context: Context, contact: ContactEntity, unreadMessages: List<MessageEntity>) {
        if (unreadMessages.isEmpty()) return

        val nm = context.getSystemService(NotificationManager::class.java)

        // Intents
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", contact.signingPublicKey)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, contact.signingPublicKey.hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mark as Read Action
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra("contactKey", contact.signingPublicKey)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context, contact.signingPublicKey.hashCode(), markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            0, "Mark as Read", markReadPendingIntent
        ).build()

        // Reply Action
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra("contactKey", contact.signingPublicKey)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, contact.signingPublicKey.hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.EXTRA_REPLY).setLabel("Reply...").build()
        val replyAction = NotificationCompat.Action.Builder(
            0, "Reply", replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val messageCount = unreadMessages.size
        val contentText = if (messageCount == 1) {
            "New encrypted message"
        } else {
            "$messageCount new encrypted messages"
        }

        // Build actual notification
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(contact.name)
            .setContentText(contentText)
            .setStyle(NotificationCompat.InboxStyle().addLine(contentText))
            .setColor(0xFF00A884.toInt()) // WhatsApp Green
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setGroup("TorXOne_Messages")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        nm.notify(contact.signingPublicKey.hashCode(), builder.build())

        // Update Summary Notification
        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(NotificationCompat.InboxStyle()
                .setSummaryText("TorX One Messages")
            )
            .setGroup("TorXOne_Messages")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setColor(0xFF00A884.toInt())

        nm.notify(NOTIFICATION_ID_SUMMARY, summaryBuilder.build())
    }

    fun showUpdateNotification(context: Context, version: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, "update".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Update Available")
            .setContentText("TorX One version $version is now available! Tap to install.")
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        nm.notify("astra_update".hashCode(), builder.build())
    }

    fun clearContactNotifications(context: Context, contactKey: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(contactKey.hashCode())
    }
}
