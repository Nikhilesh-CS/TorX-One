package com.torxone.app.chat

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.crypto.CryptoEngine
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.entity.*
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * ChatService — the feature-layer entry point for chat operations.
 *
 * Architecture: ChatScreen → ChatViewModel → ChatService → TorXAgent
 * Never: ChatScreen → TorXAgent directly
 * Never: ChatService → TransportRouter directly
 */
class ChatService(
    private val agent: TorXAgent,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) {
    companion object {
        private const val TAG = "ChatService"
    }

    /**
     * Send a text message.
     *
     * Flow (from section 10 of master plan):
     * 1. Generate messageId
     * 2. Insert local message with PREPARING status
     * 3. Build SecureEnvelope
     * 4. Encrypt via Double Ratchet
     * 5. Persist encrypted delivery item atomically with ratchet state
     * 6. Update message status to QUEUED
     * 7. TorXAgent wakes and drives transport
     */
    suspend fun sendTextMessage(
        conversationId: String,
        connectionId: String,
        queueAddress: String,
        localIdentityId: String,
        recipientId: String,
        text: String
    ): String {
        val messageId = UUID.randomUUID().toString()

        Log.d(TAG, "Sending text message $messageId to conversation $conversationId")

        // Step 1: Insert local UI state immediately
        val message = MessageEntity(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderId = localIdentityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.CREATED.name
        )
        messageDao.upsert(message)

        // Step 2: Build SecureEnvelope
        val envelope = SecureEnvelope(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = localIdentityId,
            recipientBinding = recipientId,
            messageType = MessageType.TEXT,
            payload = text.toByteArray(Charsets.UTF_8)
        )

        // Step 3: Encrypt (placeholder — full Double Ratchet in crypto layer)
        // TODO: Replace with actual SessionCrypto.encrypt() call
        val envelopeBytes = serializeEnvelope(envelope)
        val encryptionKey = CryptoEngine.randomBytes(32) // Placeholder
        val ciphertext = CryptoEngine.encryptAesGcm(encryptionKey, envelopeBytes)
        val queueAuth = CryptoEngine.hmacSha256(encryptionKey, queueAddress.toByteArray())

        // Step 4: Update status to ENCRYPTED
        messageDao.updateStatus(messageId, DeliveryStatus.ENCRYPTED.name)

        // Step 5: Create delivery item and enqueue
        val deliveryItem = DeliveryItem(
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connectionId,
            queueAddress = queueAddress,
            ciphertext = ciphertext,
            queueAuthenticator = queueAuth
        )

        agent.enqueue(deliveryItem)

        // Step 6: Update conversation preview
        conversationDao.updateLastMessage(
            conversationId = conversationId,
            messageId = messageId,
            preview = text.take(100),
            time = System.currentTimeMillis()
        )

        Log.d(TAG, "Message $messageId queued for delivery")
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

    /**
     * Placeholder serializer for SecureEnvelope.
     * TODO: Use proper protobuf or CBOR serialization.
     */
    private fun serializeEnvelope(envelope: SecureEnvelope): ByteArray {
        // Simple format for now: version|messageId|conversationId|sender|recipient|type|timestamp|payloadLen|payload
        val parts = listOf(
            envelope.protocolVersion.toString(),
            envelope.logicalMessageId,
            envelope.conversationId,
            envelope.senderIdentity,
            envelope.recipientBinding,
            envelope.messageType.name,
            envelope.timestamp.toString(),
            envelope.payload.size.toString()
        )
        val header = parts.joinToString("|").toByteArray(Charsets.UTF_8)
        return header + byteArrayOf(0x00) + envelope.payload
    }
}
