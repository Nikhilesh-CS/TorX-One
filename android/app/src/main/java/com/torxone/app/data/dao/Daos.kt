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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE status IN ('QUEUED', 'RETRY_WAIT', 'TRANSMITTING') AND next_attempt_at <= :now ORDER BY created_at ASC")
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
