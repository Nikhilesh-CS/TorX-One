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
        const val ACTION_ANSWER_CALL = "com.torxone.app.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.torxone.app.ACTION_DECLINE_CALL"
        const val ACTION_APPROVE_JOIN = "com.torxone.app.ACTION_APPROVE_JOIN"
        const val ACTION_REJECT_JOIN = "com.torxone.app.ACTION_REJECT_JOIN"
        const val EXTRA_REPLY = "extra_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val contactKey = intent.getStringExtra("contactKey")
        val service = TorXOneService.getInstance() ?: return
        if (intent.action == ACTION_APPROVE_JOIN || intent.action == ACTION_REJECT_JOIN) {
            val groupId = intent.getStringExtra("groupId") ?: return
            val memberKey = intent.getStringExtra("memberKey") ?: return
            CoroutineScope(Dispatchers.IO).launch { if (intent.action == ACTION_APPROVE_JOIN) service.groupManager.approveJoin(groupId, memberKey) else service.groupManager.rejectJoin(groupId, memberKey) }
            return
        }
        if (intent.action == ACTION_ANSWER_CALL || intent.action == ACTION_DECLINE_CALL) {
            if (intent.action == ACTION_ANSWER_CALL) service.callManager.acceptIncomingCall()
            else service.callManager.rejectIncomingCall()
            NotificationHelper.clearIncomingCall(context)
            return
        }
        val key = contactKey ?: return
        val conversationType = intent.getStringExtra("conversationType") ?: "direct"
        val db = service.db
        val router = service.messageRouter

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_REPLY)?.toString()
                        if (!replyText.isNullOrBlank()) {
                            Log.d("NotificationAction", "Sending quick reply to $key ($conversationType)")
                            if (conversationType == "group") {
                                router.sendGroupMessage(key, replyText)
                            } else {
                                router.sendMessage(key, replyText)
                            }
                            // Mark messages as read since user replied
                            db.messageDao().markMessagesAsRead(key, conversationType)
                            // Clear notification
                            NotificationHelper.clearContactNotifications(context, key)
                        }
                    }
                    ACTION_MARK_READ -> {
                        Log.d("NotificationAction", "Marking messages as read for $key ($conversationType)")
                        db.messageDao().markMessagesAsRead(key, conversationType)
                        NotificationHelper.clearContactNotifications(context, key)
                    }
                    ACTION_MUTE -> {
                        val durationMs = intent.getLongExtra("durationMs", 0L)
                        Log.d("NotificationAction", "Muting $key ($conversationType) for $durationMs ms")
                        val muteUntil = if (durationMs == -1L) -1L else System.currentTimeMillis() + durationMs
                        if (conversationType == "group") {
                            val group = db.groupDao().getGroup(key)
                            if (group != null) {
                                db.groupDao().insertGroup(group.copy(muteUntil = muteUntil))
                            }
                        } else {
                            val contact = db.contactDao().getContact(key)
                            if (contact != null) {
                                db.contactDao().insertContact(contact.copy(muteUntil = muteUntil))
                            }
                        }
                        NotificationHelper.clearContactNotifications(context, key)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
