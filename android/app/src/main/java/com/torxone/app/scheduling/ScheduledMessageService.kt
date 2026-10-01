package com.torxone.app.scheduling

import com.torxone.app.chat.ChatService
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.data.entity.GroupMemberState
import com.torxone.app.groups.GroupService

/** Scheduling owns local intent only. Current membership, relationships and ratchet are loaded when due. */
class ScheduledMessageService(
    private val db: TorXDatabase,
    private val chat: ChatService,
    private val groups: GroupService,
    scheduler: ScheduledWorkScheduler,
    private val identity: () -> String?,
    private val ready: suspend () -> Boolean
) {
    private val engine = ScheduledMessageEngine(db.scheduledMessageDao(), scheduler, ready,
        committed = { row ->
            db.messageDao().getById(SchedulingPolicy.messageId(row.scheduleId))?.let {
                require(it.conversationId == row.conversationId && it.senderId == row.ownerIdentityId) {
                    "Scheduled message ID conflict"
                }
                true
            } ?: false
        }, send = ::sendNow)

    fun observe(conversationId: String) = engine.observe(conversationId)
    suspend fun create(conversationId: String, text: String, scheduledAt: Long, replyToMessageId: String? = null): String {
        requireNotNull(db.conversationDao().getById(conversationId)) { "Chat unavailable" }
        return engine.create(conversationId, identity().orEmpty(), text, scheduledAt, replyToMessageId)
    }
    suspend fun edit(id: String, generation: Long, text: String, scheduledAt: Long, replyToMessageId: String? = null) =
        engine.edit(id, generation, text, scheduledAt, replyToMessageId)
    suspend fun cancel(id: String, generation: Long) = engine.cancel(id, generation)
    suspend fun recover() = engine.recover()
    suspend fun run(id: String, generation: Long) = engine.run(id, generation)

    private suspend fun sendNow(row: ScheduledMessageEntity) {
        check(ready()) { "Unlock your identity first" }
        val owner = identity() ?: error("Unlock your identity first")
        if (owner != row.ownerIdentityId) throw PermanentScheduleException("This schedule belongs to a different identity")
        val conversation = db.conversationDao().getById(row.conversationId)
            ?: throw PermanentScheduleException("Chat was removed")
        val reply = row.replyToMessageId?.let { id ->
            db.messageDao().getById(id)?.takeIf { it.conversationId == row.conversationId && it.deletedAt == null }?.logicalMessageId
        }
        val messageId = SchedulingPolicy.messageId(row.scheduleId)
        when (conversation.type) {
            ConversationType.DIRECT -> {
                val contact = db.contactDao().getByConversationId(row.conversationId)
                    ?: throw PermanentScheduleException("Contact was removed")
                chat.sendTextMessage(row.conversationId, contact.relationshipId, owner, contact.remoteIdentityId,
                    row.draftPayload, reply, messageId, clearDraft = false)
            }
            ConversationType.GROUP -> {
                val self = db.groupMemberDao().getMember(row.conversationId, owner)
                if (self?.state != GroupMemberState.ACTIVE.name)
                    throw PermanentScheduleException("You are no longer a group member")
                groups.sendGroupText(row.conversationId, row.draftPayload, reply, messageId, clearDraft = false)
            }
        }
    }
}
