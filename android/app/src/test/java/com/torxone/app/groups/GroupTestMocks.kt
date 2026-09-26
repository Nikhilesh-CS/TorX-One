package com.torxone.app.groups

import com.torxone.app.agent.*
import com.torxone.app.crypto.SessionState
import com.torxone.app.crypto.SessionStore
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap

class TestGroupDao : GroupDao {
    val groups = ConcurrentHashMap<String, GroupEntity>()

    override fun observeById(groupId: String): Flow<GroupEntity?> = flowOf(groups[groupId])
    override fun observeAll(): Flow<List<GroupEntity>> = flowOf(groups.values.sortedByDescending { it.updatedAt })
    override suspend fun getById(groupId: String): GroupEntity? = groups[groupId]
    override suspend fun getByConversationId(conversationId: String): GroupEntity? =
        groups.values.firstOrNull { it.conversationId == conversationId }

    override suspend fun upsert(group: GroupEntity) {
        groups[group.groupId] = group
    }

    override suspend fun updateEpoch(groupId: String, epoch: Long, updatedAt: Long) {
        groups[groupId]?.let { groups[groupId] = it.copy(epoch = epoch, updatedAt = updatedAt) }
    }

    override suspend fun updateTitle(groupId: String, title: String, updatedAt: Long) {
        groups[groupId]?.let { groups[groupId] = it.copy(title = title, updatedAt = updatedAt) }
    }

    override suspend fun updateAvatar(groupId: String, avatarHash: String?, updatedAt: Long) {
        groups[groupId]?.let { groups[groupId] = it.copy(avatarHash = avatarHash, updatedAt = updatedAt) }
    }

    override suspend fun deleteById(groupId: String) {
        groups.remove(groupId)
    }
}

class TestGroupMemberDao : GroupMemberDao {
    val members = ConcurrentHashMap<String, GroupMemberEntity>()

    private fun key(groupId: String, memberId: String) = "$groupId:$memberId"

    override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> =
        flowOf(members.values.filter { it.groupId == groupId }.sortedBy { it.joinedAt })

    override suspend fun getMembers(groupId: String): List<GroupMemberEntity> =
        members.values.filter { it.groupId == groupId }.sortedBy { it.joinedAt }

    override suspend fun getActiveMembers(groupId: String): List<GroupMemberEntity> =
        members.values.filter { it.groupId == groupId && it.state == GroupMemberState.ACTIVE.name }.sortedBy { it.joinedAt }

    override fun observeActiveMembers(groupId: String): Flow<List<GroupMemberEntity>> =
        flowOf(members.values.filter { it.groupId == groupId && it.state == GroupMemberState.ACTIVE.name }.sortedBy { it.joinedAt })

    override suspend fun getMember(groupId: String, memberIdentityId: String): GroupMemberEntity? =
        members[key(groupId, memberIdentityId)]

    override suspend fun upsert(member: GroupMemberEntity) {
        members[key(member.groupId, member.memberIdentityId)] = member
    }

    override suspend fun upsertAll(membersList: List<GroupMemberEntity>) {
        membersList.forEach { upsert(it) }
    }

    override suspend fun updateRole(groupId: String, memberIdentityId: String, role: String) {
        members[key(groupId, memberIdentityId)]?.let {
            members[key(groupId, memberIdentityId)] = it.copy(role = role)
        }
    }

    override suspend fun updateState(
        groupId: String,
        memberIdentityId: String,
        state: String,
        removedEpoch: Long?,
        removedAt: Long?
    ) {
        members[key(groupId, memberIdentityId)]?.let {
            members[key(groupId, memberIdentityId)] = it.copy(
                state = state,
                removedEpoch = removedEpoch,
                removedAt = removedAt
            )
        }
    }

    override suspend fun deleteMember(groupId: String, memberIdentityId: String) {
        members.remove(key(groupId, memberIdentityId))
    }

    override suspend fun deleteMembersForGroup(groupId: String) {
        members.entries.removeIf { it.value.groupId == groupId }
    }
}

class TestGroupMessageDeliveryDao : GroupMessageDeliveryDao {
    val deliveries = ConcurrentHashMap<String, GroupMessageDeliveryEntity>()

    override fun observeDeliveriesForMessage(logicalMessageId: String): Flow<List<GroupMessageDeliveryEntity>> =
        flowOf(deliveries.values.filter { it.logicalMessageId == logicalMessageId })

    override suspend fun getDeliveriesForMessage(logicalMessageId: String): List<GroupMessageDeliveryEntity> =
        deliveries.values.filter { it.logicalMessageId == logicalMessageId }

    override suspend fun getPendingDeliveries(): List<GroupMessageDeliveryEntity> =
        deliveries.values.filter { it.status == GroupDeliveryStatus.PENDING.name || it.status == GroupDeliveryStatus.QUEUED.name }
            .sortedBy { it.createdAt }

    override suspend fun upsert(delivery: GroupMessageDeliveryEntity) {
        deliveries[delivery.deliveryId] = delivery
    }

    override suspend fun upsertAll(deliveriesList: List<GroupMessageDeliveryEntity>) {
        deliveriesList.forEach { upsert(it) }
    }

    override suspend fun updateStatus(deliveryId: String, status: String, updatedAt: Long) {
        deliveries[deliveryId]?.let {
            deliveries[deliveryId] = it.copy(status = status, updatedAt = updatedAt)
        }
    }

    override suspend fun markDelivered(
        logicalMessageId: String,
        recipientIdentityId: String,
        deliveredAt: Long,
        updatedAt: Long
    ) {
        deliveries.values.firstOrNull { it.logicalMessageId == logicalMessageId && it.recipientIdentityId == recipientIdentityId }?.let {
            deliveries[it.deliveryId] = it.copy(
                status = GroupDeliveryStatus.DELIVERED.name,
                deliveredAt = deliveredAt,
                updatedAt = updatedAt
            )
        }
    }

    override suspend fun markRead(
        logicalMessageId: String,
        recipientIdentityId: String,
        readAt: Long,
        updatedAt: Long
    ) {
        deliveries.values.firstOrNull { it.logicalMessageId == logicalMessageId && it.recipientIdentityId == recipientIdentityId }?.let {
            deliveries[it.deliveryId] = it.copy(
                status = GroupDeliveryStatus.READ.name,
                readAt = readAt,
                updatedAt = updatedAt
            )
        }
    }

    override suspend fun deleteForMessage(logicalMessageId: String) {
        deliveries.entries.removeIf { it.value.logicalMessageId == logicalMessageId }
    }
}

class TestConversationDao : ConversationDao {
    val convs = ConcurrentHashMap<String, ConversationEntity>()

    override fun observeActive(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())
    override fun observeArchived(): Flow<List<ConversationEntity>> = flowOf(emptyList())
    override fun observeArchivedCount(): Flow<Int> = flowOf(0)
    override suspend fun getById(id: String): ConversationEntity? = convs[id]
    override fun observeById(id: String): Flow<ConversationEntity?> = flowOf(convs[id])
    override suspend fun upsert(conversation: ConversationEntity) { convs[conversation.conversationId] = conversation }
    override suspend fun updateUnreadCount(id: String, count: Int) { convs[id]?.let { convs[id] = it.copy(unreadCount = count) } }
    override suspend fun updateManuallyUnread(id: String, manuallyUnread: Boolean) {}
    override suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String?, time: Long) {
        convs[conversationId]?.let { convs[conversationId] = it.copy(lastMessageId = messageId, lastMessagePreview = preview, lastMessageTime = time) }
    }
    override suspend fun updateLastMessagePreviewIfLatest(messageId: String, preview: String?) {}
    override suspend fun setPinned(id: String, isPinned: Boolean, pinnedAt: Long?) {}
    override suspend fun setArchived(id: String, isArchived: Boolean, archivedAt: Long?) {}
    override suspend fun unarchive(id: String) {}
    override suspend fun setMutedUntil(id: String, mutedUntil: Long?) {}
    override suspend fun deleteById(id: String) { convs.remove(id) }
    override fun searchConversations(query: String): Flow<List<ConversationEntity>> = flowOf(emptyList())
}

class TestMessageDao : MessageDao {
    val msgs = ConcurrentHashMap<String, MessageEntity>()

    override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
        flowOf(msgs.values.filter { it.conversationId == conversationId })
    override suspend fun getById(messageId: String): MessageEntity? = msgs[messageId]
    override suspend fun exists(messageId: String): Boolean = msgs.containsKey(messageId)
    override suspend fun insertIfAbsent(message: MessageEntity): Long {
        return if (msgs.putIfAbsent(message.logicalMessageId, message) == null) 1L else -1L
    }
    override suspend fun upsert(message: MessageEntity) { msgs[message.logicalMessageId] = message }
    override suspend fun updateStatus(messageId: String, status: String) {
        msgs[messageId]?.let { msgs[messageId] = it.copy(status = status) }
    }
    override suspend fun markDelivered(messageId: String, status: String, deliveredAt: Long) {
        msgs[messageId]?.let { msgs[messageId] = it.copy(status = status, deliveredAt = deliveredAt) }
    }
    override suspend fun markRead(messageId: String, status: String, readAt: Long) {
        msgs[messageId]?.let { msgs[messageId] = it.copy(status = status, readAt = readAt) }
    }
    override suspend fun markOutgoingReadUpTo(conversationId: String, upToCreatedAt: Long, status: String, readAt: Long) {
        for ((id, msg) in msgs) {
            if (msg.conversationId == conversationId && msg.direction == MessageDirection.OUTGOING && msg.createdAt <= upToCreatedAt) {
                msgs[id] = msg.copy(status = status, readAt = readAt)
            }
        }
    }
    override suspend fun markAllIncomingRead(conversationId: String, status: String, readAt: Long) {
        for ((id, msg) in msgs) {
            if (msg.conversationId == conversationId && msg.direction == MessageDirection.INCOMING) {
                msgs[id] = msg.copy(status = status, readAt = readAt)
            }
        }
    }
    override suspend fun getLatestUnreadIncoming(conversationId: String): MessageEntity? =
        msgs.values.filter { it.conversationId == conversationId && it.direction == MessageDirection.INCOMING && it.status != "READ" }
            .maxByOrNull { it.createdAt }

    override suspend fun updateBodyAndEdit(messageId: String, newBody: String, editVersion: Int, editedAt: Long) {
        msgs[messageId]?.let { msgs[messageId] = it.copy(body = newBody, editVersion = editVersion, editedAt = editedAt) }
    }
    override suspend fun markDeleted(messageId: String, deletedAt: Long) {
        msgs[messageId]?.let { msgs[messageId] = it.copy(body = null, deletedAt = deletedAt) }
    }
    override suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity> =
        msgs.values.filter { it.conversationId == conversationId }.sortedByDescending { it.createdAt }

    override suspend fun deleteByConversation(conversationId: String) {
        msgs.entries.removeIf { it.value.conversationId == conversationId }
    }
}

class TestReactionDao : ReactionDao {
    val reactions = mutableListOf<ReactionEntity>()

    override suspend fun getForMessage(messageId: String): List<ReactionEntity> = reactions.filter { it.messageId == messageId }
    override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> =
        flowOf(reactions.filter { it.conversationId == conversationId })

    override suspend fun insertOrUpdate(reaction: ReactionEntity) {
        reactions.removeIf { it.messageId == reaction.messageId && it.senderId == reaction.senderId }
        reactions.add(reaction)
    }

    override suspend fun remove(messageId: String, senderId: String, emoji: String) {
        reactions.removeIf { it.messageId == messageId && it.senderId == senderId && it.emoji == emoji }
    }

    override suspend fun removeAllFromSender(messageId: String, senderId: String) {
        reactions.removeIf { it.messageId == messageId && it.senderId == senderId }
    }

    override suspend fun deleteByConversation(conversationId: String) {
        reactions.removeIf { it.conversationId == conversationId }
    }
}

class TestContactDao : ContactDao {
    val contacts = ConcurrentHashMap<String, ContactEntity>()

    override fun observeAll(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
    override suspend fun getById(id: String): ContactEntity? = contacts[id]
    override suspend fun getByRelationshipId(relationshipId: String): ContactEntity? =
        contacts.values.firstOrNull { it.relationshipId == relationshipId }
    override suspend fun getByConversationId(conversationId: String): ContactEntity? =
        contacts.values.firstOrNull { it.conversationId == conversationId }
    override suspend fun getAll(): List<ContactEntity> = contacts.values.toList()
    override suspend fun upsert(contact: ContactEntity) { contacts[contact.contactId] = contact }
}

class TestSessionStore : SessionStore {
    val sessions = ConcurrentHashMap<String, SessionState>()

    override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
    override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
    override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
}

class TestOutboxStore : OutboxStore {
    val items = ConcurrentHashMap<String, DeliveryItem>()

    override suspend fun insert(item: DeliveryItem) {
        items[item.deliveryId] = item
    }

    override suspend fun getPendingItems(): List<DeliveryItem> {
        val now = System.currentTimeMillis()
        return items.values.filter {
            (it.status == DeliveryStatus.QUEUED ||
             it.status == DeliveryStatus.RETRY_WAIT ||
             it.status == DeliveryStatus.TRANSMITTING ||
             it.status == DeliveryStatus.TRANSPORT_ACCEPTED) &&
            it.nextAttemptAt <= now
        }
    }

    override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
        items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = System.currentTimeMillis()) }
    }

    override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
        items[deliveryId]?.let {
            items[deliveryId] = it.copy(
                attemptCount = attemptCount,
                nextAttemptAt = nextAttemptAt,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun removeByMessageId(logicalMessageId: String) {
        items.entries.removeIf { it.value.logicalMessageId == logicalMessageId }
    }
}

class TestOutboxDao(private val store: TestOutboxStore) : OutboxDao {
    override suspend fun getPending(now: Long): List<OutboxEntity> = emptyList()
    override suspend fun insert(item: OutboxEntity) {}
    override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {}
    override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {}
    override suspend fun removeByMessageId(logicalMessageId: String) {
        store.removeByMessageId(logicalMessageId)
    }
    override suspend fun removeByDeliveryId(deliveryId: String) {
        store.items.remove(deliveryId)
    }
}

class TestProcessedStore : ProcessedEnvelopeStore, ProcessedEnvelopeDao {
    val processed = ConcurrentHashMap.newKeySet<String>()

    override suspend fun isProcessed(envelopeId: String): Boolean = processed.contains(envelopeId)
    override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = processed.contains(logicalMessageId)
    override suspend fun markProcessed(record: ProcessedEnvelope) { processed.add(record.envelopeId) }
    override suspend fun insert(entity: ProcessedEnvelopeEntity) { processed.add(entity.envelopeId) }
    override suspend fun getByEnvelopeId(envelopeId: String): ProcessedEnvelopeEntity? =
        if (processed.contains(envelopeId)) ProcessedEnvelopeEntity(envelopeId, "test", System.currentTimeMillis()) else null
    override suspend fun pruneOlderThan(before: Long) {}
}
