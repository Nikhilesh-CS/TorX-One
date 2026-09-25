package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.protocol.MessageEdit
import com.torxone.app.protocol.SecureEnvelope

class EditHandler(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) {
    companion object {
        private const val TAG = "EditHandler"
    }

    suspend fun handleEdit(envelope: SecureEnvelope): Boolean {
        val edit = try {
            MessageEdit.fromByteArray(envelope.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode MessageEdit: ${e.message}")
            return false
        }

        val targetMsg = messageDao.getById(edit.targetMessageId)
        if (targetMsg == null) {
            Log.w(TAG, "Edit target ${edit.targetMessageId.take(8)} not found")
            return false
        }

        // Rule 1: Only original sender may edit
        if (targetMsg.senderId != envelope.senderIdentity) {
            Log.w(
                TAG,
                "Edit rejected: sender ${envelope.senderIdentity.take(8)} is not original author ${targetMsg.senderId.take(8)}"
            )
            return false
        }

        // Rule 2: Cannot edit an already deleted message
        if (targetMsg.deletedAt != null) {
            Log.w(TAG, "Edit rejected: message ${edit.targetMessageId.take(8)} was already deleted")
            return false
        }

        // Rule 3: new version > stored version; duplicate or older edit ignored
        if (edit.editVersion <= targetMsg.editVersion) {
            Log.d(
                TAG,
                "Edit ignored: incoming version ${edit.editVersion} <= stored version ${targetMsg.editVersion}"
            )
            return false
        }

        Log.i(
            TAG,
            "[EDIT] Updating msg=${edit.targetMessageId.take(8)} to version=${edit.editVersion}"
        )

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

        return true
    }
}
