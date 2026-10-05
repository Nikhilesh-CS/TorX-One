package com.torxone.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.MediaEntity
import com.torxone.app.data.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

// This DAO has no writes: opening/paging a timeline cannot mutate receipts or outbox state.
private const val VISIBLE = "conversation_id = :conversationId AND NOT EXISTS (SELECT 1 FROM local_message_state l WHERE l.message_id = messages.logical_message_id AND l.hidden_locally = 1)"
private const val WINDOW = "SELECT logical_message_id FROM messages WHERE " + VISIBLE +
    " AND (:upperTime IS NULL OR created_at < :upperTime OR (created_at = :upperTime AND logical_message_id <= :upperId)) ORDER BY created_at DESC, logical_message_id DESC LIMIT :limit"
private const val REFERENCES = "SELECT reply_to_message_id FROM messages WHERE logical_message_id IN (" + WINDOW + ") AND reply_to_message_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM local_message_state h WHERE h.message_id = messages.reply_to_message_id AND h.hidden_locally = 1)"
private const val MEDIA_METADATA = "media_id, message_id, conversation_id, media_type, mime_type, file_name, file_size, local_path, encrypted_sha256, X'' AS media_key, NULL AS thumbnail_data, duration_ms, waveform_data, width, height, status, transfer_progress, created_at"

data class TimelineCursor(val logical_message_id: String, val created_at: Long)
data class UnreadTimelineEntry(val firstMessageId: String?, val count: Int)

@Dao
interface ChatTimelineDao {
    @Query("SELECT * FROM messages WHERE logical_message_id IN (" + WINDOW + ") ORDER BY created_at ASC, logical_message_id ASC")
    fun observeWindow(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND logical_message_id IN (" + REFERENCES + ")")
    fun observeReferences(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM reactions WHERE conversation_id = :conversationId AND message_id IN (" + WINDOW + ")")
    fun observeReactions(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<ReactionEntity>>

    @Query("SELECT " + MEDIA_METADATA + " FROM media WHERE conversation_id = :conversationId AND (message_id IN (" + WINDOW + ") OR message_id IN (" + REFERENCES + "))")
    fun observeMedia(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT thumbnail_data FROM media WHERE media_id = :mediaId")
    fun observeThumbnail(mediaId: String): Flow<ByteArray?>

    @Query("SELECT logical_message_id, created_at FROM messages WHERE conversation_id = :conversationId AND logical_message_id = :messageId")
    suspend fun cursor(conversationId: String, messageId: String): TimelineCursor?

    @Query("SELECT logical_message_id, created_at FROM messages WHERE " + VISIBLE + " AND (created_at > :time OR (created_at = :time AND logical_message_id >= :id)) ORDER BY created_at ASC, logical_message_id ASC LIMIT 1 OFFSET :offset")
    suspend fun cursorAfter(conversationId: String, time: Long, id: String, offset: Int): TimelineCursor?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE " + VISIBLE + " AND (created_at < :time OR (created_at = :time AND logical_message_id < :id)))")
    suspend fun hasEarlier(conversationId: String, time: Long, id: String): Boolean

    @Query("SELECT COUNT(*) FROM messages WHERE " + VISIBLE + " AND direction = 'INCOMING' AND (:time IS NOT NULL AND (created_at > :time OR (created_at = :time AND logical_message_id > :id)))")
    fun observeNewerIncoming(conversationId: String, time: Long?, id: String?): Flow<Int>

    @Query("SELECT logical_message_id FROM messages WHERE " + VISIBLE + " AND direction = 'INCOMING' AND status != 'READ' ORDER BY created_at ASC, logical_message_id ASC LIMIT 1")
    suspend fun firstUnreadVisible(conversationId: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE " + VISIBLE + " AND direction = 'INCOMING' AND status != 'READ'")
    suspend fun countUnreadVisible(conversationId: String): Int

    @Transaction
    suspend fun unreadEntry(conversationId: String): UnreadTimelineEntry =
        UnreadTimelineEntry(firstUnreadVisible(conversationId), countUnreadVisible(conversationId))
}
