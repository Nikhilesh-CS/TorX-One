package com.torxone.app.groups

import com.torxone.app.agent.*
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.ReactionOperation
import com.torxone.app.relationship.RelationshipService
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class GroupServiceTest {

    // DAOs & Stores
    private val groupDao = TestGroupDao()
    private val groupMemberDao = TestGroupMemberDao()
    private val groupMessageDeliveryDao = TestGroupMessageDeliveryDao()
    private val conversationDao = TestConversationDao()
    private val messageDao = TestMessageDao()
    private val reactionDao = TestReactionDao()
    private val contactDao = TestContactDao()
    private val outboxStore = TestOutboxStore()
    private val outboxDao = TestOutboxDao(outboxStore)
    private val processedStore = TestProcessedStore()

    private val connectionManager = ConnectionManager()
    private val sessionStore = TestSessionStore()
    private val sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)
    private val transportRouter = TransportRouter()
    private val agent = TorXAgent(
        transportRouter = transportRouter,
        outboxStore = outboxStore,
        processedStore = processedStore,
        coroutineDispatcher = Dispatchers.Default
    )

    private val aliceIdentityId = "alice_id"
    private val bobContactId = "bob_id"
    private val charlieContactId = "charlie_id"

    private lateinit var groupService: GroupService

    @Before
    fun setUp() = runBlocking {
        // Setup Bob relationship & connection
        val relBob = "rel_alice_bob"
        setupPairwiseSession(relBob, aliceIdentityId, bobContactId)

        // Setup Charlie relationship & connection
        val relCharlie = "rel_alice_charlie"
        setupPairwiseSession(relCharlie, aliceIdentityId, charlieContactId)

        // Contacts
        contactDao.upsert(ContactEntity(bobContactId, relBob, "Bob", null, ByteArray(32), "VERIFIED", bobContactId))
        contactDao.upsert(ContactEntity(charlieContactId, relCharlie, "Charlie", null, ByteArray(32), "VERIFIED", charlieContactId))

        groupService = GroupService(
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            groupMessageDeliveryDao = groupMessageDeliveryDao,
            conversationDao = conversationDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            outboxDao = outboxDao,
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            agent = agent,
            localIdentityIdProvider = { aliceIdentityId },
            transactionRunner = { block -> block() }
        )
    }

    @After
    fun tearDown() {
        agent.stop()
    }

    private suspend fun setupPairwiseSession(relationshipId: String, senderId: String, recipientId: String) {
        val rootSecret = IdentityCrypto.generateX25519KeyPair().privateKey
        val secrets = RelationshipService.deriveSecrets(rootSecret)
        val aliceKey = IdentityCrypto.generateEd25519KeyPair()
        val bobKey = IdentityCrypto.generateEd25519KeyPair()

        val (sendQ, recvQ, sendAuth, recvAuth) = RelationshipService.deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            aliceKey.publicKey,
            bobKey.publicKey
        )

        val connection = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = sendQ,
            recvQueueId = recvQ,
            sendAuth = sendAuth,
            recvAuth = recvAuth
        )
        connectionManager.registerConnection(connection)

        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()
        sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )
    }

    @Test
    fun testCreateGroupSucceedsAndFansOutInvites() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!

        val group = groupService.createGroup(
            title = "RoboTech Club",
            initialMembers = listOf(bobContact, charlieContact)
        )

        assertNotNull(group)
        assertEquals("RoboTech Club", group.title)
        assertEquals(1L, group.epoch)
        assertEquals(aliceIdentityId, group.creatorIdentityId)

        // Conversation exists
        val conv = conversationDao.getById(group.groupId)
        assertNotNull(conv)
        assertEquals(ConversationType.GROUP, conv?.type)
        assertEquals("RoboTech Club", conv?.title)

        // Members exist: Alice (OWNER), Bob (MEMBER), Charlie (MEMBER)
        val members = groupMemberDao.getMembers(group.groupId)
        assertEquals(3, members.size)

        val aliceMember = groupMemberDao.getMember(group.groupId, aliceIdentityId)
        assertNotNull(aliceMember)
        assertEquals(GroupMemberRole.OWNER.name, aliceMember?.role)
        assertEquals(GroupMemberState.ACTIVE.name, aliceMember?.state)

        val bobMember = groupMemberDao.getMember(group.groupId, bobContactId)
        assertNotNull(bobMember)
        assertEquals(GroupMemberRole.MEMBER.name, bobMember?.role)

        // Two outbox items for GROUP_CREATE invites (one for Bob, one for Charlie)
        val outbox = outboxStore.items.values
        assertEquals(2, outbox.size)
    }

    @Test
    fun testSendGroupTextCreatesLogicalMessageAndPerRecipientDeliveries() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact, charlieContact))

        outboxStore.items.clear()

        val msg = groupService.sendGroupText(group.groupId, "Hello Team!")

        // 1. One logical message in MessageDao
        val savedMsg = messageDao.getById(msg.logicalMessageId)
        assertNotNull(savedMsg)
        assertEquals("Hello Team!", savedMsg?.body)
        assertEquals(aliceIdentityId, savedMsg?.senderId)
        assertEquals(group.groupId, savedMsg?.conversationId)

        // 2. Deliveries tracked per recipient
        val deliveries = groupMessageDeliveryDao.getDeliveriesForMessage(msg.logicalMessageId)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.any { it.recipientIdentityId == bobContactId && it.status == GroupDeliveryStatus.QUEUED.name })
        assertTrue(deliveries.any { it.recipientIdentityId == charlieContactId && it.status == GroupDeliveryStatus.QUEUED.name })

        // 3. Two independent pairwise encrypted outbox items
        val outboxItems = outboxStore.items.values
        assertEquals(2, outboxItems.size)
    }

    @Test
    fun testDeliveryAckAggregationAdvancesMessageStatus() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact, charlieContact))

        val msg = groupService.sendGroupText(group.groupId, "Check this out")
        assertEquals(DeliveryStatus.QUEUED.name, messageDao.getById(msg.logicalMessageId)?.status)

        // Bob ACKs delivery
        groupService.handleDeliveryAck(msg.logicalMessageId, bobContactId)

        // Message should still not be DELIVERED because Charlie hasn't ACKed yet
        val msgAfterBob = messageDao.getById(msg.logicalMessageId)
        assertEquals(DeliveryStatus.QUEUED.name, msgAfterBob?.status)

        val summaryAfterBob = groupService.getDeliverySummary(msg.logicalMessageId)
        assertEquals(2, summaryAfterBob?.totalRecipients)
        assertEquals(1, summaryAfterBob?.deliveredCount)
        assertEquals(0, summaryAfterBob?.readCount)

        // Charlie ACKs delivery -> All active delivered!
        groupService.handleDeliveryAck(msg.logicalMessageId, charlieContactId)

        val msgAfterBoth = messageDao.getById(msg.logicalMessageId)
        assertEquals(DeliveryStatus.DELIVERED.name, msgAfterBoth?.status)

        val summaryAfterBoth = groupService.getDeliverySummary(msg.logicalMessageId)
        assertEquals(2, summaryAfterBoth?.deliveredCount)
        assertEquals(GroupDeliveryStatus.DELIVERED, summaryAfterBoth?.overallStatus)
    }

    @Test
    fun testReadReceiptAggregationAdvancesMessageToRead() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact, charlieContact))

        val msg = groupService.sendGroupText(group.groupId, "Testing Read Receipts")

        groupService.handleDeliveryAck(msg.logicalMessageId, bobContactId)
        groupService.handleDeliveryAck(msg.logicalMessageId, charlieContactId)

        // Bob reads
        groupService.handleReadReceipt(msg.logicalMessageId, bobContactId)
        assertEquals(DeliveryStatus.DELIVERED.name, messageDao.getById(msg.logicalMessageId)?.status)

        // Charlie reads -> All active read!
        groupService.handleReadReceipt(msg.logicalMessageId, charlieContactId)
        assertEquals(DeliveryStatus.READ.name, messageDao.getById(msg.logicalMessageId)?.status)

        val summary = groupService.getDeliverySummary(msg.logicalMessageId)
        assertEquals(2, summary?.readCount)
        assertEquals(GroupDeliveryStatus.READ, summary?.overallStatus)
    }

    @Test
    fun testReactionsEditAndDeleteAuthorOnlyEnforcement() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact))
        val msg = groupService.sendGroupText(group.groupId, "Original Text")

        // 1. Reaction
        val reactSuccess = groupService.sendGroupReaction(group.groupId, msg.logicalMessageId, "🔥")
        assertTrue(reactSuccess)
        val reactions = reactionDao.getForMessage(msg.logicalMessageId)
        assertEquals(1, reactions.size)
        assertEquals("🔥", reactions[0].emoji)

        // 2. Edit (Author only)
        val editSuccess = groupService.sendGroupEdit(group.groupId, msg.logicalMessageId, "Edited Text")
        assertTrue(editSuccess)
        assertEquals("Edited Text", messageDao.getById(msg.logicalMessageId)?.body)

        // 3. Delete (Author only)
        val deleteSuccess = groupService.sendGroupDelete(group.groupId, msg.logicalMessageId)
        assertTrue(deleteSuccess)
        assertNotNull(messageDao.getById(msg.logicalMessageId)?.deletedAt)
    }

    @Test
    fun testMembershipManagementAndEpochEnforcement() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact))
        assertEquals(1L, group.epoch)

        // 1. Add Charlie -> advances epoch to 2
        val addSuccess = groupService.addMember(group.groupId, charlieContact, GroupMemberRole.MEMBER)
        assertTrue(addSuccess)
        assertEquals(2L, groupDao.getById(group.groupId)?.epoch)
        assertEquals(GroupMemberRole.MEMBER.name, groupMemberDao.getMember(group.groupId, charlieContactId)?.role)

        // 2. Promote Bob to ADMIN -> advances epoch to 3
        val roleSuccess = groupService.changeRole(group.groupId, bobContactId, GroupMemberRole.ADMIN)
        assertTrue(roleSuccess)
        assertEquals(3L, groupDao.getById(group.groupId)?.epoch)
        assertEquals(GroupMemberRole.ADMIN.name, groupMemberDao.getMember(group.groupId, bobContactId)?.role)

        // 3. Remove Charlie -> advances epoch to 4
        val removeSuccess = groupService.removeMember(group.groupId, charlieContactId)
        assertTrue(removeSuccess)
        assertEquals(4L, groupDao.getById(group.groupId)?.epoch)
        val charlieMember = groupMemberDao.getMember(group.groupId, charlieContactId)
        assertEquals(GroupMemberState.REMOVED.name, charlieMember?.state)
        assertEquals(4L, charlieMember?.removedEpoch)

        // Contact still exists in contacts table (contact was NOT deleted)
        assertNotNull(contactDao.getById(charlieContactId))

        // 4. Nobody removes OWNER
        val cannotRemoveOwner = groupService.removeMember(group.groupId, aliceIdentityId)
        assertFalse(cannotRemoveOwner)
    }

    @Test
    fun testRemovedMemberCannotSendFutureMessages() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact))

        // Mark Alice as REMOVED to simulate trying to send when removed
        groupMemberDao.updateState(group.groupId, aliceIdentityId, GroupMemberState.REMOVED.name, 2L, System.currentTimeMillis())

        try {
            groupService.sendGroupText(group.groupId, "Should fail")
            fail("Expected IllegalStateException when removed member tries to send")
        } catch (_: IllegalStateException) {
            // Expected
        }
    }

    @Test
    fun testRecoverPendingFanoutResumesUnsentDeliveries() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val group = groupService.createGroup("Devs", listOf(bobContact))

        val msgId = UUID.randomUUID().toString()
        val message = MessageEntity(
            logicalMessageId = msgId,
            conversationId = group.groupId,
            senderId = aliceIdentityId,
            type = MessageType.TEXT.name,
            body = "Recovered message",
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name
        )
        messageDao.upsert(message)

        val pendingDelivery = GroupMessageDeliveryEntity(
            deliveryId = "del_uncompleted",
            logicalMessageId = msgId,
            recipientIdentityId = bobContactId,
            relationshipId = "rel_alice_bob",
            status = GroupDeliveryStatus.PENDING.name
        )
        groupMessageDeliveryDao.upsert(pendingDelivery)

        outboxStore.items.clear()

        // Run recovery
        groupService.recoverPendingFanout()

        // Outbox item created and delivery updated to QUEUED
        val updatedDelivery = groupMessageDeliveryDao.deliveries["del_uncompleted"]
        assertEquals(GroupDeliveryStatus.QUEUED.name, updatedDelivery?.status)
        assertEquals(1, outboxStore.items.size)
    }
}
