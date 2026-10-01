package com.torxone.app.privacy

import androidx.room.*
import com.torxone.app.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityPolicyDao {
    @Query("SELECT * FROM conversation_security_policy WHERE conversationId = :id")
    suspend fun policy(id: String): ConversationSecurityPolicy?
    @Query("SELECT * FROM conversation_security_policy WHERE conversationId = :id")
    fun observePolicy(id: String): Flow<ConversationSecurityPolicy?>
    @Upsert suspend fun save(policy: ConversationSecurityPolicy)
    @Query("""SELECT * FROM messages WHERE expires_at IS NOT NULL AND expires_at <= :now
        AND (deleted_at IS NULL OR body IS NOT NULL OR reply_to_message_id IS NOT NULL
        OR EXISTS (SELECT 1 FROM media WHERE message_id = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM reactions WHERE message_id = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM starred_messages WHERE messageId = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM message_links WHERE messageId = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM conversations WHERE last_message_id = messages.logical_message_id AND last_message_preview IS NOT NULL)
        OR EXISTS (SELECT 1 FROM messages r WHERE r.reply_to_message_id = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM conversation_drafts WHERE replyToMessageId = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM scheduled_messages WHERE replyToMessageId = messages.logical_message_id)
        OR EXISTS (SELECT 1 FROM group_message_deliveries WHERE logical_message_id = messages.logical_message_id
            AND outbox_delivery_id IS NULL AND status IN ('PENDING','FAILED')))
        ORDER BY expires_at LIMIT 128""")
    suspend fun due(now: Long): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun tombstone(value: ExpiredMediaTombstone)
    @Query("SELECT * FROM expired_media WHERE mediaId = :mediaId AND relationshipId = :relationshipId")
    suspend fun expiredMedia(mediaId: String, relationshipId: String): ExpiredMediaTombstone?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun queueFile(value: PrivacyFileCleanup)
    @Query("SELECT * FROM privacy_file_cleanup") suspend fun pendingFiles(): List<PrivacyFileCleanup>
    @Query("DELETE FROM privacy_file_cleanup WHERE path = :path") suspend fun fileDeleted(path: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun trackFile(value: PendingMediaFile)
    @Query("SELECT * FROM pending_media_files") suspend fun pendingMediaFiles(): List<PendingMediaFile>
    @Query("SELECT * FROM pending_media_files WHERE mediaId = :mediaId") suspend fun mediaFiles(mediaId: String): List<PendingMediaFile>
    @Query("DELETE FROM pending_media_files WHERE path = :path") suspend fun untrackFile(path: String)
    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE local_path = :path) OR EXISTS(SELECT 1 FROM media_transfers WHERE temp_encrypted_path = :path)")
    suspend fun referencedFile(path: String): Boolean
    @Query("UPDATE messages SET body = NULL, reply_to_message_id = NULL, deleted_at = COALESCE(deleted_at, :now) WHERE logical_message_id = :id")
    suspend fun tombstoneMessage(id: String, now: Long)
    @Query("DELETE FROM reactions WHERE message_id = :id") suspend fun clearReactions(id: String)
    @Query("DELETE FROM starred_messages WHERE messageId = :id") suspend fun clearStars(id: String)
    @Query("DELETE FROM message_links WHERE messageId = :id") suspend fun clearLinks(id: String)
    @Query("UPDATE conversations SET last_message_preview = NULL WHERE last_message_id = :id")
    suspend fun clearPreview(id: String)
    @Query("UPDATE messages SET reply_to_message_id = NULL WHERE reply_to_message_id = :id")
    suspend fun clearReplyReferences(id: String)
    @Query("UPDATE conversation_drafts SET replyToMessageId = NULL WHERE replyToMessageId = :id")
    suspend fun clearDraftReplyReferences(id: String)
    @Query("UPDATE group_message_deliveries SET status = 'EXPIRED' WHERE logical_message_id = :id AND outbox_delivery_id IS NULL AND status IN ('PENDING','FAILED')")
    suspend fun expireUnallocatedGroupDeliveries(id: String)
    @Query("UPDATE scheduled_messages SET replyToMessageId = NULL WHERE replyToMessageId = :id")
    suspend fun clearScheduledReplyReferences(id: String)
}
