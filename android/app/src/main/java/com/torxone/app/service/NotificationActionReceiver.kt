package com.torxone.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.torxone.app.TorXOneApplication
import com.torxone.app.notifications.TorXNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * NotificationActionReceiver — Handles background actions from notifications:
 * 1. Inline Quick Reply
 * 2. Mark as Read
 *
 * Invariant:
 * Inline reply routes through ChatService.sendTextMessage() -> SessionCrypto -> TorXAgent.
 * Absolutely NEVER calls NearbyTransport directly.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val conversationId = intent.getStringExtra(TorXNotificationManager.EXTRA_CONVERSATION_ID) ?: return
        val relationshipId = intent.getStringExtra(TorXNotificationManager.EXTRA_RELATIONSHIP_ID) ?: conversationId

        val app = TorXOneApplication.instance
        val chatService = app.chatService
        val identityRepo = app.identityRepository
        val contactDao = app.database.contactDao()
        val notificationManager = app.notificationManager

        when (action) {
            TorXNotificationManager.ACTION_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(TorXNotificationManager.KEY_TEXT_REPLY)?.toString()

                if (!replyText.isNullOrBlank()) {
                    Log.i(TAG, "[INLINE REPLY] Sending reply in conv=$conversationId")
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val conv = app.database.conversationDao().getById(conversationId) ?: run {
                                Log.e(TAG, "[INLINE REPLY] Conversation not found: $conversationId")
                                return@launch
                            }
                            val isGroup = conv.type == com.torxone.app.data.entity.ConversationType.GROUP

                            if (isGroup) {
                                app.groupService.sendGroupText(
                                    groupId = conversationId,
                                    text = replyText
                                )
                                app.groupService.markGroupRead(conversationId)
                                notificationManager.cancelForConversation(conversationId)
                            } else {
                                val localIdentity = identityRepo.loadIdentity() ?: run {
                                    Log.e(TAG, "[INLINE REPLY] Fail closed: Local identity missing")
                                    return@launch
                                }
                                val contact = contactDao.getByConversationId(conversationId) ?: run {
                                    Log.e(TAG, "[INLINE REPLY] Fail closed: Contact missing for conv=$conversationId")
                                    return@launch
                                }
                                val relationshipId = contact.relationshipId
                                val recipientId = contact.remoteIdentityId
                                if (!contact.isRemoteIdentityKnown) {
                                    Log.e(TAG, "[INLINE REPLY] Fail closed: Security information for contact '${contact.displayName}' needs to be refreshed. Reconnect or re-add this contact.")
                                    return@launch
                                }

                                // Route through golden path
                                chatService.sendTextMessage(
                                    conversationId = conversationId,
                                    relationshipId = relationshipId,
                                    localIdentityId = localIdentity.identityId,
                                    recipientId = recipientId,
                                    text = replyText
                                )

                                // Mark incoming messages as read upon reply and dismiss notification
                                chatService.markConversationRead(
                                    conversationId = conversationId,
                                    relationshipId = relationshipId,
                                    localIdentityId = localIdentity.identityId,
                                    recipientId = recipientId
                                )
                                notificationManager.cancelForConversation(conversationId)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send inline reply: ${e.message}", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            TorXNotificationManager.ACTION_MARK_AS_READ -> {
                Log.i(TAG, "[MARK READ] Marking conv=$conversationId read from notification")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val conv = app.database.conversationDao().getById(conversationId) ?: run {
                            Log.e(TAG, "[MARK READ] Conversation not found: $conversationId")
                            return@launch
                        }
                        val isGroup = conv.type == com.torxone.app.data.entity.ConversationType.GROUP

                        if (isGroup) {
                            app.groupService.markGroupRead(conversationId)
                            notificationManager.cancelForConversation(conversationId)
                        } else {
                            val localIdentity = identityRepo.loadIdentity() ?: run {
                                Log.e(TAG, "[MARK READ] Fail closed: Local identity missing")
                                return@launch
                            }
                            val contact = contactDao.getByConversationId(conversationId) ?: run {
                                Log.e(TAG, "[MARK READ] Fail closed: Contact missing for conv=$conversationId")
                                return@launch
                            }
                            val relationshipId = contact.relationshipId
                            val recipientId = contact.remoteIdentityId
                            if (!contact.isRemoteIdentityKnown) {
                                Log.e(TAG, "[MARK READ] Fail closed: Security information for contact '${contact.displayName}' needs to be refreshed. Reconnect or re-add this contact.")
                                return@launch
                            }

                            chatService.markConversationRead(
                                conversationId = conversationId,
                                relationshipId = relationshipId,
                                localIdentityId = localIdentity.identityId,
                                recipientId = recipientId
                            )
                            notificationManager.cancelForConversation(conversationId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark as read: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
