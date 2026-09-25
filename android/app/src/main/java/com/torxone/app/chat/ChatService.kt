package com.torxone.app.chat

import android.util.Log
import androidx.room.withTransaction
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.entity.*
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * ChatService — Application feature layer for messaging.
 *
 * Responsibilities:
 * - Validates text inputs and conversational semantics
 * - Encrypts SecureEnvelope via Double Ratchet SessionCrypto
 * - Commits Message + Outbox atomically in Room database transaction
 * - Awakens TorXAgent for network delivery
 *
 * Invariant: Never calls Nearby or network transports directly.
 */
class ChatService(
    private val database: TorXDatabase,
    private val sessionCrypto: SessionCrypto,
    private val connectionManager: ConnectionManager,
    private val agent: TorXAgent,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao
) {
    companion object {
        private const val TAG = "ChatService"
    }

    /**
     * Send a text message through the golden path.
     */
    suspend fun sendTextMessage(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        text: String
    ): String {
        require(text.isNotBlank()) { "Message text cannot be blank" }

        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val messageId = UUID.randomUUID().toString()
        val deliveryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        Log.d(TAG, "[SEND] msg=${messageId.take(8)} to conv=${conversationId.take(8)}")

        // 1. Build SecureEnvelope
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = localIdentityId,
            recipientBinding = recipientId,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)

        // 2. Cryptographically bind AAD to prevent routing metadata tampering
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

        // 3. Encrypt via Double Ratchet
        val encryptedSessionMessage = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
        val opaqueCiphertext = encryptedSessionMessage.serialize()

        // 4. Prepare database entities
        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderId = localIdentityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name,
            createdAt = now
        )

        val outboxEntity = OutboxEntity(
            deliveryId = deliveryId,
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connection.connectionId,
            queueAddress = connection.sendQueueId,
            ciphertext = opaqueCiphertext,
            queueAuthenticator = connection.sendAuth,
            status = DeliveryStatus.QUEUED.name,
            attemptCount = 0,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now
        )

        // 5. Critical atomic send transaction: Message + Outbox committed together
        database.withTransaction {
            messageDao.insertIfAbsent(messageEntity)
            outboxDao.insert(outboxEntity)
            conversationDao.updateLastMessage(
                conversationId = conversationId,
                messageId = messageId,
                preview = text.take(100),
                time = now
            )
        }

        // 6. Notify TorXAgent to drive transport
        val deliveryItem = DeliveryItem(
            deliveryId = deliveryId,
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connection.connectionId,
            queueAddress = connection.sendQueueId,
            ciphertext = opaqueCiphertext,
            queueAuthenticator = connection.sendAuth,
            status = DeliveryStatus.QUEUED,
            attemptCount = 0,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now
        )
        agent.enqueue(deliveryItem)

        Log.d(TAG, "[QUEUE] msg=${messageId.take(8)} queued for delivery")
        return messageId
    }

    /**
     * Observe messages for a conversation.
     */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.observeByConversation(conversationId)
    }

    /**
     * Observe all conversations.
     */
    fun observeConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.observeAll()
    }
}
