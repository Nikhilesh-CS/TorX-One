package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.protocol.MessageDelete
import com.torxone.app.protocol.SecureEnvelope

class DeleteHandler(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val notificationManager: com.torxone.app.notifications.TorXNotificationManager? = null
) {
    companion object {
        private const val TAG = "DeleteHandler"
    }

    suspend fun handleDelete(envelope: SecureEnvelope): Boolean {
        val delete = try {
            MessageDelete.fromByteArray(envelope.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode MessageDelete")
            return false
        }

        val targetMsg = messageDao.getById(delete.targetMessageId)
        if (targetMsg == null) {
            Log.w(TAG, "Delete target not found")
            return false
        }
        if (targetMsg.conversationId != (envelope.groupMetadata?.groupId ?: envelope.conversationId)) return false

        // Rule 1: Only original sender may delete
        if (targetMsg.senderId != envelope.senderIdentity) {
            Log.w(TAG, "Delete rejected: sender is not original author")
            return false
        }

        // Rule 2: If already deleted, idempotent no-op
        if (targetMsg.deletedAt != null) {
            Log.d(TAG, "Message already deleted (idempotent)")
            return true
        }

        Log.i(TAG, "[DELETE] Converting to tombstone")

        messageDao.markDeleted(
            messageId = delete.targetMessageId,
            deletedAt = delete.deletedAt
        )

        conversationDao.updateLastMessagePreviewIfLatest(
            messageId = delete.targetMessageId,
            preview = "This message was deleted"
        )

        notificationManager?.onMessageTombstoned(
            conversationId = targetMsg.conversationId,
            messageId = delete.targetMessageId
        )

        return true
    }
}
