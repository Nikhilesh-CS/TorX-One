package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.connection.Connection
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.SecureEnvelope

class ChatReceiver(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val activeConversationTracker: ActiveConversationTracker
) {
    companion object {
        private const val TAG = "ChatReceiver"
    }

    suspend fun receiveTextMessage(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        val messageId = envelope.logicalMessageId
        val text = String(envelope.payload, Charsets.UTF_8)
        val conversationId = envelope.conversationId

        Log.i(TAG, "[RX] msg=${messageId.take(8)} received in conv=${conversationId.take(8)}")

        if (messageDao.exists(messageId)) {
            Log.d(TAG, "[RX] msg=${messageId.take(8)} already exists in DB")
            return true
        }

        var conv = conversationDao.getById(conversationId)
        if (conv == null) {
            conv = ConversationEntity(
                conversationId = conversationId,
                type = ConversationType.DIRECT,
                title = "Contact",
                unreadCount = 0,
                lastMessageId = messageId,
                lastMessagePreview = text.take(100),
                lastMessageTime = envelope.timestamp
            )
            conversationDao.upsert(conv)
        }

        val isActive = activeConversationTracker.getActiveConversationId() == conversationId
        val unreadIncrement = if (isActive) 0 else 1
        val now = System.currentTimeMillis()

        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderId = envelope.senderIdentity,
            type = MessageType.TEXT.name,
            body = text,
            direction = MessageDirection.INCOMING,
            status = if (isActive) DeliveryStatus.READ.name else DeliveryStatus.DELIVERED.name,
            createdAt = envelope.timestamp,
            receivedAt = now,
            readAt = if (isActive) now else null,
            replyToMessageId = envelope.replyToMessageId
        )

        messageDao.insertIfAbsent(messageEntity)

        conversationDao.updateLastMessage(
            conversationId = conversationId,
            messageId = messageId,
            preview = text.take(100),
            time = envelope.timestamp
        )

        if (!isActive) {
            conversationDao.updateUnreadCount(conversationId, conv.unreadCount + unreadIncrement)
        } else {
            conversationDao.updateUnreadCount(conversationId, 0)
        }

        Log.i(TAG, "[DB] msg=${messageId.take(8)} persisted successfully")
        return true
    }
}
