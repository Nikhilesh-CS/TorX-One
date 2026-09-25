package com.torxone.app.data.dao

import androidx.room.*
import com.torxone.app.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY is_pinned DESC, pinned_at DESC, last_message_time DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY is_pinned DESC, pinned_at DESC, last_message_time DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_archived = 1 ORDER BY archived_at DESC, last_message_time DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT COUNT(*) FROM conversations WHERE is_archived = 1")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread_count = :count WHERE conversationId = :id")
    suspend fun updateUnreadCount(id: String, count: Int)

    @Query("UPDATE conversations SET manually_unread = :manuallyUnread WHERE conversationId = :id")
    suspend fun updateManuallyUnread(id: String, manuallyUnread: Boolean)

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

    @Query("UPDATE conversations SET last_message_preview = :preview WHERE last_message_id = :messageId")
    suspend fun updateLastMessagePreviewIfLatest(messageId: String, preview: String?)

    @Query("UPDATE conversations SET is_pinned = :isPinned, pinned_at = :pinnedAt WHERE conversationId = :id")
    suspend fun setPinned(id: String, isPinned: Boolean, pinnedAt: Long?)

    @Query("UPDATE conversations SET is_archived = :isArchived, archived_at = :archivedAt WHERE conversationId = :id")
    suspend fun setArchived(id: String, isArchived: Boolean, archivedAt: Long?)

    @Query("UPDATE conversations SET is_archived = 0, archived_at = null WHERE conversationId = :id")
    suspend fun unarchive(id: String)

    @Query("UPDATE conversations SET muted_until = :mutedUntil WHERE conversationId = :id")
    suspend fun setMutedUntil(id: String, mutedUntil: Long?)

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun deleteById(id: String)

    @Query("""
        SELECT DISTINCT c.* FROM conversations c
        LEFT JOIN messages m ON c.conversationId = m.conversation_id
        WHERE c.title LIKE '%' || :query || '%' 
           OR c.last_message_preview LIKE '%' || :query || '%'
           OR (m.body LIKE '%' || :query || '%' AND m.deleted_at IS NULL)
        ORDER BY c.is_pinned DESC, c.pinned_at DESC, c.last_message_time DESC
    """)
    fun searchConversations(query: String): Flow<List<ConversationEntity>>
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

    @Query("UPDATE messages SET status = :status, read_at = :readAt WHERE conversation_id = :conversationId AND direction = 'OUTGOING' AND status != 'READ' AND created_at <= :upToCreatedAt")
    suspend fun markOutgoingReadUpTo(conversationId: String, upToCreatedAt: Long, status: String, readAt: Long)

    @Query("UPDATE messages SET status = :status, read_at = :readAt WHERE conversation_id = :conversationId AND direction = 'INCOMING' AND status != 'READ'")
    suspend fun markAllIncomingRead(conversationId: String, status: String, readAt: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND direction = 'INCOMING' AND status != 'READ' ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestUnreadIncoming(conversationId: String): MessageEntity?

    @Query("UPDATE messages SET body = :newBody, edit_version = :editVersion, edited_at = :editedAt WHERE logical_message_id = :messageId")
    suspend fun updateBodyAndEdit(messageId: String, newBody: String, editVersion: Int, editedAt: Long)

    @Query("UPDATE messages SET body = null, deleted_at = :deletedAt WHERE logical_message_id = :messageId")
    suspend fun markDeleted(messageId: String, deletedAt: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
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

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE message_id = :messageId")
    suspend fun getForMessage(messageId: String): List<ReactionEntity>

    @Query("SELECT * FROM reactions WHERE conversation_id = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE message_id = :messageId AND sender_id = :senderId AND emoji = :emoji")
    suspend fun remove(messageId: String, senderId: String, emoji: String)

    @Query("DELETE FROM reactions WHERE message_id = :messageId AND sender_id = :senderId")
    suspend fun removeAllFromSender(messageId: String, senderId: String)

    @Query("DELETE FROM reactions WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface LocalMessageStateDao {
    @Query("SELECT * FROM local_message_state WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): LocalMessageStateEntity?

    @Query("SELECT message_id FROM local_message_state WHERE conversation_id = :conversationId AND hidden_locally = 1")
    fun observeHiddenMessageIds(conversationId: String): Flow<List<String>>

    @Query("SELECT message_id FROM local_message_state WHERE conversation_id = :conversationId AND hidden_locally = 1")
    suspend fun getHiddenMessageIds(conversationId: String): List<String>

    @Query("SELECT message_id FROM local_message_state WHERE hidden_locally = 1")
    fun observeAllHiddenMessageIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalMessageStateEntity)

    @Query("DELETE FROM local_message_state WHERE message_id = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM local_message_state WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE media_id = :mediaId")
    suspend fun getById(mediaId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE message_id = :messageId")
    fun observeByMessageId(messageId: String): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    fun observeForConversation(conversationId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    suspend fun getMediaForConversation(conversationId: String): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaEntity)

    @Query("UPDATE media SET status = :status, transfer_progress = :progress WHERE media_id = :mediaId")
    suspend fun updateStatus(mediaId: String, status: String, progress: Float)

    @Query("UPDATE media SET local_path = :localPath, status = :status, transfer_progress = 1.0 WHERE media_id = :mediaId")
    suspend fun updateLocalPathAndStatus(mediaId: String, localPath: String, status: String)

    @Query("DELETE FROM media WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)

    @Query("DELETE FROM media WHERE message_id = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("DELETE FROM media WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface MediaTransferDao {
    @Query("SELECT * FROM media_transfers WHERE transfer_id = :transferId")
    suspend fun getByTransferId(transferId: String): MediaTransferEntity?

    @Query("SELECT * FROM media_transfers WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): MediaTransferEntity?

    @Query("SELECT * FROM media_transfers WHERE media_id = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<MediaTransferEntity?>

    @Query("SELECT * FROM media_transfers WHERE status = 'ACTIVE' OR status = 'QUEUED' OR status = 'PAUSED'")
    suspend fun getPendingTransfers(): List<MediaTransferEntity>

    @Query("SELECT media_id FROM media_transfers WHERE status = 'ACTIVE' OR status = 'QUEUED' OR status = 'PAUSED'")
    suspend fun getAllActiveMediaIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: MediaTransferEntity)

    @Query("UPDATE media_transfers SET completed_chunks = :completedChunks, chunk_bitmask = :chunkBitmask, bytes_transferred = :bytesTransferred, status = :status, updated_at = :updatedAt WHERE transfer_id = :transferId")
    suspend fun updateProgress(
        transferId: String,
        completedChunks: Int,
        chunkBitmask: String,
        bytesTransferred: Long,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE media_transfers SET status = :status, updated_at = :updatedAt WHERE transfer_id = :transferId")
    suspend fun updateStatus(
        transferId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM media_transfers WHERE transfer_id = :transferId")
    suspend fun deleteByTransferId(transferId: String)

    @Query("DELETE FROM media_transfers WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)
}



