package com.torxone.app.groups

import com.torxone.app.agent.*
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.identity.TorXIdentity
import com.torxone.app.incoming.*
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ReactionOperation
import com.torxone.app.relationship.RelationshipService
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroupE2ETest {

    class MultiNodeTransport : Transport {
        override val type: TransportType = TransportType.FAKE
        private val _availability = MutableStateFlow<TransportAvailability>(TransportAvailability.Available)
        override fun availability(): Flow<TransportAvailability> = _availability.asStateFlow()

        val hubsByQueue = ConcurrentHashMap<String, IncomingTransportHub>()

        fun registerQueue(queueAddress: String, hub: IncomingTransportHub) {
            hubsByQueue[queueAddress] = hub
        }

        override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
            val targetHub = hubsByQueue[destination.address]
            if (targetHub != null) {
                targetHub.onRawFrameReceived(payload.copyOf(), type)
                return TransportResult.Accepted(type)
            }
            return TransportResult.Failed(type, "Queue not found: ${destination.address}")
        }
    }

    class TestNode(val name: String, val transport: MultiNodeTransport) {
        val identity: TorXIdentity
        val groupDao = TestGroupDao()
        val groupMemberDao = TestGroupMemberDao()
        val groupMessageDeliveryDao = TestGroupMessageDeliveryDao()
        val conversationDao = TestConversationDao()
        val messageDao = TestMessageDao()
        val reactionDao = TestReactionDao()
        val contactDao = TestContactDao()
        val outboxStore = TestOutboxStore()
        val outboxDao = TestOutboxDao(outboxStore)
        val processedStore = TestProcessedStore()
        val sessionStore = TestSessionStore()
        val sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)
        val connectionManager = ConnectionManager()
        val activeTracker = ActiveConversationTracker()
        val transportRouter = TransportRouter()
        val agent: TorXAgent

        val groupService: GroupService
        val groupHandler: GroupHandler
        val chatReceiver: ChatReceiver
        val deliveryReceiptHandler: DeliveryReceiptHandler
        val reactionHandler: ReactionHandler
        val editHandler: EditHandler
        val deleteHandler: DeleteHandler
        val dispatcher: IncomingDispatcher
        val incomingHub: IncomingTransportHub

        init {
            val signPair = IdentityCrypto.generateEd25519KeyPair()
            val encPair = IdentityCrypto.generateX25519KeyPair()
            identity = TorXIdentity(
                identityId = UUID.randomUUID().toString(),
                signingPublicKey = signPair.publicKey,
                signingPrivateKey = signPair.privateKey,
                encryptionPublicKey = encPair.publicKey,
                encryptionPrivateKey = encPair.privateKey,
                displayName = name
            )

            agent = TorXAgent(
                transportRouter = transportRouter,
                outboxStore = outboxStore,
                processedStore = processedStore,
                coroutineDispatcher = Dispatchers.Default,
                baseRetryDelayMs = 200L,
                outboxPollIntervalMs = 50L
            )

            chatReceiver = ChatReceiver(messageDao, conversationDao, activeTracker)
            reactionHandler = ReactionHandler(reactionDao, messageDao)
            editHandler = EditHandler(messageDao, conversationDao)
            deleteHandler = DeleteHandler(messageDao, conversationDao)

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
                localIdentityIdProvider = { identity.identityId },
                transactionRunner = { it() }
            )

            groupHandler = GroupHandler(
                groupDao = groupDao,
                groupMemberDao = groupMemberDao,
                conversationDao = conversationDao,
                localIdentityIdProvider = { identity.identityId },
                notificationManager = null,
                transactionRunner = { it() }
            )

            deliveryReceiptHandler = DeliveryReceiptHandler(
                messageDao = messageDao,
                outboxDao = outboxDao,
                agent = agent,
                groupService = groupService
            )

            dispatcher = IncomingDispatcher(
                connectionManager = connectionManager,
                sessionCrypto = sessionCrypto,
                processedEnvelopeDao = processedStore,
                chatReceiver = chatReceiver,
                deliveryReceiptHandler = deliveryReceiptHandler,
                agent = agent,
                localIdentityIdProvider = { identity.identityId },
                reactionHandler = reactionHandler,
                editHandler = editHandler,
                deleteHandler = deleteHandler,
                groupHandler = groupHandler,
                groupDao = groupDao,
                groupMemberDao = groupMemberDao,
                conversationDao = conversationDao,
                contactDao = contactDao,
                transactionRunner = { it() }
            )

            incomingHub = IncomingTransportHub(dispatcher)
            transportRouter.registerTransport(transport)
            agent.start()
        }

        fun stop() {
            agent.stop()
        }
    }

    private val sharedTransport = MultiNodeTransport()
    private lateinit var alice: TestNode
    private lateinit var bob: TestNode
    private lateinit var charlie: TestNode

    @Before
    fun setUp() = runBlocking {
        alice = TestNode("Alice", sharedTransport)
        bob = TestNode("Bob", sharedTransport)
        charlie = TestNode("Charlie", sharedTransport)

        // Setup pairwise relationships & Double Ratchet sessions
        setupPairwise(alice, bob, "rel_alice_bob")
        setupPairwise(alice, charlie, "rel_alice_charlie")
        setupPairwise(bob, charlie, "rel_bob_charlie")
    }

    @After
    fun tearDown() {
        alice.stop()
        bob.stop()
        charlie.stop()
    }

    private suspend fun setupPairwise(nodeA: TestNode, nodeB: TestNode, relationshipId: String) {
        val rootSecret = IdentityCrypto.generateX25519KeyPair().privateKey
        val secrets = RelationshipService.deriveSecrets(rootSecret)

        val (aToSendQueue, bToSendQueue, aSendAuth, bSendAuth) = RelationshipService.deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            nodeA.identity.signingPublicKey,
            nodeB.identity.signingPublicKey
        )

        // Register queues in transport
        // nodeA sends to aToSendQueue -> delivered to nodeB's incomingHub
        // nodeB sends to bToSendQueue -> delivered to nodeA's incomingHub
        sharedTransport.registerQueue(aToSendQueue, nodeB.incomingHub)
        sharedTransport.registerQueue(bToSendQueue, nodeA.incomingHub)

        val connA = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = aToSendQueue,
            recvQueueId = bToSendQueue,
            sendAuth = aSendAuth,
            recvAuth = bSendAuth
        )
        nodeA.connectionManager.registerConnection(connA)

        val connB = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = bToSendQueue,
            recvQueueId = aToSendQueue,
            sendAuth = bSendAuth,
            recvAuth = aSendAuth
        )
        nodeB.connectionManager.registerConnection(connB)

        // Initialize Double Ratchet sessions
        val ratchetkPairA = IdentityCrypto.generateX25519KeyPair()
        val ratchetkPairB = IdentityCrypto.generateX25519KeyPair()

        nodeA.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = true,
            remoteRatchetPublicKey = ratchetkPairB.publicKey,
            localRatchetPrivateKey = ratchetkPairA.privateKey,
            localRatchetPublicKey = ratchetkPairA.publicKey
        )

        nodeB.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = false,
            remoteRatchetPublicKey = ratchetkPairA.publicKey,
            localRatchetPrivateKey = ratchetkPairB.privateKey,
            localRatchetPublicKey = ratchetkPairB.publicKey
        )

        // Upsert contacts
        nodeA.contactDao.upsert(
            ContactEntity(nodeB.identity.identityId, relationshipId, nodeB.name, null, nodeB.identity.signingPublicKey, "VERIFIED", nodeB.identity.identityId)
        )
        nodeB.contactDao.upsert(
            ContactEntity(nodeA.identity.identityId, relationshipId, nodeA.name, null, nodeA.identity.signingPublicKey, "VERIFIED", nodeA.identity.identityId)
        )
    }

    private suspend fun waitFor(timeoutMs: Long = 8000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    @Test
    fun testCompleteGroupLifecycleE2E() = runBlocking {
        val bobContact = ContactEntity(bob.identity.identityId, "rel_alice_bob", "Bob", null, bob.identity.signingPublicKey, "VERIFIED", bob.identity.identityId)
        val charlieContact = ContactEntity(charlie.identity.identityId, "rel_alice_charlie", "Charlie", null, charlie.identity.signingPublicKey, "VERIFIED", charlie.identity.identityId)
        alice.contactDao.upsert(bobContact)
        alice.contactDao.upsert(charlieContact)

        // ═══════════════════════════════════════════════════════════════
        // 1. Group Creation & Multi-Peer Invites
        // ═══════════════════════════════════════════════════════════════
        val createdGroup = alice.groupService.createGroup("Engineering Team", listOf(bobContact, charlieContact))
        val groupId = createdGroup.groupId

        // Wait for Bob and Charlie to receive the group invite
        waitFor {
            bob.groupDao.getById(groupId) != null && charlie.groupDao.getById(groupId) != null
        }

        val bobGroup = bob.groupDao.getById(groupId)
        assertNotNull(bobGroup)
        assertEquals("Engineering Team", bobGroup?.title)
        assertEquals(1L, bobGroup?.epoch)

        val charlieGroup = charlie.groupDao.getById(groupId)
        assertNotNull(charlieGroup)
        assertEquals("Engineering Team", charlieGroup?.title)
        assertEquals(1L, charlieGroup?.epoch)

        // Check group conversation in Bob and Charlie
        assertNotNull(bob.conversationDao.getById(groupId))
        assertNotNull(charlie.conversationDao.getById(groupId))

        // Check members populated in Bob
        val bobGroupMembers = bob.groupMemberDao.getMembers(groupId)
        assertEquals(3, bobGroupMembers.size)

        // ═══════════════════════════════════════════════════════════════
        // 2. Group Message Fan-out, Decryption, & Delivery Receipt Aggregation
        // ═══════════════════════════════════════════════════════════════
        val msg = alice.groupService.sendGroupText(groupId, "Welcome everyone to our secure group!")

        // Wait for Bob and Charlie to receive and decrypt the message
        waitFor {
            bob.messageDao.getById(msg.logicalMessageId) != null &&
            charlie.messageDao.getById(msg.logicalMessageId) != null
        }

        assertEquals("Welcome everyone to our secure group!", bob.messageDao.getById(msg.logicalMessageId)?.body)
        assertEquals("Welcome everyone to our secure group!", charlie.messageDao.getById(msg.logicalMessageId)?.body)

        // Both nodes automatically reply with DELIVERY_ACK back to Alice
        // Wait for Alice to receive both ACKs and aggregate to DELIVERED
        waitFor {
            val aliceMsg = alice.messageDao.getById(msg.logicalMessageId)
            aliceMsg?.status == DeliveryStatus.DELIVERED.name
        }

        val summaryAfterAcks = alice.groupService.getDeliverySummary(msg.logicalMessageId)
        assertNotNull(summaryAfterAcks)
        assertEquals(2, summaryAfterAcks?.totalRecipients)
        assertEquals(2, summaryAfterAcks?.deliveredCount)
        assertEquals(0, summaryAfterAcks?.readCount)
        assertEquals(GroupDeliveryStatus.DELIVERED, summaryAfterAcks?.overallStatus)

        // ═══════════════════════════════════════════════════════════════
        // 3. Read Receipts Aggregation
        // ═══════════════════════════════════════════════════════════════
        // Bob reads the message
        alice.groupService.handleReadReceipt(msg.logicalMessageId, bob.identity.identityId)

        // Since Charlie hasn't read, Alice's message remains DELIVERED
        assertEquals(DeliveryStatus.DELIVERED.name, alice.messageDao.getById(msg.logicalMessageId)?.status)

        // Charlie reads the message
        alice.groupService.handleReadReceipt(msg.logicalMessageId, charlie.identity.identityId)

        // Now all active recipients read -> Alice's message becomes READ!
        assertEquals(DeliveryStatus.READ.name, alice.messageDao.getById(msg.logicalMessageId)?.status)

        val summaryAfterRead = alice.groupService.getDeliverySummary(msg.logicalMessageId)
        assertEquals(2, summaryAfterRead?.readCount)
        assertEquals(GroupDeliveryStatus.READ, summaryAfterRead?.overallStatus)

        // ═══════════════════════════════════════════════════════════════
        // 4. Reactions Fan-out
        // ═══════════════════════════════════════════════════════════════
        // Charlie reacts with 🚀
        val reacted = charlie.groupService.sendGroupReaction(groupId, msg.logicalMessageId, "🚀")
        assertTrue(reacted)

        // Wait for Alice to receive reaction
        waitFor {
            alice.reactionDao.getForMessage(msg.logicalMessageId).isNotEmpty()
        }
        val aliceReactions = alice.reactionDao.getForMessage(msg.logicalMessageId)
        assertEquals(1, aliceReactions.size)
        assertEquals("🚀", aliceReactions[0].emoji)
        assertEquals(charlie.identity.identityId, aliceReactions[0].senderId)

        // ═══════════════════════════════════════════════════════════════
        // 5. Author-Only Edit Fan-out
        // ═══════════════════════════════════════════════════════════════
        val edited = alice.groupService.sendGroupEdit(groupId, msg.logicalMessageId, "Welcome everyone to our ultra-secure group!")
        assertTrue(edited)

        // Wait for Bob and Charlie to see the edit
        waitFor {
            bob.messageDao.getById(msg.logicalMessageId)?.body == "Welcome everyone to our ultra-secure group!" &&
            charlie.messageDao.getById(msg.logicalMessageId)?.body == "Welcome everyone to our ultra-secure group!"
        }
        assertEquals(1, bob.messageDao.getById(msg.logicalMessageId)?.editVersion)
        assertEquals(1, charlie.messageDao.getById(msg.logicalMessageId)?.editVersion)

        // ═══════════════════════════════════════════════════════════════
        // 6. Member Removal & Epoch Enforcement
        // ═══════════════════════════════════════════════════════════════
        val removed = alice.groupService.removeMember(groupId, charlie.identity.identityId)
        assertTrue(removed)

        // Alice advances epoch to 2
        assertEquals(2L, alice.groupDao.getById(groupId)?.epoch)

        // Wait for Bob and Charlie to process removal
        waitFor {
            bob.groupDao.getById(groupId)?.epoch == 2L &&
            charlie.groupDao.getById(groupId)?.epoch == 2L
        }

        // Charlie is marked REMOVED in Bob and Charlie
        assertEquals(GroupMemberState.REMOVED.name, bob.groupMemberDao.getMember(groupId, charlie.identity.identityId)?.state)
        assertEquals(GroupMemberState.REMOVED.name, charlie.groupMemberDao.getMember(groupId, charlie.identity.identityId)?.state)

        // Alice sends message in epoch 2
        val epoch2Msg = alice.groupService.sendGroupText(groupId, "Charlie has departed. Only active members remain.")

        // Wait for Bob to receive
        waitFor {
            bob.messageDao.getById(epoch2Msg.logicalMessageId) != null
        }
        assertEquals("Charlie has departed. Only active members remain.", bob.messageDao.getById(epoch2Msg.logicalMessageId)?.body)

        // Charlie was NOT in active fanout, so Charlie didn't receive epoch 2 message
        assertNull(charlie.messageDao.getById(epoch2Msg.logicalMessageId))

        // If Charlie tries to send to the group now, Charlie's node rejects it locally
        try {
            charlie.groupService.sendGroupText(groupId, "Can I still talk?")
            fail("Expected IllegalStateException when removed member tries to send")
        } catch (_: IllegalStateException) {
            // Expected!
        }
    }
}
