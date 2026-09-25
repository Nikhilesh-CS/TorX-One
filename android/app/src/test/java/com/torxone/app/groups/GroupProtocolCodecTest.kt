package com.torxone.app.groups

import com.torxone.app.data.entity.GroupMemberRole
import com.torxone.app.data.entity.GroupMemberState
import org.junit.Assert.*
import org.junit.Test

class GroupProtocolCodecTest {

    @Test
    fun testInviteCodecRoundTrip() {
        val members = listOf(
            GroupMemberSnapshot("id_alice", "contact_alice", GroupMemberRole.OWNER, GroupMemberState.ACTIVE),
            GroupMemberSnapshot("id_bob", "contact_bob", GroupMemberRole.ADMIN, GroupMemberState.ACTIVE),
            GroupMemberSnapshot("id_charlie", "contact_charlie", GroupMemberRole.MEMBER, GroupMemberState.ACTIVE)
        )
        val invite = GroupInvitePayload(
            groupId = "group_123",
            title = "RoboTech Club",
            avatarHash = "hash_xyz",
            creatorIdentity = "id_alice",
            epoch = 1L,
            inviterIdentity = "id_alice",
            members = members
        )

        val encoded = GroupProtocolCodec.encodeInvite(invite)
        val decoded = GroupProtocolCodec.decodeInvite(encoded)

        assertEquals("group_123", decoded.groupId)
        assertEquals("RoboTech Club", decoded.title)
        assertEquals("hash_xyz", decoded.avatarHash)
        assertEquals("id_alice", decoded.creatorIdentity)
        assertEquals(1L, decoded.epoch)
        assertEquals("id_alice", decoded.inviterIdentity)
        assertEquals(3, decoded.members.size)
        assertEquals(GroupMemberRole.OWNER, decoded.members[0].role)
        assertEquals(GroupMemberRole.ADMIN, decoded.members[1].role)
        assertEquals(GroupMemberRole.MEMBER, decoded.members[2].role)
    }

    @Test
    fun testMemberJoinedCodecRoundTrip() {
        val joined = GroupMemberJoinedPayload(
            groupId = "group_123",
            memberIdentity = "id_bob",
            role = GroupMemberRole.MEMBER,
            epoch = 2L,
            actorIdentity = "id_bob"
        )
        val bytes = GroupProtocolCodec.encodeJoined(joined)
        val decoded = GroupProtocolCodec.decodeJoined(bytes)

        assertEquals("group_123", decoded.groupId)
        assertEquals("id_bob", decoded.memberIdentity)
        assertEquals(GroupMemberRole.MEMBER, decoded.role)
        assertEquals(2L, decoded.epoch)
        assertEquals("id_bob", decoded.actorIdentity)
    }

    @Test
    fun testMemberRemoveCodecRoundTrip() {
        val remove = GroupMemberRemovePayload(
            groupId = "group_123",
            targetIdentity = "id_charlie",
            actorIdentity = "id_alice",
            previousEpoch = 2L,
            newEpoch = 3L
        )
        val bytes = GroupProtocolCodec.encodeRemove(remove)
        val decoded = GroupProtocolCodec.decodeRemove(bytes)

        assertEquals("group_123", decoded.groupId)
        assertEquals("id_charlie", decoded.targetIdentity)
        assertEquals("id_alice", decoded.actorIdentity)
        assertEquals(2L, decoded.previousEpoch)
        assertEquals(3L, decoded.newEpoch)
    }

    @Test
    fun testMemberLeaveCodecRoundTrip() {
        val leave = GroupMemberLeavePayload(
            groupId = "group_123",
            memberIdentity = "id_bob",
            previousEpoch = 3L,
            newEpoch = 4L
        )
        val bytes = GroupProtocolCodec.encodeLeave(leave)
        val decoded = GroupProtocolCodec.decodeLeave(bytes)

        assertEquals("group_123", decoded.groupId)
        assertEquals("id_bob", decoded.memberIdentity)
        assertEquals(3L, decoded.previousEpoch)
        assertEquals(4L, decoded.newEpoch)
    }

    @Test
    fun testRoleChangeCodecRoundTrip() {
        val roleChange = GroupRoleChangePayload(
            groupId = "group_123",
            targetIdentity = "id_bob",
            newRole = GroupMemberRole.ADMIN,
            actorIdentity = "id_alice",
            previousEpoch = 4L,
            newEpoch = 5L
        )
        val bytes = GroupProtocolCodec.encodeRoleChange(roleChange)
        val decoded = GroupProtocolCodec.decodeRoleChange(bytes)

        assertEquals("group_123", decoded.groupId)
        assertEquals("id_bob", decoded.targetIdentity)
        assertEquals(GroupMemberRole.ADMIN, decoded.newRole)
        assertEquals("id_alice", decoded.actorIdentity)
        assertEquals(4L, decoded.previousEpoch)
        assertEquals(5L, decoded.newEpoch)
    }

    @Test
    fun testNameAndAvatarChangeCodecRoundTrip() {
        val nameChange = GroupNameChangePayload("group_123", "New Name", "id_alice", 5L, 6L)
        val decodedName = GroupProtocolCodec.decodeNameChange(GroupProtocolCodec.encodeNameChange(nameChange))
        assertEquals("New Name", decodedName.newTitle)
        assertEquals(6L, decodedName.newEpoch)

        val avatarChange = GroupAvatarChangePayload("group_123", "avatar_abc", "id_alice", 6L, 7L)
        val decodedAvatar = GroupProtocolCodec.decodeAvatarChange(GroupProtocolCodec.encodeAvatarChange(avatarChange))
        assertEquals("avatar_abc", decodedAvatar.newAvatarHash)
        assertEquals(7L, decodedAvatar.newEpoch)
    }
}
