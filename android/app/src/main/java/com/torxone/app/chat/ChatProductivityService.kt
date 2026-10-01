package com.torxone.app.chat

import androidx.room.withTransaction
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.*
import com.torxone.app.groups.GroupService
import com.torxone.app.media.MediaService
import com.torxone.app.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class SearchFilter { ALL, MEDIA, LINKS, DOCUMENTS, VOICE }

object SearchQuery {
    /** Quote every token: user punctuation cannot become an FTS operator. */
    fun fts(raw: String): String? = Regex("[\\p{L}\\p{N}]+").findAll(raw.take(256)).map { it.value }
        .take(16).joinToString(" AND ") { "\"$it*\"" }.ifBlank { null }
}

object ForwardingPlan {
    fun messageId(operation: String, source: String, destination: String): String =
        UUID.nameUUIDFromBytes("forward:$operation:$source:$destination".toByteArray()).toString()
    fun text(body: String): String = "[Forwarded]\n$body"
}

/** Local data never enters envelopes. Forwarding creates new messages via existing feature services. */
class ChatProductivityService(private val db: TorXDatabase, private val chat: ChatService,
    private val groups: GroupService, private val media: MediaService) {
    private val forwardMutex = Mutex()
    val dao get() = db.featureDao()
    suspend fun saveAppearance(value: ConversationAppearanceEntity) {
        requireNotNull(db.conversationDao().getById(value.conversationId)) { "Chat unavailable" }
        AppearanceOptions.validate(value)
        dao.saveAppearance(value)
    }
    suspend fun saveNickname(contactId: String, nickname: String) {
        requireNotNull(db.contactDao().getById(contactId)) { "Contact unavailable" }
        val normalized = nickname.trim()
        require(normalized.length <= 60 && '\n' !in normalized) { "Nickname must be at most 60 characters" }
        if (normalized.isEmpty()) dao.deleteAlias(contactId) else dao.saveAlias(ContactAliasEntity(contactId, normalized))
    }
    suspend fun saveDraft(id: String, text: String, reply: String?) {
        require(text.toByteArray().size <= com.torxone.app.protocol.ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES)
        if (text.isBlank() && reply == null) dao.deleteDraft(id)
        else dao.saveDraft(ConversationDraftEntity(id, text, reply))
    }
    suspend fun setStarred(ids: Set<String>, enabled: Boolean) = db.withTransaction {
        for (id in ids) {
            val message = db.messageDao().getById(id) ?: continue
            if (enabled && message.deletedAt == null && !com.torxone.app.privacy.DisappearingPolicy.expired(message.expiresAt, System.currentTimeMillis())) dao.star(StarredMessageEntity(id, message.conversationId, System.currentTimeMillis()))
            else dao.unstar(id)
        }
    }
    fun search(raw: String, conversation: String?, filter: SearchFilter = SearchFilter.ALL) =
        SearchQuery.fts(raw)?.let { dao.search(it, conversation, filter.name) } ?: flowOf(emptyList())

    suspend fun forward(ids: List<String>, destination: String, localIdentity: String, operationId: String) = forwardMutex.withLock {
        require(ids.size in 1..100)
        require(operationId.isNotBlank() && operationId.length <= 128)
        val conversation = requireNotNull(db.conversationDao().getById(destination)) { "Destination chat unavailable" }
        val contact = if (conversation.type == ConversationType.DIRECT)
            requireNotNull(db.contactDao().getByConversationId(destination)) { "Destination contact unavailable" } else null
        for (id in ids) {
            val newId = ForwardingPlan.messageId(operationId, id, destination)
            // Message + ratchet + durable delivery were already committed on a previous attempt.
            if (db.messageDao().getById(newId) != null) continue
            val source = requireNotNull(db.messageDao().getById(id)) { "Message unavailable" }
            require(source.deletedAt == null) { "Deleted messages cannot be forwarded" }
            require(!com.torxone.app.privacy.DisappearingPolicy.expired(source.expiresAt, System.currentTimeMillis())) { "Expired messages cannot be forwarded" }
            val hidden = db.localMessageStateDao().getByMessageId(id)
            require(hidden?.hiddenLocally != true) { "Hidden messages cannot be forwarded" }
            val attachment = db.mediaDao().getByMessageId(id)
            if (attachment != null) {
                val file = attachment.localPath?.let(::File)?.takeIf { it.isFile }
                    ?: error("Download this attachment before forwarding")
                val bytes = withContext(Dispatchers.IO) {
                    require(file.length() in 1..32L * 1024 * 1024) { "Attachment is too large to forward" }
                    file.readBytes()
                }
                val type = MediaType.valueOf(attachment.mediaType)
                if (contact != null) media.sendMedia(destination, contact.relationshipId, localIdentity, contact.remoteIdentityId,
                    type, attachment.fileName, attachment.mimeType, bytes, attachment.durationMs,
                    attachment.thumbnailData, attachment.waveformData, logicalMessageId = newId)
                else {
                    val group = requireNotNull(db.groupDao().getById(destination))
                    val self = db.groupMemberDao().getMember(destination, localIdentity)
                    require(self?.state == GroupMemberState.ACTIVE.name) { "You are no longer a group member" }
                    val recipients = db.groupMemberDao().getActiveMembers(destination)
                        .filter { it.memberIdentityId != localIdentity }
                        .map { MediaService.GroupMediaRecipient(it.memberIdentityId, it.relationshipId) }
                    media.sendGroupMedia(destination, group.epoch.toLong(), localIdentity, recipients, type,
                        attachment.fileName, attachment.mimeType, bytes, durationMs = attachment.durationMs,
                        thumbnailBytes = attachment.thumbnailData, waveformData = attachment.waveformData, logicalMessageId = newId)
                }
            } else {
                // A readable fallback also works on older versions; no original sender is disclosed.
                val text = ForwardingPlan.text(source.body ?: error("Message cannot be forwarded"))
                if (contact != null) chat.sendTextMessage(destination, contact.relationshipId, localIdentity, contact.remoteIdentityId, text, logicalMessageId = newId, clearDraft = false)
                else groups.sendGroupText(destination, text, logicalMessageId = newId, clearDraft = false)
            }
        }
        Unit
    }
}
