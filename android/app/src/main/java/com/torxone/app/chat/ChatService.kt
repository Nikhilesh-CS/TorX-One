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
import com.torxone.app.data.dao.LocalMessageStateDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.dao.ReactionDao
import com.torxone.app.data.entity.*
import com.torxone.app.notifications.TorXNotificationManager
import com.torxone.app.protocol.*
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
    private val database: TorXDatabase? = null,
    private val sessionCrypto: SessionCrypto,
    private val connectionManager: ConnectionManager,
    private val agent: TorXAgent,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
    private val reactionDao: ReactionDao? = null,
    private val localMessageStateDao: LocalMessageStateDao? = null,
    private val notificationManager: TorXNotificationManager? = null,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block ->
        if (database != null) database.withTransaction { block() } else block()
    }
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
        text: String,
        replyToMessageId: String? = null
    ): String {
        require(text.isNotBlank()) { "Message text cannot be blank" }

        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val messageId = UUID.randomUUID().toString()
        val deliveryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        Log.d(TAG, "[SEND] msg=${messageId.take(8)} to conv=${conversationId.take(8)} replyTo=${replyToMessageId?.take(8)}")

        val sendSeq = connectionManager.incrementSendSequence(relationshipId)

        // 1. Build SecureEnvelope with directional sequence and optional replyToMessageId
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = localIdentityId,
            recipientBinding = recipientId,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = text.toByteArray(Charsets.UTF_8),
            replyToMessageId = replyToMessageId,
            directionSequence = sendSeq
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)

        // 2. Cryptographically bind AAD to prevent routing metadata tampering
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

        // 3. Critical atomic send boundary: Encrypt + Ratchet Save + Message + Outbox in one Room transaction
        var opaqueCiphertext: ByteArray? = null

        sessionCrypto.encryptAndCommit(relationshipId, envelopeBytes, aad) { encrypted, updatedState ->
            val ciphertext = encrypted.serialize()
            opaqueCiphertext = ciphertext

            val messageEntity = MessageEntity(
                logicalMessageId = messageId,
                conversationId = conversationId,
                senderId = localIdentityId,
                type = MessageType.TEXT.name,
                body = text,
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.QUEUED.name,
                createdAt = now,
                replyToMessageId = replyToMessageId
            )

            val outboxEntity = OutboxEntity(
                deliveryId = deliveryId,
                logicalMessageId = messageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED.name,
                attemptCount = 0,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )

            transactionRunner {
                messageDao.insertIfAbsent(messageEntity)
                outboxDao.insert(outboxEntity)
                conversationDao.updateLastMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    preview = text.take(100),
                    time = now
                )
                conversationDao.unarchive(conversationId)
                conversationDao.updateManuallyUnread(conversationId, false)
            }
        }

        // 4. Notify TorXAgent to drive transport
        val deliveryItem = DeliveryItem(
            deliveryId = deliveryId,
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connection.connectionId,
            queueAddress = connection.sendQueueId,
            ciphertext = opaqueCiphertext ?: throw IllegalStateException("Ciphertext not generated"),
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
     * Mark all incoming messages in a conversation as read locally and dispatch batch READ_RECEIPT to peer.
     */
    suspend fun markConversationRead(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String
    ) {
        val now = System.currentTimeMillis()

        // 1. Mark incoming messages in local Room database as READ
        messageDao.markAllIncomingRead(conversationId, DeliveryStatus.READ.name, now)
        conversationDao.updateUnreadCount(conversationId, 0)
        conversationDao.updateManuallyUnread(conversationId, false)
        notificationManager?.cancelForConversation(conversationId)

        val latestUnread = messageDao.getLatestUnreadIncoming(conversationId) ?: return

        // 2. Dispatch batch READ_RECEIPT up to latest incoming message
        sendReadReceipt(
            conversationId = conversationId,
            relationshipId = relationshipId,
            upToMessageId = latestUnread.logicalMessageId,
            localIdentityId = localIdentityId,
            recipientId = recipientId
        )
    }

    /**
     * Mark all incoming messages in a conversation as read locally, clear unread count,
     * reset manuallyUnread flag, and cancel notifications.
     */
    suspend fun markConversationRead(conversationId: String) {
        val now = System.currentTimeMillis()
        messageDao.markAllIncomingRead(conversationId, DeliveryStatus.READ.name, now)
        conversationDao.updateUnreadCount(conversationId, 0)
        conversationDao.updateManuallyUnread(conversationId, false)
        notificationManager?.cancelForConversation(conversationId)
    }

    /**
     * Send an explicit encrypted batch READ_RECEIPT for messages up to upToMessageId.
     * Note: Control packet does NOT trigger an ACK back.
     */
    suspend fun sendReadReceipt(
        conversationId: String,
        relationshipId: String,
        upToMessageId: String,
        localIdentityId: String,
        recipientId: String
    ) {
        try {
            val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return
            val now = System.currentTimeMillis()
            val receipt = com.torxone.app.protocol.ReadReceipt(
                conversationId = conversationId,
                upToMessageId = upToMessageId,
                readAt = now
            )

            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localIdentityId,
                recipientBinding = recipientId,
                messageType = MessageType.READ_RECEIPT,
                timestamp = now,
                payload = receipt.toByteArray()
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encrypted = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
            val ciphertext = encrypted.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = com.torxone.app.agent.DeliveryPriority.HIGH
            )

            Log.i(TAG, "[READ RECEIPT] Enqueueing READ up to ${upToMessageId.take(8)} for conv=${conversationId.take(8)}")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send read receipt: ${e.message}")
        }
    }

    /**
     * Observe messages for a conversation.
     */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.observeByConversation(conversationId)
    }

    /**
     * Observe active (unarchived) conversations ordered by pinned and recent activity.
     */
    fun observeConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.observeActive()
    }

    /**
     * Observe archived conversations.
     */
    fun observeArchivedConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.observeArchived()
    }

    /**
     * Observe count of archived conversations.
     */
    fun observeArchivedCount(): Flow<Int> {
        return conversationDao.observeArchivedCount()
    }

    /**
     * Search conversations by title and message content.
     */
    fun searchConversations(query: String): Flow<List<ConversationEntity>> {
        return if (query.isBlank()) {
            conversationDao.observeActive()
        } else {
            conversationDao.searchConversations(query.trim())
        }
    }

    /**
     * Pin or unpin a conversation.
     */
    suspend fun setChatPinned(conversationId: String, isPinned: Boolean) {
        val pinnedAt = if (isPinned) System.currentTimeMillis() else null
        conversationDao.setPinned(conversationId, isPinned, pinnedAt)
        Log.i(TAG, "[PIN] Conversation $conversationId pinned=$isPinned")
    }

    /**
     * Archive or unarchive a conversation.
     */
    suspend fun setChatArchived(conversationId: String, isArchived: Boolean) {
        val archivedAt = if (isArchived) System.currentTimeMillis() else null
        conversationDao.setArchived(conversationId, isArchived, archivedAt)
        Log.i(TAG, "[ARCHIVE] Conversation $conversationId archived=$isArchived")
    }

    /**
     * Mute or unmute notifications for a conversation.
     */
    suspend fun setChatMuted(conversationId: String, mutedUntil: Long?) {
        conversationDao.setMutedUntil(conversationId, mutedUntil)
        Log.i(TAG, "[MUTE] Conversation $conversationId mutedUntil=$mutedUntil")
    }

    /**
     * Mark a conversation as manually unread or read.
     * Note: Purely local UI state, never emits any protocol envelope.
     */
    suspend fun markChatUnread(conversationId: String, unread: Boolean = true) {
        conversationDao.updateManuallyUnread(conversationId, unread)
        Log.i(TAG, "[MANUAL UNREAD] Conversation $conversationId unread=$unread")
    }

    /**
     * Delete chat and all local message history.
     *
     * Invariants:
     * - Strictly LOCAL: Never sends any protocol packet to peer or transport.
     * - Preserves Contact, PairRelationship, Session, Connection.
     * - Deletes messages, reactions, local message state, and conversation record.
     * - Cancels active notifications for this conversation.
     */
    suspend fun deleteChatLocally(conversationId: String): Boolean {
        transactionRunner {
            messageDao.deleteByConversation(conversationId)
            reactionDao?.deleteByConversation(conversationId)
            localMessageStateDao?.deleteByConversation(conversationId)
            conversationDao.deleteById(conversationId)
        }
        notificationManager?.cancelForConversation(conversationId)
        Log.i(TAG, "[DELETE CHAT] Conversation $conversationId deleted locally")
        return true
    }

    /**
     * Send or remove an emoji reaction on a target message.
     */
    suspend fun sendReaction(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        targetMessageId: String,
        emoji: String,
        operation: ReactionOperation
    ) {
        val now = System.currentTimeMillis()

        // 1. Update Room DB locally
        when (operation) {
            ReactionOperation.ADD -> {
                reactionDao?.insertOrUpdate(
                    ReactionEntity(
                        messageId = targetMessageId,
                        conversationId = conversationId,
                        senderId = localIdentityId,
                        emoji = emoji,
                        createdAt = now
                    )
                )
            }
            ReactionOperation.REMOVE -> {
                reactionDao?.remove(
                    messageId = targetMessageId,
                    senderId = localIdentityId,
                    emoji = emoji
                )
            }
        }

        // 2. Build and transmit secure protocol event
        try {
            val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return
            val payload = MessageReaction(
                targetMessageId = targetMessageId,
                emoji = emoji,
                operation = operation
            ).toByteArray()

            val sendSeq = connectionManager.incrementSendSequence(relationshipId)
            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localIdentityId,
                recipientBinding = recipientId,
                messageType = MessageType.REACTION,
                timestamp = now,
                payload = payload,
                directionSequence = sendSeq
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encrypted = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
            val ciphertext = encrypted.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = com.torxone.app.agent.DeliveryPriority.NORMAL,
                expectsAck = false
            )

            Log.i(TAG, "[REACTION] Enqueueing reaction $emoji on msg=${targetMessageId.take(8)}")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reaction: ${e.message}")
        }
    }

    /**
     * Edit a previously sent text message.
     * Rules: only original sender may edit, new version > stored version, deleted messages cannot be edited.
     */
    suspend fun editMessage(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        targetMessageId: String,
        newText: String
    ): Boolean {
        require(newText.isNotBlank()) { "Edited text cannot be blank" }

        val targetMsg = messageDao.getById(targetMessageId) ?: return false
        if (targetMsg.senderId != localIdentityId) {
            Log.w(TAG, "Cannot edit message: not original author")
            return false
        }
        if (targetMsg.deletedAt != null) {
            Log.w(TAG, "Cannot edit deleted message")
            return false
        }

        val newVersion = targetMsg.editVersion + 1
        val now = System.currentTimeMillis()

        // 1. Update Room DB locally
        messageDao.updateBodyAndEdit(
            messageId = targetMessageId,
            newBody = newText,
            editVersion = newVersion,
            editedAt = now
        )
        conversationDao.updateLastMessagePreviewIfLatest(
            messageId = targetMessageId,
            preview = newText.take(100)
        )
        notificationManager?.onMessageEdited(
            conversationId = conversationId,
            messageId = targetMessageId,
            newText = newText
        )

        // 2. Build and transmit secure protocol event
        try {
            val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return true
            val payload = MessageEdit(
                targetMessageId = targetMessageId,
                newText = newText,
                editVersion = newVersion,
                editedAt = now
            ).toByteArray()

            val sendSeq = connectionManager.incrementSendSequence(relationshipId)
            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localIdentityId,
                recipientBinding = recipientId,
                messageType = MessageType.EDIT,
                timestamp = now,
                payload = payload,
                directionSequence = sendSeq
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encrypted = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
            val ciphertext = encrypted.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = com.torxone.app.agent.DeliveryPriority.NORMAL,
                expectsAck = false
            )

            Log.i(TAG, "[EDIT] Enqueueing edit for msg=${targetMessageId.take(8)} version=$newVersion")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send edit: ${e.message}")
        }
        return true
    }

    /**
     * Delete a previously sent message, turning it into a tombstone ("Delete for everyone").
     */
    suspend fun deleteMessage(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        targetMessageId: String
    ): Boolean {
        val targetMsg = messageDao.getById(targetMessageId) ?: return false
        if (targetMsg.senderId != localIdentityId) {
            Log.w(TAG, "Cannot delete message: not original author")
            return false
        }
        if (targetMsg.deletedAt != null) {
            return true
        }

        val now = System.currentTimeMillis()

        // 1. Update Room DB locally: body = null, deletedAt = now
        messageDao.markDeleted(
            messageId = targetMessageId,
            deletedAt = now
        )
        conversationDao.updateLastMessagePreviewIfLatest(
            messageId = targetMessageId,
            preview = "This message was deleted"
        )
        notificationManager?.onMessageTombstoned(
            conversationId = conversationId,
            messageId = targetMessageId
        )

        // 2. Build and transmit secure protocol event
        try {
            val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return true
            val payload = MessageDelete(
                targetMessageId = targetMessageId,
                deletedAt = now
            ).toByteArray()

            val sendSeq = connectionManager.incrementSendSequence(relationshipId)
            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localIdentityId,
                recipientBinding = recipientId,
                messageType = MessageType.DELETE,
                timestamp = now,
                payload = payload,
                directionSequence = sendSeq
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encrypted = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
            val ciphertext = encrypted.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = com.torxone.app.agent.DeliveryPriority.HIGH,
                expectsAck = false
            )

            Log.i(TAG, "[DELETE] Enqueueing delete for msg=${targetMessageId.take(8)}")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delete: ${e.message}")
        }
        return true
    }

    /**
     * Explicit alias for deleteMessage ("Delete for everyone").
     */
    suspend fun deleteForEveryone(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        targetMessageId: String
    ): Boolean {
        return deleteMessage(conversationId, relationshipId, localIdentityId, recipientId, targetMessageId)
    }

    /**
     * Delete a message locally only ("Delete for me").
     *
     * Invariants:
     * - Strictly local: Never sends any protocol packet to peer or transport.
     * - Works on both outgoing and incoming messages.
     * - Records hiddenLocally = true in local_message_state table.
     * - Recalculates conversation.last_message_preview to the latest locally visible message.
     * - Updates active system notification if unread.
     */
    suspend fun deleteForMe(
        conversationId: String,
        messageId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        localMessageStateDao?.upsert(
            LocalMessageStateEntity(
                messageId = messageId,
                conversationId = conversationId,
                hiddenLocally = true,
                hiddenAt = now
            )
        )

        // Recalculate conversation last message preview
        recalculateConversationPreviewAfterLocalDelete(conversationId)

        // Notify notification manager to update active notifications
        notificationManager?.onMessageDeletedLocally(conversationId, messageId)

        Log.i(TAG, "[DELETE FOR ME] Message $messageId hidden locally for conversation $conversationId")
        return true
    }

    private suspend fun recalculateConversationPreviewAfterLocalDelete(conversationId: String) {
        val allMessages = messageDao.getMessagesForConversationDesc(conversationId)
        val hiddenIds = localMessageStateDao?.getHiddenMessageIds(conversationId)?.toSet() ?: emptySet()
        val latestVisible = allMessages.firstOrNull { !hiddenIds.contains(it.logicalMessageId) }

        if (latestVisible != null) {
            val preview = if (latestVisible.deletedAt != null) {
                "This message was deleted"
            } else {
                latestVisible.body?.take(100)
            }
            conversationDao.updateLastMessage(
                conversationId = conversationId,
                messageId = latestVisible.logicalMessageId,
                preview = preview,
                time = latestVisible.createdAt
            )
        } else {
            conversationDao.updateLastMessage(
                conversationId = conversationId,
                messageId = "",
                preview = null,
                time = 0L
            )
        }
    }

    /**
     * Observe IDs of messages that have been deleted locally ("Delete for me").
     */
    fun observeHiddenMessageIds(conversationId: String): Flow<List<String>> {
        return localMessageStateDao?.observeHiddenMessageIds(conversationId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    /**
     * Observe reactions for a conversation.
     */
    fun observeReactions(conversationId: String): Flow<List<ReactionEntity>> {
        return reactionDao?.observeForConversation(conversationId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
}
