package com.torxone.app.incoming

import com.torxone.app.connection.Connection
import com.torxone.app.data.entity.*
import com.torxone.app.groups.*
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class GroupHandlerTest {

    private val groupDao = TestGroupDao()
    private val groupMemberDao = TestGroupMemberDao()
    private val conversationDao = TestConversationDao()
    private val localIdentityId = "alice_id"
    private val bobIdentityId = "bob_id"
    private val charlieIdentityId = "charlie_id"

    private lateinit var groupHandler: GroupHandler
    private lateinit var connectionBob: Connection

    @Before
    fun setUp() {
        groupHandler = GroupHandler(
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            conversationDao = conversationDao,
            localIdentityIdProvider = { localIdentityId },
            notificationManager = null,
            transactionRunner = { it() }
        )

        connectionBob = Connection(
            connectionId = "conn_bob",
            relationshipId = "rel_bob",
            generation = 1,
            sendQueueId = "q_send",
            recvQueueId = "q_recv",
            sendAuth = ByteArray(32),
            recvAuth = ByteArray(32)
        )
    }

    @Test
    fun testHandleGroupCreateOrInviteCreatesEntities() = runBlocking {
        val groupId = "group_alpha"
        val invite = GroupInvitePayload(
            groupId = groupId,
            title = "Alpha Team",
            avatarHash = "hash_123",
            creatorIdentity = bobIdentityId,
            epoch = 1L,
            inviterIdentity = bobIdentityId,
            members = listOf(
                GroupMemberSnapshot(bobIdentityId, "contact_bob", GroupMemberRole.OWNER, GroupMemberState.ACTIVE),
                GroupMemberSnapshot(localIdentityId, "contact_alice", GroupMemberRole.MEMBER, GroupMemberState.ACTIVE)
            )
        )
        val envelope = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = bobIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_CREATE,
            payload = GroupProtocolCodec.encodeInvite(invite)
        )

        val handled = groupHandler.handleGroupCreateOrInvite(connectionBob, envelope)
        assertTrue(handled)

        // Verifies group stored
        val group = groupDao.getById(groupId)
        assertNotNull(group)
        assertEquals("Alpha Team", group?.title)
        assertEquals(1L, group?.epoch)

        // Verifies conversation stored
        val conv = conversationDao.getById(groupId)
        assertNotNull(conv)
        assertEquals(ConversationType.GROUP, conv?.type)
        assertEquals("Alpha Team", conv?.title)

        // Verifies members stored
        val members = groupMemberDao.getMembers(groupId)
        assertEquals(2, members.size)
        val aliceMember = groupMemberDao.getMember(groupId, localIdentityId)
        assertNotNull(aliceMember)
        assertEquals("self", aliceMember?.relationshipId)
        assertEquals(GroupMemberRole.MEMBER.name, aliceMember?.role)
    }

    @Test
    fun testHandleMemberJoinedAdvancesEpoch() = runBlocking {
        val groupId = "group_alpha"
        groupDao.upsert(GroupEntity(groupId, groupId, "Alpha Team", null, bobIdentityId, 1L))

        val joined = GroupMemberJoinedPayload(
            groupId = groupId,
            memberIdentity = charlieIdentityId,
            role = GroupMemberRole.MEMBER,
            epoch = 2L,
            actorIdentity = bobIdentityId
        )
        val envelope = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = bobIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_MEMBER_ACCEPT,
            payload = GroupProtocolCodec.encodeJoined(joined)
        )

        val handled = groupHandler.handleMemberJoined(connectionBob, envelope)
        assertTrue(handled)

        assertEquals(2L, groupDao.getById(groupId)?.epoch)
        val charlieMember = groupMemberDao.getMember(groupId, charlieIdentityId)
        assertNotNull(charlieMember)
        assertEquals(GroupMemberRole.MEMBER.name, charlieMember?.role)
        assertEquals(GroupMemberState.ACTIVE.name, charlieMember?.state)
    }

    @Test
    fun testHandleMemberRemoveAuthorizedAdminSucceeds() = runBlocking {
        val groupId = "group_alpha"
        groupDao.upsert(GroupEntity(groupId, groupId, "Alpha Team", null, bobIdentityId, 2L))
        groupMemberDao.upsert(GroupMemberEntity(groupId, bobIdentityId, "c_bob", "r_bob", GroupMemberRole.OWNER.name, GroupMemberState.ACTIVE.name, 1L))
        groupMemberDao.upsert(GroupMemberEntity(groupId, charlieIdentityId, "c_charlie", "r_charlie", GroupMemberRole.MEMBER.name, GroupMemberState.ACTIVE.name, 1L))

        val removePayload = GroupMemberRemovePayload(
            groupId = groupId,
            targetIdentity = charlieIdentityId,
            actorIdentity = bobIdentityId, // Bob is OWNER
            previousEpoch = 2L,
            newEpoch = 3L
        )
        val envelope = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = bobIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_MEMBER_REMOVE,
            payload = GroupProtocolCodec.encodeRemove(removePayload)
        )

        val handled = groupHandler.handleMemberRemove(connectionBob, envelope)
        assertTrue(handled)

        assertEquals(3L, groupDao.getById(groupId)?.epoch)
        val charlie = groupMemberDao.getMember(groupId, charlieIdentityId)
        assertEquals(GroupMemberState.REMOVED.name, charlie?.state)
        assertEquals(3L, charlie?.removedEpoch)
    }

    @Test
    fun testHandleMemberRemoveNobodyRemovesOwner() = runBlocking {
        val groupId = "group_alpha"
        groupDao.upsert(GroupEntity(groupId, groupId, "Alpha Team", null, bobIdentityId, 2L))
        groupMemberDao.upsert(GroupMemberEntity(groupId, bobIdentityId, "c_bob", "r_bob", GroupMemberRole.OWNER.name, GroupMemberState.ACTIVE.name, 1L))
        groupMemberDao.upsert(GroupMemberEntity(groupId, charlieIdentityId, "c_charlie", "r_charlie", GroupMemberRole.ADMIN.name, GroupMemberState.ACTIVE.name, 1L))

        val removePayload = GroupMemberRemovePayload(
            groupId = groupId,
            targetIdentity = bobIdentityId, // Charlie trying to remove Bob (OWNER)
            actorIdentity = charlieIdentityId,
            previousEpoch = 2L,
            newEpoch = 3L
        )
        val envelope = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = charlieIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_MEMBER_REMOVE,
            payload = GroupProtocolCodec.encodeRemove(removePayload)
        )

        val handled = groupHandler.handleMemberRemove(connectionBob, envelope)
        assertFalse("Nobody removes OWNER must be rejected", handled)
    }

    @Test
    fun testHandleRoleAndNameChange() = runBlocking {
        val groupId = "group_alpha"
        groupDao.upsert(GroupEntity(groupId, groupId, "Alpha Team", null, bobIdentityId, 1L))
        conversationDao.upsert(ConversationEntity(groupId, ConversationType.GROUP, "Alpha Team"))
        groupMemberDao.upsert(GroupMemberEntity(groupId, bobIdentityId, "c_bob", "r_bob", GroupMemberRole.OWNER.name, GroupMemberState.ACTIVE.name, 1L))
        groupMemberDao.upsert(GroupMemberEntity(groupId, localIdentityId, "c_alice", "r_alice", GroupMemberRole.MEMBER.name, GroupMemberState.ACTIVE.name, 1L))

        // 1. Role Change: Bob promotes Alice to ADMIN
        val rolePayload = GroupRoleChangePayload(groupId, localIdentityId, GroupMemberRole.ADMIN, bobIdentityId, 1L, 2L)
        val roleEnv = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = bobIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_ROLE_CHANGE,
            payload = GroupProtocolCodec.encodeRoleChange(rolePayload)
        )
        assertTrue(groupHandler.handleRoleChange(connectionBob, roleEnv))
        assertEquals(2L, groupDao.getById(groupId)?.epoch)
        assertEquals(GroupMemberRole.ADMIN.name, groupMemberDao.getMember(groupId, localIdentityId)?.role)

        // 2. Name Change: Bob renames to "Alpha Force"
        val namePayload = GroupNameChangePayload(groupId, "Alpha Force", bobIdentityId, 2L, 3L)
        val nameEnv = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = groupId,
            senderIdentity = bobIdentityId,
            recipientBinding = localIdentityId,
            messageType = MessageType.GROUP_NAME_CHANGE,
            payload = GroupProtocolCodec.encodeNameChange(namePayload)
        )
        assertTrue(groupHandler.handleNameChange(connectionBob, nameEnv))
        assertEquals(3L, groupDao.getById(groupId)?.epoch)
        assertEquals("Alpha Force", groupDao.getById(groupId)?.title)
        assertEquals("Alpha Force", conversationDao.getById(groupId)?.title)
    }
}
