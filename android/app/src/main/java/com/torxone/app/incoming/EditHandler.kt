package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.protocol.MessageEdit
import com.torxone.app.protocol.SecureEnvelope

class EditHandler(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val notificationManager: com.torxone.app.notifications.TorXNotificationManager? = null
) {
    companion object {
        private const val TAG = "EditHandler"
    }

    suspend fun handleEdit(envelope: SecureEnvelope): Boolean {
        val edit = try {
            MessageEdit.fromByteArray(envelope.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode MessageEdit")
            return false
        }

        val targetMsg = messageDao.getById(edit.targetMessageId)
        if (targetMsg == null) {
            Log.w(TAG, "Edit target not found")
            return false
        }
        if (targetMsg.conversationId != (envelope.groupMetadata?.groupId ?: envelope.conversationId)) return false

        // Rule 1: Only original sender may edit
        if (targetMsg.senderId != envelope.senderIdentity) {
            Log.w(TAG, "Edit rejected: sender is not original author")
            return false
        }

        // Rule 2: Cannot edit an already deleted message
        if (com.torxone.app.privacy.DisappearingPolicy.expired(targetMsg.expiresAt, System.currentTimeMillis())) {
            // The authenticated author may retry an old edit after expiry. Accept/ACK
            // without restoring text or blocking every following directional sequence.
            return true
        }
        if (targetMsg.deletedAt != null) {
            Log.w(TAG, "Edit rejected: message was already deleted")
            return false
        }

        // Rule 3: new version > stored version; duplicate or older edit ignored
        if (targetMsg.editVersion == Int.MAX_VALUE || edit.editVersion != targetMsg.editVersion + 1) {
            Log.d(TAG, "Edit rejected: incoming version is not the exact next version")
            return false
        }

        Log.i(TAG, "[EDIT] Applying authenticated update")

        messageDao.updateBodyAndEdit(
            messageId = edit.targetMessageId,
            newBody = edit.newText,
            editVersion = edit.editVersion,
            editedAt = edit.editedAt
        )

        conversationDao.updateLastMessagePreviewIfLatest(
            messageId = edit.targetMessageId,
            preview = edit.newText.take(100)
        )

        notificationManager?.onMessageEdited(
            conversationId = targetMsg.conversationId,
            messageId = edit.targetMessageId,
            newText = edit.newText
        )

        return true
    }
}
