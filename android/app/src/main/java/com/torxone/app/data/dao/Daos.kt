package com.torxone.app.data.dao

import androidx.room.*
import com.torxone.app.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY last_message_time DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread_count = :count WHERE conversationId = :id")
    suspend fun updateUnreadCount(id: String, count: Int)

    @Query("""
        UPDATE conversations SET 
            last_message_id = :messageId,
            last_message_preview = :preview,
            last_message_time = :time
        WHERE conversationId = :conversationId
    """)
    suspend fun updateLastMessage(
        conversationId: String,
        messageId: String,
        preview: String?,
        time: Long
    )
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE logical_message_id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE logical_message_id = :messageId")
    suspend fun exists(messageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE logical_message_id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status, delivered_at = :deliveredAt WHERE logical_message_id = :messageId")
    suspend fun markDelivered(messageId: String, status: String, deliveredAt: Long)

    @Query("UPDATE messages SET status = :status, read_at = :readAt WHERE logical_message_id = :messageId")
    suspend fun markRead(messageId: String, status: String, readAt: Long)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY display_name ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE contactId = :id")
    suspend fun getById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE relationship_id = :relationshipId")
    suspend fun getByRelationshipId(relationshipId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE conversation_id = :conversationId")
    suspend fun getByConversationId(conversationId: String): ContactEntity?

    @Query("SELECT * FROM contacts")
    suspend fun getAll(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE status IN ('QUEUED', 'RETRY_WAIT', 'TRANSMITTING', 'TRANSPORT_ACCEPTED') AND next_attempt_at <= :now ORDER BY priority DESC, created_at ASC")
    suspend fun getPending(now: Long = System.currentTimeMillis()): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxEntity)

    @Query("UPDATE outbox SET status = :status, updated_at = :now WHERE deliveryId = :deliveryId")
    suspend fun updateStatus(deliveryId: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE outbox SET attempt_count = :attemptCount, next_attempt_at = :nextAttemptAt, status = 'RETRY_WAIT', updated_at = :now WHERE deliveryId = :deliveryId")
    suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM outbox WHERE logical_message_id = :logicalMessageId")
    suspend fun removeByMessageId(logicalMessageId: String)
}

@Dao
interface ProcessedEnvelopeDao {
    @Query("SELECT COUNT(*) FROM processed_envelopes WHERE envelope_id = :envelopeId")
    suspend fun isProcessed(envelopeId: String): Boolean

    @Query("SELECT COUNT(*) FROM processed_envelopes WHERE logical_message_id = :logicalMessageId")
    suspend fun isMessageProcessed(logicalMessageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProcessedEnvelopeEntity)

    @Query("DELETE FROM processed_envelopes WHERE processed_at < :before")
    suspend fun pruneOlderThan(before: Long)
}

@Dao
interface PairRelationshipDao {
    @Query("SELECT * FROM pair_relationships WHERE relationship_id = :id")
    suspend fun getById(id: String): PairRelationshipEntity?

    @Query("SELECT * FROM pair_relationships WHERE contact_id = :contactId")
    suspend fun getByContactId(contactId: String): PairRelationshipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: PairRelationshipEntity)

    @Query("UPDATE pair_relationships SET state = :state WHERE relationship_id = :id")
    suspend fun updateState(id: String, state: String)
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections WHERE relationship_id = :relationshipId")
    suspend fun getByRelationshipId(relationshipId: String): ConnectionDbEntity?

    @Query("SELECT * FROM connections WHERE recv_queue_id = :recvQueueId")
    suspend fun getByRecvQueue(recvQueueId: String): ConnectionDbEntity?

    @Query("SELECT * FROM connections WHERE send_queue_id = :sendQueueId")
    suspend fun getBySendQueue(sendQueueId: String): ConnectionDbEntity?

    @Query("SELECT * FROM connections WHERE state = 'ACTIVE'")
    suspend fun getAllActive(): List<ConnectionDbEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: ConnectionDbEntity)

    @Query("UPDATE connections SET state = :state WHERE connection_id = :connectionId")
    suspend fun updateState(connectionId: String, state: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE relationship_id = :relationshipId")
    suspend fun getByRelationshipId(relationshipId: String): SessionDbEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionDbEntity)

    @Query("DELETE FROM sessions WHERE relationship_id = :relationshipId")
    suspend fun deleteByRelationshipId(relationshipId: String)
}

@Dao
interface SkippedKeyDao {
    @Query("SELECT * FROM skipped_message_keys WHERE session_id = :sessionId")
    suspend fun getKeysForSession(sessionId: String): List<SkippedKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SkippedKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(keys: List<SkippedKeyEntity>)

    @Query("DELETE FROM skipped_message_keys WHERE session_id = :sessionId AND ratchet_public_key_hex = :ratchetPubHex AND counter = :counter")
    suspend fun deleteKey(sessionId: String, ratchetPubHex: String, counter: Int)

    @Query("DELETE FROM skipped_message_keys WHERE session_id = :sessionId")
    suspend fun deleteKeysForSession(sessionId: String)
}

@Dao
interface PendingInviteDao {
    @Query("SELECT * FROM pending_invites WHERE invite_id = :inviteId")
    suspend fun getById(inviteId: String): PendingInviteEntity?

    @Query("SELECT * FROM pending_invites WHERE ephemeral_public_key = :publicKey")
    suspend fun getByPublicKey(publicKey: ByteArray): PendingInviteEntity?

    @Query("SELECT * FROM pending_invites ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatest(): PendingInviteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(invite: PendingInviteEntity)

    @Query("DELETE FROM pending_invites WHERE invite_id = :inviteId")
    suspend fun delete(inviteId: String)
}

