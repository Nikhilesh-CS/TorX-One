package com.torxone.app.groups

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.connection.RelationshipSendCoordinator
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
    private val groupControlDao: GroupControlDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val contactDao: ContactDao,
    private val outboxDao: OutboxDao,
    private val connectionManager: ConnectionManager,
    private val sessionCrypto: SessionCrypto,
    private val agent: TorXAgent,
    private val localIdentityIdProvider: suspend () -> String?,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit,
    private val relationshipSendCoordinator: RelationshipSendCoordinator? = null,
    private val sessionStore: com.torxone.app.crypto.SessionStore? = null,
    private val featureDao: FeatureDao? = null,
    private val securityPolicyService: com.torxone.app.privacy.SecurityPolicyService? = null,
    private val canSendText: suspend () -> Boolean = { true }
) {
    companion object {
        private const val TAG = "GroupService"
        /** Pairwise V1 is deliberately bounded; larger groups require the MLS V2 release gate. */
        const val MAX_PAIRWISE_GROUP_MEMBERS = 64
    }

    private val sendCoordinator: RelationshipSendCoordinator by lazy {
        relationshipSendCoordinator ?: RelationshipSendCoordinator(
            connectionManager = connectionManager,
            sessionStore = requireNotNull(sessionStore) { "GroupService requires SessionStore when no coordinator is injected" },
            sessionCrypto = sessionCrypto,
            agent = agent,
            transactionRunner = transactionRunner
        )
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

        require(initialMembers.map { it.remoteIdentityId }.distinct().size == initialMembers.size) {
            "Group member identities must be unique"
        }
        require(initialMembers.size + 1 <= MAX_PAIRWISE_GROUP_MEMBERS) {
            "Pairwise groups support at most $MAX_PAIRWISE_GROUP_MEMBERS members; MLS V2 is required above this limit"
        }
        initialMembers.forEach { contact ->
            require(contact.relationshipId.isNotBlank() &&
                connectionManager.getConnectionByRelationship(contact.relationshipId) != null) {
                "Cannot create group: no active pairwise relationship for ${contact.contactId}"
            }
        }

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

        // 2. Add invited contacts as MEMBER using their authoritative remote TorX identity ID
        initialMembers.forEach { contact ->
            require(contact.remoteIdentityId.isNotBlank() && contact.remoteIdentityId != ContactEntity.REMOTE_IDENTITY_UNKNOWN) {
                "Cannot add contact ${contact.contactId} to group: remote identity is missing or unauthenticated"
            }
            memberEntities.add(
                GroupMemberEntity(
                    groupId = groupId,
                    memberIdentityId = contact.remoteIdentityId,
                    contactId = contact.contactId,
                    relationshipId = contact.relationshipId,
                    role = GroupMemberRole.MEMBER.name,
                    state = GroupMemberState.ACTIVE.name,
                    joinedEpoch = 1L,
                    joinedAt = now
                )
            )
        }

        // 3. Build roster snapshot for invitation payload (never expose local database contactId on wire)
        val memberSnapshots = memberEntities.map {
            GroupMemberSnapshot(
                identityId = it.memberIdentityId,
                contactId = it.memberIdentityId,
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

        val operationId = persistControlTransition(
            groupId = groupId,
            previousEpoch = 0L,
            newEpoch = 1L,
            targets = initialMembers.map { contact ->
                ControlTarget(contact.remoteIdentityId, contact.relationshipId, MessageType.GROUP_CREATE, payloadBytes)
            }
        ) {
            conversationDao.upsert(conversationEntity)
            groupDao.upsert(groupEntity)
            groupMemberDao.upsertAll(memberEntities)
        }
        dispatchControlOperation(operationId, localIdentityId)

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
        replyToMessageId: String? = null,
        logicalMessageId: String? = null,
        clearDraft: Boolean = true
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

        val messageId = logicalMessageId ?: UUID.randomUUID().toString()
        messageDao.getById(messageId)?.let {
            require(it.conversationId == groupId && it.senderId == localIdentityId && it.body == text)
            return it
        }
        val now = System.currentTimeMillis()

        val expiresAt = securityPolicyService?.expiryForSend(groupId, activeMembers.map { it.relationshipId }, now)
        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = groupId,
            senderId = localIdentityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = MessageDirection.OUTGOING,
            status = if (activeMembers.isEmpty()) DeliveryStatus.DELIVERED.name else DeliveryStatus.QUEUED.name,
            createdAt = now,
            replyToMessageId = replyToMessageId,
            expiresAt = expiresAt
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
            check(canSendText()) { "Unlock TorX to send this message" }
            messageDao.upsert(messageEntity)
            if (clearDraft) featureDao?.deleteDraft(groupId)
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
        val target = messageDao.getById(targetMessageId) ?: return false
        if (target.conversationId != groupId) return false

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
                    expectsAck = true
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
        if (targetMessage.conversationId != groupId) return false
        if (targetMessage.senderId != localIdentityId) {
            Log.w(TAG, "Rejecting edit: local user is not author of msg=$targetMessageId")
            return false
        }
        if (targetMessage.deletedAt != null) return false

        if (targetMessage.editVersion == Int.MAX_VALUE) return false
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
                    expectsAck = true
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
        if (targetMessage.conversationId != groupId) return false
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
                    expectsAck = true
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
        val contacts = contactDao.getAll().filter { it.remoteIdentityId.isNotBlank() }.associateBy { it.remoteIdentityId }

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
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId)
            ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) {
            Log.w(TAG, "Unauthorized addMember: $localIdentityId is $selfRole")
            return false
        }

        if (contact.remoteIdentityId.isBlank() || contact.remoteIdentityId == ContactEntity.REMOTE_IDENTITY_UNKNOWN) {
            Log.w(TAG, "Cannot add contact with missing/unknown remote identity to group")
            return false
        }
        if (contact.relationshipId.isBlank() ||
            connectionManager.getConnectionByRelationship(contact.relationshipId) == null) {
            Log.w(TAG, "Cannot add contact without an active pairwise relationship")
            return false
        }
        if (groupMemberDao.getActiveMembers(groupId).size >= MAX_PAIRWISE_GROUP_MEMBERS) {
            Log.w(TAG, "Pairwise group member limit reached; MLS V2 migration is required")
            return false
        }

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        val newMemberEntity = GroupMemberEntity(
            groupId = groupId,
            memberIdentityId = contact.remoteIdentityId,
            contactId = contact.contactId,
            relationshipId = contact.relationshipId,
            role = role.name,
            state = GroupMemberState.ACTIVE.name,
            joinedEpoch = newEpoch,
            joinedAt = now
        )

        // Build the exact post-transition roster before changing local state.
        val allMembers = groupMemberDao.getActiveMembers(groupId) + newMemberEntity
        val rosterSnapshot = allMembers.map {
            GroupMemberSnapshot(
                identityId = it.memberIdentityId,
                contactId = it.memberIdentityId,
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
        // Existing members receive the joined event at the same epoch.
        val joinedPayload = GroupMemberJoinedPayload(
            groupId = groupId,
            memberIdentity = contact.remoteIdentityId,
            role = role,
            epoch = newEpoch,
            actorIdentity = localIdentityId
        )
        val joinedBytes = GroupProtocolCodec.encodeJoined(joinedPayload)
        val existingPeers = allMembers.filter {
            it.memberIdentityId != localIdentityId && it.memberIdentityId != contact.remoteIdentityId
        }

        val targets = listOf(
            ControlTarget(contact.remoteIdentityId, contact.relationshipId, MessageType.GROUP_MEMBER_INVITE, inviteBytes)
        ) + existingPeers.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_MEMBER_ACCEPT, joinedBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.upsert(newMemberEntity)
        }
        dispatchControlOperation(operationId, localIdentityId)

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
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

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

        // Notify remaining active peers and the removed member
        val removePayload = GroupMemberRemovePayload(
            groupId = groupId,
            targetIdentity = targetIdentityId,
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val removeBytes = GroupProtocolCodec.encodeRemove(removePayload)

        val recipients = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }
        val targets = recipients.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_MEMBER_REMOVE, removeBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateState(groupId, targetIdentityId, GroupMemberState.REMOVED.name, newEpoch, now)
        }
        dispatchControlOperation(operationId, localIdentityId)

        return true
    }

    /**
     * Voluntary leave by local user.
     */
    suspend fun leaveGroup(groupId: String): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        if (GroupMemberRole.fromString(selfMember.role) == GroupMemberRole.OWNER &&
            groupMemberDao.countActiveOwners(groupId) <= 1
        ) return false
        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

        val leavePayload = GroupMemberRemovePayload(
            groupId = groupId,
            targetIdentity = localIdentityId,
            actorIdentity = localIdentityId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
        val leaveBytes = GroupProtocolCodec.encodeRemove(leavePayload)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }

        val targets = activeMembers.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_MEMBER_REMOVE, leaveBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateState(groupId, localIdentityId, GroupMemberState.LEFT.name, newEpoch, now)
        }
        dispatchControlOperation(operationId, localIdentityId)

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
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        if (GroupMemberRole.fromString(selfMember.role) != GroupMemberRole.OWNER) {
            Log.w(TAG, "Only OWNER can change roles in group $groupId")
            return false
        }
        val targetMember = groupMemberDao.getMember(groupId, targetIdentityId) ?: return false
        if (GroupMemberRole.fromString(targetMember.role) == GroupMemberRole.OWNER &&
            newRole != GroupMemberRole.OWNER && groupMemberDao.countActiveOwners(groupId) <= 1
        ) {
            Log.w(TAG, "Cannot demote the group's last active OWNER")
            return false
        }

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

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

        val targets = activeMembers.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_ROLE_CHANGE, roleBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateEpoch(groupId, newEpoch, now)
            groupMemberDao.updateRole(groupId, targetIdentityId, newRole.name)
        }
        dispatchControlOperation(operationId, localIdentityId)

        return true
    }

    /**
     * Renames the group. Requires OWNER or ADMIN.
     */
    suspend fun updateGroupTitle(groupId: String, newTitle: String): Boolean {
        require(newTitle.isNotBlank()) { "Title cannot be blank" }
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) return false

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

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

        val targets = activeMembers.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_NAME_CHANGE, nameBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateTitle(groupId, newTitle.trim(), now)
            groupDao.updateEpoch(groupId, newEpoch, now)
            conversationDao.getById(groupId)?.let { conversationDao.upsert(it.copy(title = newTitle.trim())) }
        }
        dispatchControlOperation(operationId, localIdentityId)

        return true
    }

    /**
     * Updates the group avatar hash. Requires OWNER or ADMIN.
     */
    suspend fun updateGroupAvatar(groupId: String, avatarHash: String?): Boolean {
        val localIdentityId = localIdentityIdProvider() ?: return false
        val group = groupDao.getById(groupId) ?: return false
        if (groupControlDao.countPendingOperations(groupId) > 0) return false

        val selfMember = groupMemberDao.getMember(groupId, localIdentityId) ?: return false
        val selfRole = GroupMemberRole.fromString(selfMember.role)
        if (selfRole != GroupMemberRole.OWNER && selfRole != GroupMemberRole.ADMIN) return false

        val previousEpoch = group.epoch
        val newEpoch = previousEpoch + 1
        val now = System.currentTimeMillis()

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

        val targets = activeMembers.map { member ->
            ControlTarget(member.memberIdentityId, member.relationshipId, MessageType.GROUP_AVATAR_CHANGE, avatarBytes)
        }
        val operationId = persistControlTransition(groupId, previousEpoch, newEpoch, targets) {
            groupDao.updateAvatar(groupId, avatarHash, now)
            groupDao.updateEpoch(groupId, newEpoch, now)
            conversationDao.getById(groupId)?.let { conversationDao.upsert(it.copy(avatarHash = avatarHash)) }
        }
        dispatchControlOperation(operationId, localIdentityId)

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

        // Snapshot unread messages before updating their read state so reopening the group
        // does not resend receipts for historical messages.
        val unreadIncoming = messageDao.getUnreadIncoming(groupId)
            .filter { it.senderId != localIdentityId }
        val latestBySender = unreadIncoming.groupBy { it.senderId }
            .mapValues { (_, msgs) -> msgs.maxByOrNull { it.createdAt } }

        // 1. Mark local Room messages as READ
        messageDao.markAllIncomingRead(groupId, DeliveryStatus.READ.name, now)
        conversationDao.updateUnreadCount(groupId, 0)
        conversationDao.updateManuallyUnread(groupId, false)

        // 2. Find unread incoming messages and dispatch READ receipt back to each author
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
                    expectsAck = true
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
        recoverPendingControlOperations(localIdentityId)
        val pendingDeliveries = groupMessageDeliveryDao.getPendingDeliveries()
        if (pendingDeliveries.isNotEmpty()) {
            Log.i(TAG, "[RECOVERY] Resuming ${pendingDeliveries.size} pending group message deliveries")
        }

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

    private data class ControlTarget(
        val recipientIdentityId: String,
        val relationshipId: String,
        val messageType: MessageType,
        val payload: ByteArray
    )

    private suspend fun persistControlTransition(
        groupId: String,
        previousEpoch: Long,
        newEpoch: Long,
        targets: List<ControlTarget>,
        mutation: suspend () -> Unit
    ): String {
        require(groupControlDao.countPendingOperations(groupId) == 0) {
            "Group $groupId has an unfinished control operation; recover it before advancing the epoch"
        }
        require(targets.map { it.recipientIdentityId }.distinct().size == targets.size) {
            "A control transition cannot contain duplicate recipients"
        }
        targets.forEach {
            require(it.relationshipId.isNotBlank() && it.relationshipId != "self") {
                "No pairwise relationship for group member ${it.recipientIdentityId}"
            }
        }
        val operationId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val operation = GroupControlOperationEntity(
            operationId = operationId,
            groupId = groupId,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch,
            createdAt = now,
            updatedAt = now
        )
        val deliveries = targets.map {
            GroupControlDeliveryEntity(
                operationId = operationId,
                recipientIdentityId = it.recipientIdentityId,
                relationshipId = it.relationshipId,
                messageType = it.messageType.name,
                payload = it.payload,
                envelopeEpoch = newEpoch,
                updatedAt = now
            )
        }
        transactionRunner {
            groupControlDao.insertOperation(operation)
            if (deliveries.isNotEmpty()) groupControlDao.insertDeliveries(deliveries)
            mutation()
            if (deliveries.isEmpty()) groupControlDao.markOperationQueued(operationId, now)
        }
        return operationId
    }

    private suspend fun recoverPendingControlOperations(localIdentityId: String) {
        val blockedGroups = mutableSetOf<String>()
        for (operation in groupControlDao.getPendingOperations()) {
            if (operation.groupId in blockedGroups) continue
            dispatchControlOperation(operation.operationId, localIdentityId)
            if (groupControlDao.countPendingDeliveries(operation.operationId) > 0) {
                blockedGroups += operation.groupId
            }
        }
    }

    private suspend fun dispatchControlOperation(operationId: String, localIdentityId: String) {
        val operation = groupControlDao.getOperation(operationId) ?: return
        for (delivery in groupControlDao.getPendingDeliveries(operationId)) {
            try {
                val logicalId = UUID.nameUUIDFromBytes(
                    "torx-control:$operationId:${delivery.recipientIdentityId}".toByteArray(Charsets.UTF_8)
                ).toString()
                fanoutControlEnvelope(
                    groupId = operation.groupId,
                    recipientIdentityId = delivery.recipientIdentityId,
                    relationshipId = delivery.relationshipId,
                    localIdentityId = localIdentityId,
                    messageType = MessageType.valueOf(delivery.messageType),
                    payload = delivery.payload,
                    epoch = delivery.envelopeEpoch,
                    logicalMessageId = logicalId,
                    onPersisted = { outboxId ->
                        groupControlDao.markDeliveryQueued(
                            operationId, delivery.recipientIdentityId, outboxId, System.currentTimeMillis()
                        )
                    }
                )
            } catch (e: Exception) {
                groupControlDao.markDeliveryFailed(
                    operationId, delivery.recipientIdentityId, e.message ?: e.javaClass.simpleName, System.currentTimeMillis()
                )
                Log.e(TAG, "Control fan-out remains pending for ${delivery.recipientIdentityId}: ${e.message}")
            }
        }
        if (groupControlDao.countPendingDeliveries(operationId) == 0) {
            groupControlDao.markOperationQueued(operationId, System.currentTimeMillis())
        }
    }

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

        val logicalMessage = messageDao.getById(messageId)
        if (logicalMessage?.expiresAt != null && com.torxone.app.privacy.DisappearingPolicy.expired(logicalMessage.expiresAt, System.currentTimeMillis())) {
            groupMessageDeliveryDao.upsert(GroupMessageDeliveryEntity(deliveryId, messageId,
                recipientIdentityId, relationshipId, status = GroupDeliveryStatus.EXPIRED.name))
            return // No sequence was allocated for this recipient.
        }

        val outboxDeliveryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        sendCoordinator.sendSequenced(
            relationshipId = relationshipId,
            connection = connection,
            buildEnvelope = { seq ->
                SecureEnvelope(
                    protocolVersion = 1,
                    logicalMessageId = messageId,
                    conversationId = groupId,
                    senderIdentity = localIdentityId,
                    recipientBinding = recipientIdentityId,
                    messageType = messageType,
                    timestamp = logicalMessage?.createdAt ?: now,
                    payload = payload,
                    replyToMessageId = replyToMessageId,
                    groupMetadata = GroupEnvelopeMetadata(
                        groupId = groupId,
                        groupEpoch = epoch.toInt(),
                        keyVersion = epoch.toInt()
                    ),
                    directionSequence = seq,
                    expiresAt = logicalMessage?.expiresAt
                )
            },
            persistDomain = { seq, _, ciphertext ->
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
                    expectsAck = true,
                    applicationSequence = seq,
                    relationshipId = connection.relationshipId
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
                        updatedAt = now
                    )
                )
            }
        )
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
        expectsAck: Boolean = true,
        logicalMessageId: String = UUID.randomUUID().toString(),
        onPersisted: suspend (outboxDeliveryId: String) -> Unit = {}
    ) {
        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val outboxDeliveryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        sendCoordinator.sendSequenced(
            relationshipId = relationshipId,
            connection = connection,
            buildEnvelope = { seq ->
                SecureEnvelope(
                    protocolVersion = 1,
                    logicalMessageId = logicalMessageId,
                    conversationId = groupId,
                    senderIdentity = localIdentityId,
                    recipientBinding = recipientIdentityId,
                    messageType = messageType,
                    timestamp = now,
                    payload = payload,
                    groupMetadata = GroupEnvelopeMetadata(
                        groupId = groupId,
                        groupEpoch = epoch.toInt(),
                        keyVersion = epoch.toInt()
                    ),
                    directionSequence = seq
                )
            },
            persistDomain = { seq, envelope, ciphertext ->
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
                    expectsAck = expectsAck,
                    applicationSequence = seq,
                    relationshipId = connection.relationshipId
                )
                outboxDao.insert(outboxItem)
                onPersisted(outboxDeliveryId)
            }
        )
    }
}
