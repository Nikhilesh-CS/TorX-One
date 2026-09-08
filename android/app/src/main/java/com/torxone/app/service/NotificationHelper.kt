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
    const val CHANNEL_CALLS = "astra_mesh_calls"

    const val NOTIFICATION_ID_FOREGROUND = 1
    const val NOTIFICATION_ID_SUMMARY = 2
    const val NOTIFICATION_ID_INCOMING_CALL = 3
    const val NOTIFICATION_ID_GROUP_JOIN = 4

    fun showGroupJoinRequest(context: Context, groupId: String, groupName: String, memberKey: String) {
        fun action(action: String, title: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply { this.action = action; putExtra("groupId", groupId); putExtra("memberKey", memberKey) }
            val pending = PendingIntent.getBroadcast(context, (groupId + memberKey + action).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, title, pending).build()
        }
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Join request").setContentText("A member wants to join $groupName").setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).addAction(action(NotificationActionReceiver.ACTION_APPROVE_JOIN, "Approve")).addAction(action(NotificationActionReceiver.ACTION_REJECT_JOIN, "Reject")).build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_GROUP_JOIN, n)
    }

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

            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming TorX One calls"
                enableVibration(true)
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE), null)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            nm.createNotificationChannels(listOf(messagesChannel, systemChannel, updatesChannel, criticalChannel, callsChannel))
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

    fun showMessageNotification(context: Context, contact: ContactEntity, unreadMessages: List<MessageEntity>, conversationType: String = "direct") {
        if (unreadMessages.isEmpty()) return

        val nm = context.getSystemService(NotificationManager::class.java)

        // Intents
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", contact.signingPublicKey)
            putExtra("conversation_type", conversationType)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, contact.signingPublicKey.hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mark as Read Action
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra("contactKey", contact.signingPublicKey)
            putExtra("conversationType", conversationType)
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
            putExtra("conversationType", conversationType)
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

    fun showDeferredMessageNotification(context: Context, contact: ContactEntity) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, contact.signingPublicKey.hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(contact.name)
            .setContentText("New secure message received")
            .setColor(0xFF00A884.toInt()) // WhatsApp Green
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setGroup("TorXOne_Messages")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
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

    fun showAppLockSetupNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_app_lock_setup", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, "app_lock_security_update".hashCode(), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Security Update: Protect AstraMesh")
            .setContentText("Set up Biometric or Face Lock to secure your messages and identity.")
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        nm.notify("app_lock_setup".hashCode(), builder.build())
    }

    fun clearContactNotifications(context: Context, contactKey: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(contactKey.hashCode())
    }

    fun showIncomingCall(context: Context, callId: String, peerKey: String, peerName: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_call", callId)
        }
        val open = PendingIntent.getActivity(context, callId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, title: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra("callId", callId)
                putExtra("peerKey", peerKey)
            }
            val pending = PendingIntent.getBroadcast(context, (callId + action).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, title, pending).build()
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming TorX One call")
            .setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(action(NotificationActionReceiver.ACTION_ANSWER_CALL, "Answer"))
            .addAction(action(NotificationActionReceiver.ACTION_DECLINE_CALL, "Decline"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_INCOMING_CALL, notification)
    }

    fun clearIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_INCOMING_CALL)
    }
}
