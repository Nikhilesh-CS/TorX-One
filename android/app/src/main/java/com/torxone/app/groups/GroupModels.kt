package com.torxone.app.groups

import com.torxone.app.data.entity.GroupDeliveryStatus
import com.torxone.app.data.entity.GroupMemberRole
import com.torxone.app.data.entity.GroupMemberState

/**
 * Snapshot of a group member included in group invites and rosters.
 */
data class GroupMemberSnapshot(
    val identityId: String,
    val contactId: String,
    val role: GroupMemberRole = GroupMemberRole.MEMBER,
    val state: GroupMemberState = GroupMemberState.ACTIVE
)

/**
 * Payload sent inside a pairwise Double Ratchet envelope for GROUP_CREATE or GROUP_MEMBER_INVITE.
 */
data class GroupInvitePayload(
    val groupId: String,
    val title: String,
    val avatarHash: String? = null,
    val creatorIdentity: String,
    val epoch: Long,
    val inviterIdentity: String,
    val members: List<GroupMemberSnapshot>
)

/**
 * Payload sent for GROUP_MEMBER_ACCEPT when an invited member joins.
 */
data class GroupMemberJoinedPayload(
    val groupId: String,
    val memberIdentity: String,
    val role: GroupMemberRole,
    val epoch: Long,
    val actorIdentity: String
)

/**
 * Payload sent for GROUP_MEMBER_REMOVE when an owner/admin removes a member.
 */
data class GroupMemberRemovePayload(
    val groupId: String,
    val targetIdentity: String,
    val actorIdentity: String,
    val previousEpoch: Long,
    val newEpoch: Long
)

/**
 * Payload sent when a member leaves the group voluntarily.
 */
data class GroupMemberLeavePayload(
    val groupId: String,
    val memberIdentity: String,
    val previousEpoch: Long,
    val newEpoch: Long
)

/**
 * Payload sent for GROUP_ROLE_CHANGE (e.g. promoting member to admin).
 */
data class GroupRoleChangePayload(
    val groupId: String,
    val targetIdentity: String,
    val newRole: GroupMemberRole,
    val actorIdentity: String,
    val previousEpoch: Long,
    val newEpoch: Long
)

/**
 * Payload sent for GROUP_NAME_CHANGE.
 */
data class GroupNameChangePayload(
    val groupId: String,
    val newTitle: String,
    val actorIdentity: String,
    val previousEpoch: Long,
    val newEpoch: Long
)

/**
 * Payload sent for GROUP_AVATAR_CHANGE.
 */
data class GroupAvatarChangePayload(
    val groupId: String,
    val newAvatarHash: String?,
    val actorIdentity: String,
    val previousEpoch: Long,
    val newEpoch: Long
)

/**
 * Aggregate delivery progress for a group message.
 */
data class GroupMessageDeliverySummary(
    val logicalMessageId: String,
    val totalRecipients: Int,
    val deliveredCount: Int,
    val readCount: Int,
    val overallStatus: GroupDeliveryStatus,
    val details: List<GroupRecipientDeliveryState>
)

/**
 * Detailed per-recipient status for message info screen.
 */
data class GroupRecipientDeliveryState(
    val recipientIdentityId: String,
    val recipientName: String,
    val status: GroupDeliveryStatus,
    val deliveredAt: Long?,
    val readAt: Long?
)
