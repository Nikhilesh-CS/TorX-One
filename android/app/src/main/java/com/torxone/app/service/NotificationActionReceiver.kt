package com.torxone.app.service

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.torxone.app.ACTION_REPLY"
        const val ACTION_MARK_READ = "com.torxone.app.ACTION_MARK_READ"
        const val ACTION_MUTE = "com.torxone.app.ACTION_MUTE"
        const val EXTRA_REPLY = "extra_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val contactKey = intent.getStringExtra("contactKey") ?: return
        val service = TorXOneService.getInstance() ?: return
        val db = service.db
        val router = service.messageRouter

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_REPLY)?.toString()
                        if (!replyText.isNullOrBlank()) {
                            Log.d("NotificationAction", "Sending quick reply to $contactKey")
                            router.sendMessage(contactKey, replyText)
                            // Mark messages as read since user replied
                            db.messageDao().markMessagesAsRead(contactKey)
                            // Clear notification
                            NotificationHelper.clearContactNotifications(context, contactKey)
                        }
                    }
                    ACTION_MARK_READ -> {
                        Log.d("NotificationAction", "Marking messages as read for $contactKey")
                        db.messageDao().markMessagesAsRead(contactKey)
                        NotificationHelper.clearContactNotifications(context, contactKey)
                    }
                    ACTION_MUTE -> {
                        val durationMs = intent.getLongExtra("durationMs", 0L)
                        Log.d("NotificationAction", "Muting $contactKey for $durationMs ms")
                        val contact = db.contactDao().getContact(contactKey)
                        if (contact != null) {
                            val muteUntil = if (durationMs == -1L) -1L else System.currentTimeMillis() + durationMs
                            db.contactDao().insertContact(contact.copy(muteUntil = muteUntil))
                            NotificationHelper.clearContactNotifications(context, contactKey)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
