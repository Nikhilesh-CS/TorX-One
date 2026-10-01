package com.torxone.app.data.dao

import androidx.room.*
import com.torxone.app.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FeatureDao {
    @Query("""SELECT COUNT(DISTINCT o.logical_message_id) FROM outbox o JOIN messages m
        ON m.logical_message_id = o.logical_message_id WHERE o.conversation_id = :id
        AND m.direction = 'OUTGOING' AND m.deleted_at IS NULL
        AND o.status IN ('QUEUED','RETRY_WAIT','TRANSMITTING','TRANSPORT_ACCEPTED')""")
    suspend fun pendingMessageCount(id: String): Int
    @Query("""SELECT COUNT(*) FROM outbox WHERE conversation_id = :id
        AND status IN ('QUEUED','RETRY_WAIT','TRANSMITTING','TRANSPORT_ACCEPTED')""")
    suspend fun pendingDeliveryCount(id: String): Int
    @Query("""SELECT COUNT(*) FROM outbox o WHERE o.conversation_id = :id
        AND o.status IN ('QUEUED','RETRY_WAIT','TRANSMITTING','TRANSPORT_ACCEPTED')
        AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.logical_message_id = o.logical_message_id)""")
    suspend fun pendingControlCount(id: String): Int
    @Query("SELECT m.* FROM messages m JOIN pending_link_index p ON p.messageId = m.logical_message_id LIMIT 100")
    suspend fun pendingLinkMessages(): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLink(value: MessageLinkEntity)
    @Query("DELETE FROM message_links WHERE messageId = :id") suspend fun deleteLinks(id: String)
    @Query("DELETE FROM pending_link_index WHERE messageId = :id") suspend fun finishLinkIndex(id: String)
    @Query("SELECT l.* FROM message_links l JOIN messages m ON m.logical_message_id = l.messageId WHERE l.conversationId = :id AND m.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM local_message_state s WHERE s.message_id = l.messageId AND s.hidden_locally = 1) ORDER BY l.createdAt DESC")
    fun observeLinks(id: String): Flow<List<MessageLinkEntity>>
    @Upsert suspend fun saveCapabilities(value: PeerCapabilitiesEntity)
    @Query("SELECT * FROM peer_capabilities WHERE relationshipId = :relationshipId")
    suspend fun capabilities(relationshipId: String): PeerCapabilitiesEntity?
    @Upsert suspend fun saveDraft(value: ConversationDraftEntity)
    @Query("SELECT * FROM conversation_drafts WHERE conversationId = :id") suspend fun draft(id: String): ConversationDraftEntity?
    @Query("SELECT * FROM conversation_drafts") fun observeDrafts(): Flow<List<ConversationDraftEntity>>
    @Query("DELETE FROM conversation_drafts WHERE conversationId = :id") suspend fun deleteDraft(id: String)
    @Upsert suspend fun star(value: StarredMessageEntity)
    @Query("DELETE FROM starred_messages WHERE messageId = :id") suspend fun unstar(id: String)
    @Query("SELECT messageId FROM starred_messages WHERE conversationId = :id") fun observeStars(id: String): Flow<List<String>>
    @Query("SELECT m.* FROM messages m JOIN starred_messages s ON m.logical_message_id = s.messageId WHERE m.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM local_message_state l WHERE l.message_id = m.logical_message_id AND l.hidden_locally = 1) ORDER BY s.starredAt DESC")
    fun observeStarredMessages(): Flow<List<MessageEntity>>
    @Upsert suspend fun saveAlias(value: ContactAliasEntity)
    @Query("DELETE FROM contact_aliases WHERE contactId = :id") suspend fun deleteAlias(id: String)
    @Query("SELECT * FROM contact_aliases") fun observeAliases(): Flow<List<ContactAliasEntity>>
    @Query("SELECT * FROM contact_aliases WHERE contactId = :id") suspend fun alias(id: String): ContactAliasEntity?
    @Upsert suspend fun saveAppearance(value: ConversationAppearanceEntity)
    @Query("SELECT * FROM conversation_appearance WHERE conversationId = :id") fun observeAppearance(id: String): Flow<ConversationAppearanceEntity?>
    @Query("""SELECT m.* FROM message_search JOIN messages m ON m.logical_message_id = message_search.messageId
        WHERE message_search MATCH :query AND (:conversationId IS NULL OR m.conversation_id = :conversationId)
        AND m.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM local_message_state l WHERE l.message_id = m.logical_message_id AND l.hidden_locally = 1)
        AND (:filter = 'ALL'
          OR (:filter = 'MEDIA' AND EXISTS (SELECT 1 FROM media a WHERE a.message_id = m.logical_message_id AND a.media_type IN ('IMAGE','VIDEO')))
          OR (:filter = 'DOCUMENTS' AND EXISTS (SELECT 1 FROM media a WHERE a.message_id = m.logical_message_id AND a.media_type = 'DOCUMENT'))
          OR (:filter = 'VOICE' AND EXISTS (SELECT 1 FROM media a WHERE a.message_id = m.logical_message_id AND a.media_type IN ('VOICE_NOTE','AUDIO')))
          OR (:filter = 'LINKS' AND (instr(lower(COALESCE(m.body,'')), 'https://') > 0 OR instr(lower(COALESCE(m.body,'')), 'http://') > 0)))
        ORDER BY m.created_at DESC LIMIT 200""")
    fun search(query: String, conversationId: String?, filter: String = "ALL"): Flow<List<MessageEntity>>
}

