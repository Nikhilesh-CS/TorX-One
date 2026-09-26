package com.torxone.app.groups

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.protocol.*
import java.util.UUID

/**
 * Authority for group chat, membership, epoch transitions, and recoverable fan-out.
 *
 * Invariant: Group ≠ shared network connection.
 * A group is a membership/state layer atop existing pairwise Double Ratchet relationships.
 */
class GroupService(
    private val groupDao: GroupDao,
    private val groupMemberDao: GroupMemberDao,
    private val groupMessageDeliveryDao: GroupMessageDeliveryDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val contactDao: ContactDao,
    private val outboxDao: OutboxDao,
    private val connectionManager: ConnectionManager,
    private val sessionCrypto: SessionCrypto,
    private val agent: TorXAgent,
    private val localIdentityIdProvider: suspend () -> String?,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit
) {
    companion object {
        private const val TAG = "GroupService"
    }

    // ─── Group Creation ────────────────────────────────────────────────────────

    /**
     * Creates a new direct group with the given title and initial contacts.
     * Atomically stores ConversationEntity, GroupEntity, and GroupMemberEntity roster,
     * then fans out GROUP_CREATE invitations over pairwise relationships.
     */
    suspend fun createGroup(
        title: String,
        initialMembers: List<ContactEntity>,
        avatarHash: String? = null
    ): GroupEntity {
        require(title.isNotBlank()) { "Group title cannot be blank" }
        val localIdentityId = localIdentityIdProvider()
            ?: throw IllegalStateException("Local identity not initialized")

        val groupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val groupEntity = GroupEntity(
            groupId = groupId,
            conversationId = groupId,
            title = title.trim(),
            avatarHash = avatarHash,
            creatorIdentityId = localIdentityId,
            epoch = 1L,
            createdAt = now,
            updatedAt = now
        )

        val conversationEntity = ConversationEntity(
            conversationId = groupId,
            type = ConversationType.GROUP,
            title = title.trim(),
            avatarHash = avatarHash,
            createdAt = now
        )

        val memberEntities = mutableListOf<GroupMemberEntity>()
        // 1. Add creator as OWNER
        memberEntities.add(
            GroupMemberEntity(
                groupId = groupId,
                memberIdentityId = localIdentityId,
                contactId = "self",
                relationshipId = "self",
                role = GroupMemberRole.OWNER.name,
                state = GroupMemberState.ACTIVE.name,
                joinedEpoch = 1L,
                joinedAt = now
            )
        )

        // 2. Add invited contacts as MEMBER
        initialMembers.forEach { contact ->
            memberEntities.add(
                GroupMemberEntity(
                    groupId = groupId,
                    memberIdentityId = contact.contactId,
                    contactId = contact.contactId,
                    relationshipId = contact.relationshipId,
                    role = GroupMemberRole.MEMBER.name,
                    state = GroupMemberState.ACTIVE.name,
                    joinedEpoch = 1L,
                    joinedAt = now
                )
            )
        }

        // 3. Atomically persist group, conversation, and members
        transactionRunner {
            conversationDao.upsert(conversationEntity)
            groupDao.upsert(groupEntity)
            groupMemberDao.upsertAll(memberEntities)
        }

        // 4. Build roster snapshot for invitation payload
        val memberSnapshots = memberEntities.map {
            GroupMemberSnapshot(
                identityId = it.memberIdentityId,
                contactId = it.contactId,
                role = GroupMemberRole.fromString(it.role),
                state = GroupMemberState.fromString(it.state)
            )
        }

        val invitePayload = GroupInvitePayload(
            groupId = groupId,
            title = title.trim(),
            avatarHash = avatarHash,
            creatorIdentity = localIdentityId,
            epoch = 1L,
            inviterIdentity = localIdentityId,
            members = memberSnapshots
        )
        val payloadBytes = GroupProtocolCodec.encodeInvite(invitePayload)

        // 5. Fan-out GROUP_CREATE invitation over pairwise relationships
        initialMembers.forEach { contact ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = contact.contactId,
                    relationshipId = contact.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_CREATE,
                    payload = payloadBytes,
                    epoch = 1L,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group invite to ${contact.contactId}: ${e.message}")
            }
        }

        return groupEntity
    }

    // ─── Group Message Sending & Fan-Out ───────────────────────────────────────

    /**
     * Sends a text message to a group.
     * Persists logical MessageEntity once, records per-recipient GroupMessageDeliveryEntity,
     * and performs recoverable pairwise fan-out to all active members.
     */
    suspend fun sendGroupText(
        groupId: String,
        text: String,
        replyToMessageId: String? = null
    ): MessageEntity {
        require(text.isNotBlank()) { "Group message text cannot be blank" }
        val localIdentityId = localIdentityIdProvider()
            ?: throw IllegalStateException("Local identity not initialized")

        val group = groupDao.getById(groupId)
            ?: throw IllegalArgumentException("Group $groupId not found")

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId)
        if (selfMember == null || selfMember.state != GroupMemberState.ACTIVE.name) {
            throw IllegalStateException("Local user is not an active member of group $groupId")
        }

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = groupId,
            senderId = localIdentityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = MessageDirection.OUTGOING,
            status = if (activeMembers.isEmpty()) DeliveryStatus.DELIVERED.name else DeliveryStatus.QUEUED.name,
            createdAt = now,
            replyToMessageId = replyToMessageId
        )

        // Pre-create per-recipient delivery records
        val deliveryEntities = activeMembers.map { member ->
            GroupMessageDeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = messageId,
                recipientIdentityId = member.memberIdentityId,
                relationshipId = member.relationshipId,
                status = GroupDeliveryStatus.PENDING.name,
                createdAt = now,
                updatedAt = now
            )
        }

        // Atomically commit logical message, conversation update, and delivery records
        transactionRunner {
            messageDao.upsert(messageEntity)
            conversationDao.updateLastMessage(
                conversationId = groupId,
                messageId = messageId,
                preview = text,
                time = now
            )
            groupMessageDeliveryDao.upsertAll(deliveryEntities)
        }

        // Fan-out to each active member independently
        val payloadBytes = text.toByteArray(Charsets.UTF_8)
        deliveryEntities.forEach { delivery ->
            try {
                encryptAndEnqueueGroupMessage(
                    groupId = groupId,
                    epoch = group.epoch,
                    messageId = messageId,
                    recipientIdentityId = delivery.recipientIdentityId,
                    relationshipId = delivery.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.TEXT,
                    payload = payloadBytes,
                    replyToMessageId = replyToMessageId,
                    deliveryId = delivery.deliveryId,
                    priority = DeliveryPriority.NORMAL
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed pairwise fan-out for msg=$messageId to recipient=${delivery.recipientIdentityId}: ${e.message}")
            }
        }

        return messageEntity
    }

    // ─── Group Reactions ───────────────────────────────────────────────────────

    /**
     * Sends a reaction on a group message and fans out to all active members.
     */
    suspend fun sendGroupReaction(
        groupId: String,
        targetMessageId: String,
        emoji: String,
        operation: ReactionOperation = ReactionOperation.ADD
    ): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId)
        if (selfMember == null || selfMember.state != GroupMemberState.ACTIVE.name) return false

        val now = System.currentTimeMillis()

        // 1. Update local reaction store
        if (operation == ReactionOperation.ADD) {
            reactionDao.insertOrUpdate(
                ReactionEntity(
                    messageId = targetMessageId,
                    conversationId = groupId,
                    senderId = localIdentityId,
                    emoji = emoji,
                    createdAt = now
                )
            )
        } else {
            reactionDao.remove(
                messageId = targetMessageId,
                senderId = localIdentityId,
                emoji = emoji
            )
        }

        // 2. Fan out to all active peers
        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        val reactionPayload = MessageReaction(
            targetMessageId = targetMessageId,
            emoji = emoji,
            operation = operation
        ).toByteArray()

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.REACTION,
                    payload = reactionPayload,
                    epoch = group.epoch,
                    priority = DeliveryPriority.NORMAL,
                    expectsAck = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fan out reaction to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    // ─── Group Edits (Author Only) ─────────────────────────────────────────────

    /**
     * Edits a group message. Enforces author-only rule and fans out to peers.
     */
    suspend fun sendGroupEdit(
        groupId: String,
        targetMessageId: String,
        newText: String
    ): Boolean {
        require(newText.isNotBlank()) { "Edited text cannot be blank" }
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val targetMessage = messageDao.getById(targetMessageId) ?: return false
        if (targetMessage.senderId != localIdentityId) {
            Log.w(TAG, "Rejecting edit: local user is not author of msg=$targetMessageId")
            return false
        }
        if (targetMessage.deletedAt != null) return false

        val newVersion = targetMessage.editVersion + 1
        val now = System.currentTimeMillis()

        // Update local message
        messageDao.updateBodyAndEdit(
            messageId = targetMessageId,
            newBody = newText,
            editVersion = newVersion,
            editedAt = now
        )
        conversationDao.updateLastMessagePreviewIfLatest(targetMessageId, newText)

        // Fan out EDIT event
        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        val editPayload = MessageEdit(
            targetMessageId = targetMessageId,
            newText = newText,
            editVersion = newVersion
        ).toByteArray()

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.EDIT,
                    payload = editPayload,
                    epoch = group.epoch,
                    priority = DeliveryPriority.NORMAL,
                    expectsAck = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fan out edit to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    // ─── Group Deletes (Author Only Delete for Everyone) ───────────────────────

    /**
     * Deletes a group message for everyone. Enforces author-only rule.
     */
    suspend fun sendGroupDelete(
        groupId: String,
        targetMessageId: String
    ): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val targetMessage = messageDao.getById(targetMessageId) ?: return false
        if (targetMessage.senderId != localIdentityId) {
            Log.w(TAG, "Rejecting delete: local user is not author of msg=$targetMessageId")
            return false
        }

        val now = System.currentTimeMillis()

        // Mark deleted locally
        messageDao.markDeleted(targetMessageId, now)
        conversationDao.updateLastMessagePreviewIfLatest(targetMessageId, "This message was deleted")

        // Fan out DELETE event
        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        val deletePayload = MessageDelete(targetMessageId = targetMessageId).toByteArray()

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.DELETE,
                    payload = deletePayload,
                    epoch = group.epoch,
                    priority = DeliveryPriority.HIGH,
                    expectsAck = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fan out delete to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    // ─── Delivery Receipts & Aggregate Status ──────────────────────────────────

    /**
     * Checks if a message is tracked as a group message.
     */
    suspend fun isGroupMessage(logicalMessageId: String): Boolean {
        return groupMessageDeliveryDao.getDeliveriesForMessage(logicalMessageId).isNotEmpty()
    }

    /**
     * Handles incoming delivery ACK from a group peer.
     * Updates GroupMessageDeliveryEntity and derives visible MessageEntity status.
     */
    suspend fun handleDeliveryAck(
        logicalMessageId: String,
        recipientIdentityId: String,
        deliveredAt: Long = System.currentTimeMillis()
    ) {
        groupMessageDeliveryDao.markDelivered(
            logicalMessageId = logicalMessageId,
            recipientIdentityId = recipientIdentityId,
            deliveredAt = deliveredAt
        )

        // Check if all active recipients are delivered
        val deliveries = groupMessageDeliveryDao.getDeliveriesForMessage(logicalMessageId)
        if (deliveries.isNotEmpty()) {
            val allDelivered = deliveries.all {
                it.status == GroupDeliveryStatus.DELIVERED.name || it.status == GroupDeliveryStatus.READ.name
            }
            if (allDelivered) {
                val currentMsg = messageDao.getById(logicalMessageId)
                if (currentMsg != null && currentMsg.status != DeliveryStatus.READ.name) {
                    messageDao.markDelivered(logicalMessageId, DeliveryStatus.DELIVERED.name, deliveredAt)
                }
            }
        }
    }

    /**
     * Handles incoming read receipt from a group peer.
     * Updates GroupMessageDeliveryEntity and derives visible MessageEntity status.
     */
    suspend fun handleReadReceipt(
        logicalMessageId: String,
        recipientIdentityId: String,
        readAt: Long = System.currentTimeMillis()
    ) {
        groupMessageDeliveryDao.markRead(
            logicalMessageId = logicalMessageId,
            recipientIdentityId = recipientIdentityId,
            readAt = readAt
        )

        // Check if all active recipients have read
        val deliveries = groupMessageDeliveryDao.getDeliveriesForMessage(logicalMessageId)
        if (deliveries.isNotEmpty()) {
            val allRead = deliveries.all { it.status == GroupDeliveryStatus.READ.name }
            if (allRead) {
                messageDao.markRead(logicalMessageId, DeliveryStatus.READ.name, readAt)
            }
        }
    }

    /**
     * Returns detailed delivery breakdown for message info screen.
     */
    suspend fun getDeliverySummary(logicalMessageId: String): GroupMessageDeliverySummary? {
        val message = messageDao.getById(logicalMessageId) ?: return null
        val deliveries = groupMessageDeliveryDao.getDeliveriesForMessage(logicalMessageId)
        val contacts = contactDao.getAll().associateBy { it.contactId }

        val details = deliveries.map { d ->
            val contactName = contacts[d.recipientIdentityId]?.displayName ?: d.recipientIdentityId.take(8)
            GroupRecipientDeliveryState(
                recipientIdentityId = d.recipientIdentityId,
                recipientName = contactName,
                status = GroupDeliveryStatus.fromString(d.status),
                deliveredAt = d.deliveredAt,
                readAt = d.readAt
            )
        }

        val total = deliveries.size
        val deliveredCount = deliveries.count { it.status == GroupDeliveryStatus.DELIVERED.name || it.status == GroupDeliveryStatus.READ.name }
        val readCount = deliveries.count { it.status == GroupDeliveryStatus.READ.name }

        val overallStatus = when {
            total == 0 -> GroupDeliveryStatus.DELIVERED
            readCount == total -> GroupDeliveryStatus.READ
            deliveredCount == total -> GroupDeliveryStatus.DELIVERED
            else -> GroupDeliveryStatus.QUEUED
        }

        return GroupMessageDeliverySummary(
            logicalMessageId = logicalMessageId,
            totalRecipients = total,
            deliveredCount = deliveredCount,
            readCount = readCount,
            overallStatus = overallStatus,
            details = details
        )
    }

    // ─── Membership Operations & Epoch Enforcement ────────────────────────────

    /**
     * Adds a new member to the group. Enforces OWNER/ADMIN authorization and advances epoch.
     */
    suspend fun addMember(
        groupId: String,
        contact: ContactEntity,
        role: GroupMemberRole = GroupMemberRole.MEMBER
    ): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId)
            ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) {
            Log.w(TAG, "Unauthorized addMember: $localIdentityId is $selfRole")
            return false
        }

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        val newMemberEntity = GroupMemberEntity(
            groupId = groupId,
            memberIdentityId = contact.contactId,
            contactId = contact.contactId,
            relationshipId = contact.relationshipId,
            role = role.name,
            state = GroupMemberState.ACTIVE.name,
            joinedEpoch = newEpoch,
            joinedAt = now
        )

        transactionRunner {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.upsert(newMemberEntity)
        }

        // 1. Send GROUP_MEMBER_INVITE to the new member with current roster
        val allMembers = groupMemberDao.getActiveMembers(groupId)
        val rosterSnapshot = allMembers.map {
            GroupMemberSnapshot(
                identityId = it.memberIdentityId,
                contactId = it.contactId,
                role = GroupMemberRole.fromString(it.role),
                state = GroupMemberState.fromString(it.state)
            )
        }
        val invitePayload = GroupInvitePayload(
            groupId = groupId,
            title = group.title,
            avatarHash = group.avatarHash,
            creatorIdentity = group.creatorIdentityId,
            epoch = newEpoch,
            inviterIdentity = localIdentityId,
            members = rosterSnapshot
        )
        val inviteBytes = GroupProtocolCodec.encodeInvite(invitePayload)
        fanoutControlEnvelope(
            groupId = groupId,
            recipientIdentityId = contact.contactId,
            relationshipId = contact.relationshipId,
            localIdentityId = localIdentityId,
            messageType = MessageType.GROUP_MEMBER_INVITE,
            payload = inviteBytes,
            epoch = newEpoch,
            priority = DeliveryPriority.HIGH
        )

        // 2. Notify existing members of new member joined
        val joinedPayload = GroupMemberJoinedPayload(
            groupId = groupId,
            memberIdentity = contact.contactId,
            role = role,
            epoch = newEpoch,
            actorIdentity = localIdentityId
        )
        val joinedBytes = GroupProtocolCodec.encodeJoined(joinedPayload)
        val existingPeers = allMembers.filter {
            it.memberIdentityId != localIdentityId && it.memberIdentityId != contact.contactId
        }

        existingPeers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_MEMBER_ACCEPT,
                    payload = joinedBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify member ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    /**
     * Removes a member from the group.
     * Enforces: OWNER/ADMIN role, nobody removes OWNER, admins cannot remove owners or admins.
     */
    suspend fun removeMember(
        groupId: String,
        targetIdentityId: String
    ): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)

        val targetMember = groupMemberDao.getMember(groupId, targetIdentityId) ?: return false
        val targetRole = GroupMemberRole.fromString(targetMember.role)

        // Authorization rules:
        // 1. Nobody removes OWNER
        if (targetRole == GroupMemberRole.OWNER) {
            Log.w(TAG, "Cannot remove OWNER from group $groupId")
            return false
        }
        // 2. Local user must be OWNER or ADMIN
        if (selfRole == GroupMemberRole.MEMBER) {
            Log.w(TAG, "MEMBER cannot remove another member")
            return false
        }
        // 3. ADMIN cannot remove another ADMIN
        if (selfRole == GroupMemberRole.ADMIN && targetRole == GroupMemberRole.ADMIN) {
            Log.w(TAG, "ADMIN cannot remove another ADMIN")
            return false
        }

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        transactionRunner {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateState(
                groupId = groupId,
                memberIdentityId = targetIdentityId,
                state = GroupMemberState.REMOVED.name,
                removedEpoch = newEpoch,
                removedAt = now
            )
        }

        // Notify remaining active peers and the removed member
        val removePayload = GroupMemberRemovePayload(
            groupId = groupId,
            targetIdentity = targetIdentityId,
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val removeBytes = GroupProtocolCodec.encodeRemove(removePayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        // Send to remaining active members
        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_MEMBER_REMOVE,
                    payload = removeBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send remove notice to ${member.memberIdentityId}: ${e.message}")
            }
        }

        // Also inform the removed member over their pairwise connection
        try {
            fanoutControlEnvelope(
                groupId = groupId,
                recipientIdentityId = targetMember.memberIdentityId,
                relationshipId = targetMember.relationshipId,
                localIdentityId = localIdentityId,
                messageType = MessageType.GROUP_MEMBER_REMOVE,
                payload = removeBytes,
                epoch = newEpoch,
                priority = DeliveryPriority.HIGH
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify removed member: ${e.message}")
        }

        return true
    }

    /**
     * Voluntary leave by local user.
     */
    suspend fun leaveGroup(groupId: String): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        transactionRunner {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateState(
                groupId = groupId,
                memberIdentityId = localIdentityId,
                state = GroupMemberState.LEFT.name,
                removedEpoch = newEpoch,
                removedAt = now
            )
        }

        val leavePayload = GroupMemberLeavePayload(
            groupId = groupId,
            memberIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val leaveBytes = GroupProtocolCodec.encodeLeave(leavePayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_MEMBER_REMOVE,
                    payload = leaveBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send leave notice to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    /**
     * Updates member role. Enforces OWNER role.
     */
    suspend fun changeRole(
        groupId: String,
        targetIdentityId: String,
        newRole: GroupMemberRole
    ): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        if (GroupMemberRole.fromString(selfMember.role) != GroupMemberRole.OWNER) {
            Log.w(TAG, "Only OWNER can change roles in group $groupId")
            return false
        }

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        transactionRunner {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateRole(groupId, targetIdentityId, newRole.name)
        }

        val rolePayload = GroupRoleChangePayload(
            groupId = groupId,
            targetIdentity = targetIdentityId,
            newRole = newRole,
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val roleBytes = GroupProtocolCodec.encodeRoleChange(rolePayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_ROLE_CHANGE,
                    payload = roleBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send role change to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    /**
     * Renames the group. Requires OWNER or ADMIN.
     */
    suspend fun updateGroupTitle(groupId: String, newTitle: String): Boolean {
        require(newTitle.isNotBlank()) { "Title cannot be blank" }
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) return false

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        transactionRunner {
            groupDao.updateTitle(groupId, newTitle.trim(), now)
            groupDao.updateEpoch(groupId, newEpoch, now)
            conversationDao.getById(groupId)?.let { conv ->
                conversationDao.upsert(conv.copy(title = newTitle.trim()))
            }
        }

        val namePayload = GroupNameChangePayload(
            groupId = groupId,
            newTitle = newTitle.trim(),
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val nameBytes = GroupProtocolCodec.encodeNameChange(namePayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_NAME_CHANGE,
                    payload = nameBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send name change to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    /**
     * Updates the group avatar hash. Requires OWNER or ADMIN.
     */
    suspend fun updateGroupAvatar(groupId: String, avatarHash: String?): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) return false

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        transactionRunner {
            groupDao.updateAvatar(groupId, avatarHash, now)
            groupDao.updateEpoch(groupId, newEpoch, now)
            conversationDao.getById(groupId)?.let { conv ->
                conversationDao.upsert(conv.copy(avatarHash = avatarHash))
            }
        }

        val avatarPayload = GroupAvatarChangePayload(
            groupId = groupId,
            newAvatarHash = avatarHash,
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val avatarBytes = GroupProtocolCodec.encodeAvatarChange(avatarPayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        activeMembers.forEach { member ->
            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.GROUP_AVATAR_CHANGE,
                    payload = avatarBytes,
                    epoch = newEpoch,
                    priority = DeliveryPriority.HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send avatar change to ${member.memberIdentityId}: ${e.message}")
            }
        }

        return true
    }

    /**
     * Marks all unread incoming messages in a group as READ locally and dispatches
     * READ receipts to the authors of those messages.
     */
    suspend fun markGroupRead(groupId: String) {
        val now = System.currentTimeMillis()
        val localIdentityId = localIdentityIdProvider() ?: return
        val group = groupDao.getById(groupId) ?: return

        // 1. Mark local Room messages as READ
        messageDao.markAllIncomingRead(groupId, DeliveryStatus.READ.name, now)
        conversationDao.updateUnreadCount(groupId, 0)
        conversationDao.updateManuallyUnread(groupId, false)

        // 2. Find unread incoming messages and dispatch READ receipt back to each author
        val unreadIncoming = messageDao.getMessagesForConversationDesc(groupId)
            .filter { it.direction == MessageDirection.INCOMING && it.senderId != localIdentityId }
        val latestBySender = unreadIncoming.groupBy { it.senderId }.mapValues { (_, msgs) -> msgs.maxByOrNull { it.createdAt } }

        val activeMembers = groupMemberDao.getActiveMembers(groupId).associateBy { it.memberIdentityId }

        for ((senderId, latestMsg) in latestBySender) {
            if (latestMsg == null) continue
            val member = activeMembers[senderId] ?: continue
            val receiptPayload = ReadReceipt(
                conversationId = groupId,
                upToMessageId = latestMsg.logicalMessageId,
                readAt = now
            ).toByteArray()

            try {
                fanoutControlEnvelope(
                    groupId = groupId,
                    recipientIdentityId = member.memberIdentityId,
                    relationshipId = member.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.READ_RECEIPT,
                    payload = receiptPayload,
                    epoch = group.epoch,
                    priority = DeliveryPriority.NORMAL,
                    expectsAck = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send read receipt to $senderId: ${e.message}")
            }
        }
    }

    // ─── Recoverable Fan-Out on Startup / Restart ──────────────────────────────

    /**
     * Scans for any pending group deliveries (e.g. if app crashed during fan-out)
     * and resumes encrypting and queuing them to outbox.
     */
    suspend fun recoverPendingFanout() {
        val localIdentityId = localIdentityIdProvider() ?: return
        val pendingDeliveries = groupMessageDeliveryDao.getPendingDeliveries()
        if (pendingDeliveries.isEmpty()) return

        Log.i(TAG, "[RECOVERY] Resuming ${pendingDeliveries.size} pending group message deliveries")

        pendingDeliveries.forEach { delivery ->
            try {
                val message = messageDao.getById(delivery.logicalMessageId) ?: return@forEach
                val group = groupDao.getByConversationId(message.conversationId) ?: return@forEach
                val payloadBytes = message.body?.toByteArray(Charsets.UTF_8) ?: return@forEach

                encryptAndEnqueueGroupMessage(
                    groupId = group.groupId,
                    epoch = group.epoch,
                    messageId = message.logicalMessageId,
                    recipientIdentityId = delivery.recipientIdentityId,
                    relationshipId = delivery.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.TEXT,
                    payload = payloadBytes,
                    replyToMessageId = message.replyToMessageId,
                    deliveryId = delivery.deliveryId,
                    priority = DeliveryPriority.NORMAL
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover pending delivery ${delivery.deliveryId}: ${e.message}")
            }
        }
    }

    // ─── Internal Fan-Out Helpers ──────────────────────────────────────────────

    private suspend fun encryptAndEnqueueGroupMessage(
        groupId: String,
        epoch: Long,
        messageId: String,
        recipientIdentityId: String,
        relationshipId: String,
        localIdentityId: String,
        messageType: MessageType,
        payload: ByteArray,
        replyToMessageId: String?,
        deliveryId: String,
        priority: Int
    ) {
        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val sendSeq = connectionManager.allocateSendSequence(relationshipId)
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = messageId,
            conversationId = groupId,
            senderIdentity = localIdentityId,
            recipientBinding = recipientIdentityId,
            messageType = messageType,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            replyToMessageId = replyToMessageId,
            groupMetadata = GroupEnvelopeMetadata(
                groupId = groupId,
                groupEpoch = epoch.toInt(),
                keyVersion = 1
            ),
            directionSequence = sendSeq
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

        var opaqueCiphertext: ByteArray? = null
        val outboxDeliveryId = UUID.randomUUID().toString()

        sessionCrypto.encryptAndCommit(relationshipId, envelopeBytes, aad) { encrypted, _ ->
            val ciphertext = encrypted.serialize()
            opaqueCiphertext = ciphertext

            val outboxEntity = OutboxEntity(
                deliveryId = outboxDeliveryId,
                logicalMessageId = messageId,
                conversationId = groupId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED.name,
                priority = priority,
                expectsAck = true
            )

            outboxDao.insert(outboxEntity)
            groupMessageDeliveryDao.upsert(
                GroupMessageDeliveryEntity(
                    deliveryId = deliveryId,
                    logicalMessageId = messageId,
                    recipientIdentityId = recipientIdentityId,
                    relationshipId = relationshipId,
                    outboxDeliveryId = outboxDeliveryId,
                    status = GroupDeliveryStatus.QUEUED.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Notify TorXAgent delivery engine
        opaqueCiphertext?.let { ct ->
            val deliveryItem = DeliveryItem(
                deliveryId = outboxDeliveryId,
                logicalMessageId = messageId,
                conversationId = groupId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ct,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = priority,
                expectsAck = true
            )
            agent.enqueue(deliveryItem)
        }
    }

    private suspend fun fanoutControlEnvelope(
        groupId: String,
        recipientIdentityId: String,
        relationshipId: String,
        localIdentityId: String,
        messageType: MessageType,
        payload: ByteArray,
        epoch: Long,
        priority: Int = DeliveryPriority.HIGH,
        expectsAck: Boolean = true
    ) {
        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val sendSeq = connectionManager.allocateSendSequence(relationshipId)
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = localIdentityId,
            recipientBinding = recipientIdentityId,
            messageType = messageType,
            timestamp = System.currentTimeMillis(),
            payload = payload,
            groupMetadata = GroupEnvelopeMetadata(
                groupId = groupId,
                groupEpoch = epoch.toInt(),
                keyVersion = 1
            ),
            directionSequence = sendSeq
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

        var opaqueCiphertext: ByteArray? = null
        val outboxDeliveryId = UUID.randomUUID().toString()

        sessionCrypto.encryptAndCommit(relationshipId, envelopeBytes, aad) { encrypted, _ ->
            val ciphertext = encrypted.serialize()
            opaqueCiphertext = ciphertext

            val outboxItem = OutboxEntity(
                deliveryId = outboxDeliveryId,
                logicalMessageId = envelope.logicalMessageId,
                conversationId = groupId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED.name,
                priority = priority,
                expectsAck = expectsAck
            )
            outboxDao.insert(outboxItem)
        }

        // Notify TorXAgent delivery engine
        opaqueCiphertext?.let { ct ->
            val deliveryItem = DeliveryItem(
                deliveryId = outboxDeliveryId,
                logicalMessageId = envelope.logicalMessageId,
                conversationId = groupId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ct,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = priority,
                expectsAck = expectsAck
            )
            agent.enqueue(deliveryItem)
        }
    }
}
