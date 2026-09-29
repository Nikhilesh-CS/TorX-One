package com.torxone.app.groups

import com.torxone.app.agent.*
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.GroupHandler
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
    private val groupControlDao = TestGroupControlDao()
    private val conversationDao = TestConversationDao()
    private val messageDao = TestMessageDao()
    private val reactionDao = TestReactionDao()
    private val contactDao = TestContactDao()
    private val outboxStore = TestOutboxStore()
    private val outboxDao = TestOutboxDao(outboxStore)
    private val processedStore = TestProcessedStore()

    private val connectionManager = ConnectionManager(keyProtector = com.torxone.app.crypto.NoOpKeyProtector())
    private val sessionStore = TestSessionStore()
    private val sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)
    private val receiverCryptos = mutableMapOf<String, DoubleRatchetSessionCrypto>()
    private val transportRouter = TransportRouter()
    private val agent = TorXAgent(
        transportRouter = transportRouter,
        outboxStore = outboxStore,
        processedStore = processedStore,
        coroutineDispatcher = Dispatchers.Default
    )

    private val aliceIdentityId = "identity-alice-111"
    private val bobContactId = "contact-local-bob-222"
    private val bobIdentityId = "identity-remote-bob-333"
    private val charlieContactId = "contact-local-charlie-444"
    private val charlieIdentityId = "identity-remote-charlie-555"

    private lateinit var groupService: GroupService

    @Before
    fun setUp() = runBlocking {
        // Setup Bob relationship & connection
        val relBob = "rel_alice_bob"
        setupPairwiseSession(relBob, aliceIdentityId, bobIdentityId)

        // Setup Charlie relationship & connection
        val relCharlie = "rel_alice_charlie"
        setupPairwiseSession(relCharlie, aliceIdentityId, charlieIdentityId)

        // Contacts with distinct contactId vs remoteIdentityId
        contactDao.upsert(ContactEntity(
            contactId = bobContactId,
            relationshipId = relBob,
            displayName = "Bob",
            signingPublicKey = ByteArray(32),
            verificationState = "VERIFIED",
            conversationId = bobContactId,
            remoteIdentityId = bobIdentityId
        ))
        contactDao.upsert(ContactEntity(
            contactId = charlieContactId,
            relationshipId = relCharlie,
            displayName = "Charlie",
            signingPublicKey = ByteArray(32),
            verificationState = "VERIFIED",
            conversationId = charlieContactId,
            remoteIdentityId = charlieIdentityId
        ))

        groupService = GroupService(
            groupDao = groupDao,
            groupMemberDao = groupMemberDao,
            groupMessageDeliveryDao = groupMessageDeliveryDao,
            groupControlDao = groupControlDao,
            conversationDao = conversationDao,
            messageDao = messageDao,
            reactionDao = reactionDao,
            contactDao = contactDao,
            outboxDao = outboxDao,
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            agent = agent,
            localIdentityIdProvider = { aliceIdentityId },
            sessionStore = sessionStore,
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
        val receiverCrypto = DoubleRatchetSessionCrypto(TestSessionStore())
        receiverCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = false,
            remoteRatchetPublicKey = aliceRatchet.publicKey,
            localRatchetPrivateKey = bobRatchet.privateKey,
            localRatchetPublicKey = bobRatchet.publicKey
        )
        receiverCryptos[relationshipId] = receiverCrypto
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

        val bobMember = groupMemberDao.getMember(group.groupId, bobIdentityId)
        assertNotNull(bobMember)
        assertEquals(GroupMemberRole.MEMBER.name, bobMember?.role)
        assertEquals(bobContactId, bobMember?.contactId)
        assertEquals(bobIdentityId, bobMember?.memberIdentityId)

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
        assertTrue(deliveries.any { it.recipientIdentityId == bobIdentityId && it.status == GroupDeliveryStatus.QUEUED.name })
        assertTrue(deliveries.any { it.recipientIdentityId == charlieIdentityId && it.status == GroupDeliveryStatus.QUEUED.name })

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
        groupService.handleDeliveryAck(msg.logicalMessageId, bobIdentityId)

        // Message should still not be DELIVERED because Charlie hasn't ACKed yet
        val msgAfterBob = messageDao.getById(msg.logicalMessageId)
        assertEquals(DeliveryStatus.QUEUED.name, msgAfterBob?.status)

        val summaryAfterBob = groupService.getDeliverySummary(msg.logicalMessageId)
        assertEquals(2, summaryAfterBob?.totalRecipients)
        assertEquals(1, summaryAfterBob?.deliveredCount)
        assertEquals(0, summaryAfterBob?.readCount)

        // Charlie ACKs delivery -> All active delivered!
        groupService.handleDeliveryAck(msg.logicalMessageId, charlieIdentityId)

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

        groupService.handleDeliveryAck(msg.logicalMessageId, bobIdentityId)
        groupService.handleDeliveryAck(msg.logicalMessageId, charlieIdentityId)

        // Bob reads
        groupService.handleReadReceipt(msg.logicalMessageId, bobIdentityId)
        assertEquals(DeliveryStatus.DELIVERED.name, messageDao.getById(msg.logicalMessageId)?.status)

        // Charlie reads -> All active read!
        groupService.handleReadReceipt(msg.logicalMessageId, charlieIdentityId)
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
        assertEquals(GroupMemberRole.MEMBER.name, groupMemberDao.getMember(group.groupId, charlieIdentityId)?.role)

        // 2. Promote Bob to ADMIN -> advances epoch to 3
        val roleSuccess = groupService.changeRole(group.groupId, bobIdentityId, GroupMemberRole.ADMIN)
        assertTrue(roleSuccess)
        assertEquals(3L, groupDao.getById(group.groupId)?.epoch)
        assertEquals(GroupMemberRole.ADMIN.name, groupMemberDao.getMember(group.groupId, bobIdentityId)?.role)

        // 3. Remove Charlie -> advances epoch to 4
        val removeSuccess = groupService.removeMember(group.groupId, charlieIdentityId)
        assertTrue(removeSuccess)
        assertEquals(4L, groupDao.getById(group.groupId)?.epoch)
        val charlieMember = groupMemberDao.getMember(group.groupId, charlieIdentityId)
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
            recipientIdentityId = bobIdentityId,
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

    @Test
    fun testWirePayloadsNeverExposeLocalContactIds() = runBlocking {
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("Secret Devs", listOf(bobContact, charlieContact))

        // Verify outbox items for group creation
        val items = outboxStore.items.values.toList()
        assertEquals(2, items.size)
        // Decrypt and inspect invite payloads
        items.forEach { item ->
            // Envelopes are encrypted with pairwise ratchet session
            val connection = connectionManager.getConnectionByRelationship(item.relationshipId)!!
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray()
            val decryptedBytes = receiverCryptos.getValue(item.relationshipId).decrypt(
                relationshipId = item.relationshipId,
                message = com.torxone.app.crypto.EncryptedSessionMessage.deserialize(item.ciphertext),
                associatedData = aad
            )
            val envelope = ProtocolCodec.decodeSecureEnvelope(decryptedBytes)
            val invite = GroupProtocolCodec.decodeInvite(envelope.payload)

            // Assert that every member snapshot uses identity ID and never local contactId
            invite.members.forEach { m ->
                assertNotEquals("Local contactId must NEVER appear as identityId", bobContactId, m.identityId)
                assertNotEquals("Local contactId must NEVER appear as identityId", charlieContactId, m.identityId)
                assertNotEquals("Local contactId must NEVER appear as contactId on wire", bobContactId, m.contactId)
                assertNotEquals("Local contactId must NEVER appear as contactId on wire", charlieContactId, m.contactId)
                assertTrue("Wire identity must be an authoritative identity ID",
                    m.identityId == aliceIdentityId || m.identityId == bobIdentityId || m.identityId == charlieIdentityId)
            }
        }
    }

    @Test
    fun testGroupMembershipSurvivesDevicesWithDifferentLocalContactIds() = runBlocking {
        // Alice has Charlie as "contact-local-charlie-444"
        // Bob has Charlie as "contact-charlie-on-bob-device-999"
        // Both share charlieIdentityId = "identity-remote-charlie-555"
        val bobContact = contactDao.getById(bobContactId)!!
        val charlieContact = contactDao.getById(charlieContactId)!!
        val group = groupService.createGroup("MultiDevice Group", listOf(bobContact, charlieContact))

        // Alice's local database links to her local contactId
        val aliceViewOfCharlie = groupMemberDao.getMember(group.groupId, charlieIdentityId)
        assertNotNull(aliceViewOfCharlie)
        assertEquals(charlieContactId, aliceViewOfCharlie?.contactId)
        assertEquals(charlieIdentityId, aliceViewOfCharlie?.memberIdentityId)

        // Bob receives the invite and handles it
        val bobGroupDao = TestGroupDao()
        val bobGroupMemberDao = TestGroupMemberDao()
        val bobConversationDao = TestConversationDao()
        val bobContactDao = TestContactDao().also { dao ->
            dao.contacts["alice-on-bob"] = ContactEntity(contactId = "alice-on-bob", relationshipId = "rel_alice_bob", displayName = "Alice", signingPublicKey = ByteArray(32), conversationId = "alice-on-bob", remoteIdentityId = aliceIdentityId)
            dao.contacts["charlie-on-bob"] = ContactEntity(contactId = "charlie-on-bob", relationshipId = "rel_bob_charlie", displayName = "Charlie", signingPublicKey = ByteArray(32), conversationId = "charlie-on-bob", remoteIdentityId = charlieIdentityId)
        }
        val bobHandler = GroupHandler(
            groupDao = bobGroupDao,
            groupMemberDao = bobGroupMemberDao,
            conversationDao = bobConversationDao,
            contactDao = bobContactDao,
            localIdentityIdProvider = { bobIdentityId },
            transactionRunner = { it() }
        )

        val aliceToBobOutbox = outboxStore.items.values.first { it.relationshipId == "rel_alice_bob" }
        val aliceToBobConnection = connectionManager.getConnectionByRelationship(aliceToBobOutbox.relationshipId)!!
        val aliceToBobAad = "torx-aad-v1:${aliceToBobConnection.generation}:${aliceToBobConnection.sendQueueId}".toByteArray()
        val decrypted = receiverCryptos.getValue(aliceToBobOutbox.relationshipId).decrypt(
            aliceToBobOutbox.relationshipId,
            com.torxone.app.crypto.EncryptedSessionMessage.deserialize(aliceToBobOutbox.ciphertext),
            aliceToBobAad
        )
        val envelope = ProtocolCodec.decodeSecureEnvelope(decrypted)

        val bobConnection = Connection(
            relationshipId = "rel_alice_bob",
            generation = 1,
            sendQueueId = "q_s",
            recvQueueId = "q_r",
            sendAuth = ByteArray(32),
            recvAuth = ByteArray(32)
        )
        val handled = bobHandler.handleGroupCreateOrInvite(bobConnection, envelope)
        assertTrue(handled)

        // In Bob's member DAO, Charlie is correctly identified by charlieIdentityId
        val bobViewOfCharlie = bobGroupMemberDao.getMember(group.groupId, charlieIdentityId)
        assertNotNull(bobViewOfCharlie)
        assertEquals(charlieIdentityId, bobViewOfCharlie?.memberIdentityId)
        // Alice's local contact ID was never leaked or assigned as Charlie's identity on Bob's device
        assertNotEquals(charlieContactId, bobViewOfCharlie?.contactId)
    }

    @Test
    fun testGroupCreationDurablyJournalsEveryControlRecipient() = runBlocking {
        val group = groupService.createGroup(
            "Journaled Group",
            listOf(contactDao.getById(bobContactId)!!, contactDao.getById(charlieContactId)!!)
        )

        val operation = groupControlDao.operations.values.single { it.groupId == group.groupId }
        assertEquals(1L, operation.newEpoch)
        assertEquals("QUEUED", operation.status)
        val deliveries = groupControlDao.deliveries.values.filter { it.operationId == operation.operationId }
        assertEquals(setOf(bobIdentityId, charlieIdentityId), deliveries.map { it.recipientIdentityId }.toSet())
        assertTrue(deliveries.all { it.status == "QUEUED" && it.outboxDeliveryId != null })
    }

    @Test
    fun testUnfinishedControlOperationBlocksNextEpoch() = runBlocking {
        val group = groupService.createGroup("Ordered Group", listOf(contactDao.getById(bobContactId)!!))
        val pendingId = "pending-epoch-2"
        groupControlDao.insertOperation(
            GroupControlOperationEntity(pendingId, group.groupId, 1L, 2L)
        )
        groupControlDao.insertDeliveries(
            listOf(
                GroupControlDeliveryEntity(
                    operationId = pendingId,
                    recipientIdentityId = bobIdentityId,
                    relationshipId = "rel_alice_bob",
                    messageType = MessageType.GROUP_NAME_CHANGE.name,
                    payload = byteArrayOf(1),
                    envelopeEpoch = 2L
                )
            )
        )

        assertFalse(groupService.updateGroupTitle(group.groupId, "Must Wait"))
        assertEquals(1L, groupDao.getById(group.groupId)?.epoch)
        assertEquals("Ordered Group", groupDao.getById(group.groupId)?.title)
    }

    @Test
    fun testStartupRecoveryQueuesPendingControlOperation() = runBlocking {
        val group = groupService.createGroup("Recovery Group", listOf(contactDao.getById(bobContactId)!!))
        val operationId = "recover-epoch-2"
        val payload = GroupProtocolCodec.encodeNameChange(
            GroupNameChangePayload(group.groupId, "Recovered Name", aliceIdentityId, 1L, 2L)
        )
        groupControlDao.insertOperation(
            GroupControlOperationEntity(operationId, group.groupId, 1L, 2L)
        )
        groupControlDao.insertDeliveries(
            listOf(
                GroupControlDeliveryEntity(
                    operationId = operationId,
                    recipientIdentityId = bobIdentityId,
                    relationshipId = "rel_alice_bob",
                    messageType = MessageType.GROUP_NAME_CHANGE.name,
                    payload = payload,
                    envelopeEpoch = 2L
                )
            )
        )
        groupDao.updateTitle(group.groupId, "Recovered Name")
        groupDao.updateEpoch(group.groupId, 2L)

        groupService.recoverPendingFanout()

        assertEquals("QUEUED", groupControlDao.getOperation(operationId)?.status)
        val delivery = groupControlDao.deliveries[operationId to bobIdentityId]
        assertEquals("QUEUED", delivery?.status)
        assertNotNull(delivery?.outboxDeliveryId)
    }
}
