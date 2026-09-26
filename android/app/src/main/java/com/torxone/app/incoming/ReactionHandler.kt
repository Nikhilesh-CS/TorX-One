package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.ReactionDao
import com.torxone.app.data.entity.ReactionEntity
import com.torxone.app.protocol.MessageReaction
import com.torxone.app.protocol.ReactionOperation
import com.torxone.app.protocol.SecureEnvelope

class ReactionHandler(
    private val reactionDao: ReactionDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "ReactionHandler"
    }

    suspend fun handleReaction(envelope: SecureEnvelope) {
        val reaction = try {
            MessageReaction.fromByteArray(envelope.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode MessageReaction: ${e.message}")
            return
        }

        val targetMsg = messageDao.getById(reaction.targetMessageId)
        if (targetMsg == null) {
            Log.w(TAG, "Target message ${reaction.targetMessageId.take(8)} for reaction not found")
            return
        }

        val conversationId = targetMsg.conversationId
        val senderId = envelope.senderIdentity

        when (reaction.operation) {
            ReactionOperation.ADD -> {
                Log.d(TAG, "[REACTION ADD] user=${senderId.take(8)} emoji=${reaction.emoji} on msg=${reaction.targetMessageId.take(8)}")
                reactionDao.insertOrUpdate(
                    ReactionEntity(
                        messageId = reaction.targetMessageId,
                        conversationId = conversationId,
                        senderId = senderId,
                        emoji = reaction.emoji,
                        createdAt = envelope.timestamp
                    )
                )
            }
            ReactionOperation.REMOVE -> {
                Log.d(TAG, "[REACTION REMOVE] user=${senderId.take(8)} emoji=${reaction.emoji} on msg=${reaction.targetMessageId.take(8)}")
                reactionDao.remove(
                    messageId = reaction.targetMessageId,
                    senderId = senderId,
                    emoji = reaction.emoji
                )
            }
        }
    }
}
