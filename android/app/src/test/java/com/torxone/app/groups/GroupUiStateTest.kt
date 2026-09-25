package com.torxone.app.groups

import com.torxone.app.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class GroupUiStateTest {

    private lateinit var groupDao: TestGroupDao
    private lateinit var groupMemberDao: TestGroupMemberDao
    private lateinit var messageDao: TestMessageDao
    private lateinit var reactionDao: TestReactionDao
    private lateinit var contactDao: TestContactDao
    private lateinit var conversationDao: TestConversationDao
    private lateinit var groupService: GroupService
    private val localIdentityId = "identity-self"
    private val groupId = "group-123"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        groupDao = TestGroupDao()
        groupMemberDao = TestGroupMemberDao()
        messageDao = TestMessageDao()
        reactionDao = TestReactionDao()
        contactDao = TestContactDao()
        conversationDao = TestConversationDao()

        val outboxStore = TestOutboxStore()
        val outboxDao = TestOutboxDao(outboxStore)
        val processedStore = TestProcessedStore()
        val sessionStore = TestSessionStore()
        val sessionCrypto = com.torxone.app.crypto.DoubleRatchetSessionCrypto(sessionStore)
        val transportRouter = com.torxone.app.transport.TransportRouter()
        val agent = com.torxone.app.agent.TorXAgent(
            transportRouter = transportRouter,
            outboxStore = outboxStore,
            processedStore = processedStore
        )

        // Create a mock groupService (delegates to DAOs)
        groupService = GroupService(
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            groupMessageDeliveryDao = TestGroupMessageDeliveryDao(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            outboxDao = outboxDao,
            connectionManager = com.torxone.app.connection.ConnectionManager(),
            sessionCrypto = sessionCrypto,
            agent = agent,
            localIdentityIdProvider = { localIdentityId },
            transactionRunner = { it() }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `group state initializes with group title, active participant count, and active status`() = runTest {
        val now = System.currentTimeMillis()
        groupDao.upsert(
            GroupEntity(
                groupId = groupId,
                conversationId = groupId,
                title = "Alpha Squad",
                avatarHash = null,
                creatorIdentityId = localIdentityId,
                epoch = 1L,
                createdAt = now,
                updatedAt = now
            )
        )
        groupMemberDao.upsert(
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
        groupMemberDao.upsert(
            GroupMemberEntity(
                groupId = groupId,
                memberIdentityId = "contact-alice",
                contactId = "contact-alice",
                relationshipId = "rel-alice",
                role = GroupMemberRole.MEMBER.name,
                state = GroupMemberState.ACTIVE.name,
                joinedEpoch = 1L,
                joinedAt = now
            )
        )

        val viewModel = GroupChatViewModel(
            groupId = groupId,
            conversationId = groupId,
            localIdentityId = localIdentityId,
            groupService = groupService,
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            conversationDao = conversationDao
        )

        val state = viewModel.uiState.first()
        assertEquals("Alpha Squad", state.title)
        assertTrue(state.isGroup)
        assertTrue(state.isParticipantActive)
        assertEquals("2 participants", state.subtitle)
    }

    @Test
    fun `removed member has isParticipantActive false and warning subtitle`() = runTest {
        val now = System.currentTimeMillis()
        groupDao.upsert(
            GroupEntity(
                groupId = groupId,
                conversationId = groupId,
                title = "Bravo Team",
                creatorIdentityId = "other",
                epoch = 2L,
                createdAt = now,
                updatedAt = now
            )
        )
        groupMemberDao.upsert(
            GroupMemberEntity(
                groupId = groupId,
                memberIdentityId = localIdentityId,
                contactId = "self",
                relationshipId = "self",
                role = GroupMemberRole.MEMBER.name,
                state = GroupMemberState.REMOVED.name,
                joinedEpoch = 1L,
                joinedAt = now,
                removedEpoch = 2L,
                removedAt = now
            )
        )

        val viewModel = GroupChatViewModel(
            groupId = groupId,
            conversationId = groupId,
            localIdentityId = localIdentityId,
            groupService = groupService,
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            conversationDao = conversationDao
        )

        val state = viewModel.uiState.first()
        assertFalse(state.isParticipantActive)
        assertEquals("You can't send messages because you're no longer a participant.", state.subtitle)
    }

    @Test
    fun `incoming group messages resolve sender display name from contact store`() = runTest {
        val now = System.currentTimeMillis()
        contactDao.upsert(
            ContactEntity(
                contactId = "contact-alice",
                displayName = "Alice Wonderland",
                relationshipId = "rel-alice",
                conversationId = "conv-alice",
                signingPublicKey = ByteArray(32)
            )
        )
        groupDao.upsert(
            GroupEntity(
                groupId = groupId,
                conversationId = groupId,
                title = "Delta Team",
                creatorIdentityId = localIdentityId,
                epoch = 1L,
                createdAt = now,
                updatedAt = now
            )
        )
        groupMemberDao.upsert(
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

        // Incoming message from Alice
        messageDao.upsert(
            MessageEntity(
                logicalMessageId = "msg-1",
                conversationId = groupId,
                senderId = "contact-alice",
                type = "TEXT",
                body = "Hello group!",
                direction = MessageDirection.INCOMING,
                status = "DELIVERED",
                createdAt = now
            )
        )

        // Outgoing message from local user
        messageDao.upsert(
            MessageEntity(
                logicalMessageId = "msg-2",
                conversationId = groupId,
                senderId = localIdentityId,
                type = "TEXT",
                body = "Hi Alice!",
                direction = MessageDirection.OUTGOING,
                status = "READ",
                createdAt = now + 1000
            )
        )

        val viewModel = GroupChatViewModel(
            groupId = groupId,
            conversationId = groupId,
            localIdentityId = localIdentityId,
            groupService = groupService,
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            conversationDao = conversationDao
        )

        val state = viewModel.uiState.first()
        assertEquals(2, state.messages.size)

        val aliceMsg = state.messages.find { it.logicalMessageId == "msg-1" }!!
        assertEquals("Alice Wonderland", aliceMsg.senderDisplayName)
        assertEquals(MessageDirection.INCOMING, aliceMsg.direction)

        val myMsg = state.messages.find { it.logicalMessageId == "msg-2" }!!
        assertNull(myMsg.senderDisplayName)
        assertEquals(MessageDirection.OUTGOING, myMsg.direction)
    }

    @Test
    fun `typing indicators aggregate single, pair, and multiple members cleanly`() = runTest {
        contactDao.upsert(ContactEntity(contactId = "user-1", relationshipId = "r1", displayName = "Alice", conversationId = "c1", signingPublicKey = ByteArray(32)))
        contactDao.upsert(ContactEntity(contactId = "user-2", relationshipId = "r2", displayName = "Bob", conversationId = "c2", signingPublicKey = ByteArray(32)))
        contactDao.upsert(ContactEntity(contactId = "user-3", relationshipId = "r3", displayName = "Charlie", conversationId = "c3", signingPublicKey = ByteArray(32)))

        val viewModel = GroupChatViewModel(
            groupId = groupId,
            conversationId = groupId,
            localIdentityId = localIdentityId,
            groupService = groupService,
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            conversationDao = conversationDao
        )

        // Single typer
        viewModel.onPeerTypingReceived("user-1")
        var state = viewModel.uiState.first()
        assertEquals("Alice is typing…", state.typingText)

        // Two typers
        viewModel.onPeerTypingReceived("user-2")
        state = viewModel.uiState.first()
        assertTrue(state.typingText == "Alice and Bob are typing…" || state.typingText == "Bob and Alice are typing…")

        // Three typers
        viewModel.onPeerTypingReceived("user-3")
        state = viewModel.uiState.first()
        assertEquals("Several people are typing…", state.typingText)
    }
}
