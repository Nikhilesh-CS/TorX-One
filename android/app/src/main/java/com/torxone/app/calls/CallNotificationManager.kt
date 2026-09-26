package com.torxone.app.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * CallNotificationManager — Dedicated call notification channel and notifications.
 *
 * Channels:
 *   "torx_calls"          → High importance, ringtone, vibration, full-screen intent
 *   "torx_active_call"    → Low importance, ongoing call indicator
 *
 * Separate from chat message notifications per the user spec.
 *
 * Incoming call → high-priority notification with Decline/Answer actions
 * Active call   → ongoing notification with Hang up action
 */
class CallNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "CallNotifManager"
        const val CHANNEL_INCOMING = "torx_calls"
        const val CHANNEL_ACTIVE = "torx_active_call"
        const val INCOMING_NOTIFICATION_ID = 9101
        const val ACTIVE_NOTIFICATION_ID = 9102
        const val ACTION_ANSWER = "com.torxone.app.calls.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.torxone.app.calls.ACTION_DECLINE"
        const val ACTION_HANGUP = "com.torxone.app.calls.ACTION_HANGUP"
        const val EXTRA_CALL_ID = "extra_call_id"
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Incoming call channel — high importance with ringtone
        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING,
            "TorX Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call notifications"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(incomingChannel)

        // Active call channel — low importance, ongoing
        val activeChannel = NotificationChannel(
            CHANNEL_ACTIVE,
            "TorX Active Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(activeChannel)
    }

    /**
     * Show incoming call notification with Decline/Answer actions.
     * Uses full-screen intent for immediate visibility.
     */
    fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        callType: CallType
    ) {
        val typeLabel = when (callType) {
            CallType.VOICE -> "voice"
            CallType.VIDEO -> "video"
        }

        // Full-screen intent to open the call UI
        val fullScreenIntent = createCallActivityIntent(callId)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Answer
        val answerIntent = Intent(ACTION_ANSWER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CALL_ID, callId)
        }
        val answerPending = PendingIntent.getBroadcast(
            context, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Decline
        val declineIntent = Intent(ACTION_DECLINE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming TorX call")
            .setContentText("Secure $typeLabel call")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming TorX $typeLabel call")
            .setContentText(callerName)
            .setSubText("End-to-end secured")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_delete, "Decline", declinePending)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(INCOMING_NOTIFICATION_ID, notification)
        Log.d(TAG, "Incoming call notification shown for $callerName")
    }

    /**
     * Show ongoing call notification with Hang up action.
     * Used as the foreground service notification.
     */
    fun buildActiveCallNotification(
        callId: String,
        peerName: String,
        callType: CallType,
        durationText: String? = null
    ): Notification {
        val typeLabel = when (callType) {
            CallType.VOICE -> "voice"
            CallType.VIDEO -> "video"
        }

        val contentIntent = createCallActivityIntent(callId)
        val contentPending = PendingIntent.getActivity(
            context, 3, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(ACTION_HANGUP).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CALL_ID, callId)
        }
        val hangupPending = PendingIntent.getBroadcast(
            context, 4, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (durationText != null) {
            "Secure $typeLabel call · $durationText"
        } else {
            "Secure TorX $typeLabel call"
        }

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Ongoing call with $peerName")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangupPending)
            .setUsesChronometer(durationText == null) // Auto-count if no explicit text
            .build()
    }

    fun showActiveCallNotification(
        callId: String,
        peerName: String,
        callType: CallType,
        durationText: String? = null
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ACTIVE_NOTIFICATION_ID, buildActiveCallNotification(callId, peerName, callType, durationText))
    }

    fun cancelIncomingNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_NOTIFICATION_ID)
    }

    fun cancelActiveNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ACTIVE_NOTIFICATION_ID)
    }

    fun cancelAll() {
        cancelIncomingNotification()
        cancelActiveNotification()
    }

    private fun createCallActivityIntent(callId: String): Intent {
        return Intent(context, Class.forName("com.torxone.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra("navigate_to", "active_call")
        }
    }
}
