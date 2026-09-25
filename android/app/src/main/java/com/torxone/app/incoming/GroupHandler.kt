package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.connection.Connection
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.GroupDao
import com.torxone.app.data.dao.GroupMemberDao
import com.torxone.app.data.entity.*
import com.torxone.app.groups.GroupProtocolCodec
import com.torxone.app.notifications.TorXNotificationManager
import com.torxone.app.protocol.SecureEnvelope

/**
 * Incoming dispatcher handler for group control protocol envelopes:
 * GROUP_CREATE, GROUP_MEMBER_INVITE, GROUP_MEMBER_ACCEPT, GROUP_MEMBER_REMOVE,
 * GROUP_ROLE_CHANGE, GROUP_NAME_CHANGE, GROUP_AVATAR_CHANGE.
 */
class GroupHandler(
    private val groupDao: GroupDao,
    private val groupMemberDao: GroupMemberDao,
    private val conversationDao: ConversationDao,
    private val localIdentityIdProvider: suspend () -> String?,
    private val notificationManager: TorXNotificationManager? = null,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit
) {
    companion object {
        private const val TAG = "GroupHandler"
    }

    /**
     * Handles incoming GROUP_CREATE or GROUP_MEMBER_INVITE envelope.
     */
    suspend fun handleGroupCreateOrInvite(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeInvite(envelope.payload)
            val localIdentityId = localIdentityIdProvider() ?: return false
            val now = System.currentTimeMillis()

            Log.i(TAG, "[GROUP INVITE] Received invite to ${payload.title} (${payload.groupId.take(8)}) from ${envelope.senderIdentity.take(8)}")

            val conversation = ConversationEntity(
                conversationId = payload.groupId,
                type = ConversationType.GROUP,
                title = payload.title,
                avatarHash = payload.avatarHash,
                createdAt = now
            )

            val group = GroupEntity(
                groupId = payload.groupId,
                conversationId = payload.groupId,
                title = payload.title,
                avatarHash = payload.avatarHash,
                creatorIdentityId = payload.creatorIdentity,
                epoch = payload.epoch,
                createdAt = now,
                updatedAt = now
            )

            val memberEntities = payload.members.map { m ->
                val (contactId, relationshipId) = when {
                    m.identityId == localIdentityId -> "self" to "self"
                    m.identityId == envelope.senderIdentity -> connection.relationshipId to connection.relationshipId
                    else -> m.contactId.ifEmpty { m.identityId } to m.contactId.ifEmpty { m.identityId }
                }
                GroupMemberEntity(
                    groupId = payload.groupId,
                    memberIdentityId = m.identityId,
                    contactId = contactId,
                    relationshipId = relationshipId,
                    role = m.role.name,
                    state = m.state.name,
                    joinedEpoch = payload.epoch,
                    joinedAt = now
                )
            }

            transactionRunner {
                conversationDao.upsert(conversation)
                groupDao.upsert(group)
                groupMemberDao.upsertAll(memberEntities)
            }

            notificationManager?.handleIncomingTextMessage(
                conversationId = payload.groupId,
                messageId = envelope.logicalMessageId,
                senderId = envelope.senderIdentity,
                text = "Added you to group: ${payload.title}",
                timestamp = envelope.timestamp
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle group create/invite: ${e.message}")
            false
        }
    }

    /**
     * Handles incoming GROUP_MEMBER_ACCEPT when an invited peer joins.
     */
    suspend fun handleMemberJoined(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeJoined(envelope.payload)
            val group = groupDao.getById(payload.groupId) ?: return false
            val now = System.currentTimeMillis()

            val memberEntity = GroupMemberEntity(
                groupId = payload.groupId,
                memberIdentityId = payload.memberIdentity,
                contactId = if (payload.memberIdentity == envelope.senderIdentity) connection.relationshipId else payload.memberIdentity,
                relationshipId = if (payload.memberIdentity == envelope.senderIdentity) connection.relationshipId else payload.memberIdentity,
                role = payload.role.name,
                state = GroupMemberState.ACTIVE.name,
                joinedEpoch = payload.epoch,
                joinedAt = now
            )

            transactionRunner {
                if (payload.epoch > group.epoch) {
                    groupDao.updateEpoch(payload.groupId, payload.epoch, now)
                }
                groupMemberDao.upsert(memberEntity)
            }

            Log.i(TAG, "[GROUP JOINED] Member ${payload.memberIdentity.take(8)} joined ${payload.groupId.take(8)} at epoch ${payload.epoch}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle member joined: ${e.message}")
            false
        }
    }

    /**
     * Handles incoming GROUP_MEMBER_REMOVE when an owner/admin removes a member or a member leaves.
     */
    suspend fun handleMemberRemove(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeRemove(envelope.payload)
            val group = groupDao.getById(payload.groupId) ?: return false

            // Authorization check
            val actor = groupMemberDao.getMember(payload.groupId, payload.actorIdentity)
            val actorRole = if (actor != null) GroupMemberRole.fromString(actor.role) else null

            // If actor is removing self (leave), or actor is OWNER/ADMIN removing a member
            val isSelfLeave = payload.actorIdentity == payload.targetIdentity
            val isAuthorizedAdmin = actorRole == GroupMemberRole.OWNER || actorRole == GroupMemberRole.ADMIN

            val target = groupMemberDao.getMember(payload.groupId, payload.targetIdentity)
            val targetRole = if (target != null) GroupMemberRole.fromString(target.role) else null

            if (!isSelfLeave) {
                if (!isAuthorizedAdmin) {
                    Log.w(TAG, "Rejecting unauthorized member remove by non-admin ${payload.actorIdentity.take(8)}")
                    return false
                }
                // Nobody removes OWNER
                if (targetRole == GroupMemberRole.OWNER) {
                    Log.w(TAG, "Rejecting remove: target is OWNER")
                    return false
                }
                // Admin cannot remove Admin
                if (actorRole == GroupMemberRole.ADMIN && targetRole == GroupMemberRole.ADMIN) {
                    Log.w(TAG, "Rejecting remove: admin cannot remove admin")
                    return false
                }
            }

            val now = System.currentTimeMillis()
            val newState = if (isSelfLeave) GroupMemberState.LEFT.name else GroupMemberState.REMOVED.name

            transactionRunner {
                if (payload.newEpoch > group.epoch) {
                    groupDao.updateEpoch(payload.groupId, payload.newEpoch, now)
                }
                groupMemberDao.updateState(
                    groupId = payload.groupId,
                    memberIdentityId = payload.targetIdentity,
                    state = newState,
                    removedEpoch = payload.newEpoch,
                    removedAt = now
                )
            }

            Log.i(TAG, "[GROUP REMOVE] Target ${payload.targetIdentity.take(8)} marked $newState by ${payload.actorIdentity.take(8)} at epoch ${payload.newEpoch}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle member remove: ${e.message}")
            false
        }
    }

    /**
     * Handles incoming GROUP_ROLE_CHANGE.
     */
    suspend fun handleRoleChange(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeRoleChange(envelope.payload)
            val group = groupDao.getById(payload.groupId) ?: return false

            // Authorization: actor must be OWNER in local state
            val actor = groupMemberDao.getMember(payload.groupId, payload.actorIdentity)
            if (actor == null || GroupMemberRole.fromString(actor.role) != GroupMemberRole.OWNER) {
                Log.w(TAG, "Rejecting role change: actor is not OWNER")
                return false
            }

            val now = System.currentTimeMillis()
            transactionRunner {
                if (payload.newEpoch > group.epoch) {
                    groupDao.updateEpoch(payload.groupId, payload.newEpoch, now)
                }
                groupMemberDao.updateRole(payload.groupId, payload.targetIdentity, payload.newRole.name)
            }

            Log.i(TAG, "[GROUP ROLE] Changed ${payload.targetIdentity.take(8)} to ${payload.newRole} at epoch ${payload.newEpoch}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle role change: ${e.message}")
            false
        }
    }

    /**
     * Handles incoming GROUP_NAME_CHANGE.
     */
    suspend fun handleNameChange(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeNameChange(envelope.payload)
            val group = groupDao.getById(payload.groupId) ?: return false

            val actor = groupMemberDao.getMember(payload.groupId, payload.actorIdentity)
            val role = if (actor != null) GroupMemberRole.fromString(actor.role) else null
            if (role != GroupMemberRole.OWNER && role != GroupMemberRole.ADMIN) {
                Log.w(TAG, "Rejecting name change: actor is not admin/owner")
                return false
            }

            val now = System.currentTimeMillis()
            transactionRunner {
                if (payload.newEpoch > group.epoch) {
                    groupDao.updateEpoch(payload.groupId, payload.newEpoch, now)
                }
                groupDao.updateTitle(payload.groupId, payload.newTitle, now)
                conversationDao.getById(payload.groupId)?.let { conv ->
                    conversationDao.upsert(conv.copy(title = payload.newTitle))
                }
            }

            Log.i(TAG, "[GROUP NAME] Updated title to '${payload.newTitle}' at epoch ${payload.newEpoch}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle name change: ${e.message}")
            false
        }
    }

    /**
     * Handles incoming GROUP_AVATAR_CHANGE.
     */
    suspend fun handleAvatarChange(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return try {
            val payload = GroupProtocolCodec.decodeAvatarChange(envelope.payload)
            val group = groupDao.getById(payload.groupId) ?: return false

            val actor = groupMemberDao.getMember(payload.groupId, payload.actorIdentity)
            val role = if (actor != null) GroupMemberRole.fromString(actor.role) else null
            if (role != GroupMemberRole.OWNER && role != GroupMemberRole.ADMIN) {
                Log.w(TAG, "Rejecting avatar change: actor is not admin/owner")
                return false
            }

            val now = System.currentTimeMillis()
            transactionRunner {
                if (payload.newEpoch > group.epoch) {
                    groupDao.updateEpoch(payload.groupId, payload.newEpoch, now)
                }
                groupDao.updateAvatar(payload.groupId, payload.newAvatarHash, now)
                conversationDao.getById(payload.groupId)?.let { conv ->
                    conversationDao.upsert(conv.copy(avatarHash = payload.newAvatarHash))
                }
            }

            Log.i(TAG, "[GROUP AVATAR] Updated avatar at epoch ${payload.newEpoch}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle avatar change: ${e.message}")
            false
        }
    }
}
