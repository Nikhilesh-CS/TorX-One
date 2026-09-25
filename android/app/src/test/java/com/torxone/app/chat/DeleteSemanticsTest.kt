package com.torxone.app.chat

import com.torxone.app.agent.*
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionState
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.*
import com.torxone.app.protocol.*
import com.torxone.app.transport.*
import com.torxone.app.transport.nearby.DirectRouteTable
import com.torxone.app.transport.nearby.RouteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteSemanticsTest {

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ── In-Memory DAOs ──

    class TestMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()

        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

        override suspend fun getById(messageId: String): MessageEntity? = messages[messageId]

        override suspend fun exists(messageId: String): Boolean = messages.containsKey(messageId)

        override suspend fun insertIfAbsent(message: MessageEntity): Long {
            return if (messages.putIfAbsent(message.logicalMessageId, message) == null) 1L else -1L
        }

        override suspend fun upsert(message: MessageEntity) {
            messages[message.logicalMessageId] = message
        }

        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }

        override suspend fun markDelivered(messageId: String, status: String, deliveredAt: Long) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(status = status, deliveredAt = deliveredAt)
            }
        }

        override suspend fun markRead(messageId: String, status: String, readAt: Long) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(status = status, readAt = readAt)
            }
        }

        override suspend fun markOutgoingReadUpTo(
            conversationId: String,
            upToCreatedAt: Long,
            status: String,
            readAt: Long
        ) {
            for ((id, msg) in messages) {
                if (msg.conversationId == conversationId &&
                    msg.direction == MessageDirection.OUTGOING &&
                    msg.createdAt <= upToCreatedAt
                ) {
                    messages[id] = msg.copy(status = status, readAt = readAt)
                }
            }
        }

        override suspend fun markAllIncomingRead(conversationId: String, status: String, readAt: Long) {
            for ((id, msg) in messages) {
                if (msg.conversationId == conversationId && msg.direction == MessageDirection.INCOMING) {
                    messages[id] = msg.copy(status = status, readAt = readAt)
                }
            }
        }

        override suspend fun getLatestUnreadIncoming(conversationId: String): MessageEntity? {
            return messages.values
                .filter { it.conversationId == conversationId && it.direction == MessageDirection.INCOMING && it.status != "READ" }
                .maxByOrNull { it.createdAt }
        }

        override suspend fun updateBodyAndEdit(
            messageId: String,
            newBody: String,
            editVersion: Int,
            editedAt: Long
        ) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(body = newBody, editVersion = editVersion, editedAt = editedAt)
            }
        }

        override suspend fun markDeleted(messageId: String, deletedAt: Long) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(body = null, deletedAt = deletedAt)
            }
        }

        override suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity> {
            return messages.values
                .filter { it.conversationId == conversationId }
                .sortedByDescending { it.createdAt }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            messages.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class TestConversationDao : ConversationDao {
        val convs = ConcurrentHashMap<String, ConversationEntity>()

        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())

        override fun observeActive(): Flow<List<ConversationEntity>> =
            flowOf(convs.values.filter { !it.isArchived }.sortedWith(
                compareByDescending<ConversationEntity> { it.isPinned }
                    .thenByDescending { it.pinnedAt ?: 0L }
                    .thenByDescending { it.lastMessageTime ?: 0L }
            ))

        override fun observeArchived(): Flow<List<ConversationEntity>> =
            flowOf(convs.values.filter { it.isArchived }.sortedWith(
                compareByDescending<ConversationEntity> { it.archivedAt ?: 0L }
                    .thenByDescending { it.lastMessageTime ?: 0L }
            ))

        override fun observeArchivedCount(): Flow<Int> =
            flowOf(convs.values.count { it.isArchived })

        override suspend fun getById(id: String): ConversationEntity? = convs[id]

        override fun observeById(id: String): Flow<ConversationEntity?> = flowOf(convs[id])

        override suspend fun upsert(conversation: ConversationEntity) {
            convs[conversation.conversationId] = conversation
        }

        override suspend fun updateUnreadCount(id: String, count: Int) {
            convs[id]?.let { convs[id] = it.copy(unreadCount = count) }
        }

        override suspend fun updateManuallyUnread(id: String, manuallyUnread: Boolean) {
            convs[id]?.let { convs[id] = it.copy(manuallyUnread = manuallyUnread) }
        }

        override suspend fun updateLastMessage(
            conversationId: String,
            messageId: String,
            preview: String?,
            time: Long
        ) {
            convs[conversationId]?.let {
                convs[conversationId] = it.copy(
                    lastMessageId = messageId,
                    lastMessagePreview = preview,
                    lastMessageTime = time
                )
            }
        }

        override suspend fun updateLastMessagePreviewIfLatest(messageId: String, preview: String?) {
            for ((id, conv) in convs) {
                if (conv.lastMessageId == messageId) {
                    convs[id] = conv.copy(lastMessagePreview = preview)
                }
            }
        }

        override suspend fun setPinned(id: String, isPinned: Boolean, pinnedAt: Long?) {
            convs[id]?.let { convs[id] = it.copy(isPinned = isPinned, pinnedAt = pinnedAt) }
        }

        override suspend fun setArchived(id: String, isArchived: Boolean, archivedAt: Long?) {
            convs[id]?.let { convs[id] = it.copy(isArchived = isArchived, archivedAt = archivedAt) }
        }

        override suspend fun unarchive(id: String) {
            convs[id]?.let { convs[id] = it.copy(isArchived = false, archivedAt = null) }
        }

        override suspend fun setMutedUntil(id: String, mutedUntil: Long?) {
            convs[id]?.let { convs[id] = it.copy(mutedUntil = mutedUntil) }
        }

        override suspend fun deleteById(id: String) {
            convs.remove(id)
        }

        override fun searchConversations(query: String): Flow<List<ConversationEntity>> {
            val q = query.trim().lowercase()
            return flowOf(convs.values.filter {
                it.title?.lowercase()?.contains(q) == true || (it.lastMessagePreview?.lowercase()?.contains(q) == true)
            })
        }
    }

    class TestOutboxDao : OutboxDao {
        val outbox = ConcurrentHashMap<String, OutboxEntity>()

        override suspend fun insert(item: OutboxEntity) {
            outbox[item.deliveryId] = item
        }

        override suspend fun getPending(now: Long): List<OutboxEntity> =
            outbox.values.filter { it.status == DeliveryStatus.QUEUED.name }

        override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {
            outbox[deliveryId]?.let { outbox[deliveryId] = it.copy(status = status) }
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {
            outbox[deliveryId]?.let {
                outbox[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt)
            }
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            outbox.values.removeIf { it.logicalMessageId == logicalMessageId }
        }
    }

    class TestReactionDao : ReactionDao {
        val reactions = ConcurrentHashMap<String, ReactionEntity>()

        override suspend fun getForMessage(messageId: String): List<ReactionEntity> {
            return reactions.values.filter { it.messageId == messageId }
        }

        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> {
            return flowOf(reactions.values.filter { it.conversationId == conversationId })
        }

        override suspend fun insertOrUpdate(reaction: ReactionEntity) {
            val key = "${reaction.messageId}_${reaction.senderId}_${reaction.emoji}"
            reactions[key] = reaction
        }

        override suspend fun remove(messageId: String, senderId: String, emoji: String) {
            val key = "${messageId}_${senderId}_${emoji}"
            reactions.remove(key)
        }

        override suspend fun removeAllFromSender(messageId: String, senderId: String) {
            reactions.values.removeIf { it.messageId == messageId && it.senderId == senderId }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            reactions.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class TestLocalMessageStateDao : LocalMessageStateDao {
        val states = ConcurrentHashMap<String, LocalMessageStateEntity>()
        private val hiddenFlow = MutableStateFlow<List<String>>(emptyList())

        override suspend fun getByMessageId(messageId: String): LocalMessageStateEntity? = states[messageId]

        override fun observeHiddenMessageIds(conversationId: String): Flow<List<String>> =
            flowOf(states.values.filter { it.conversationId == conversationId && it.hiddenLocally }.map { it.messageId })

        override suspend fun getHiddenMessageIds(conversationId: String): List<String> =
            states.values.filter { it.conversationId == conversationId && it.hiddenLocally }.map { it.messageId }

        override fun observeAllHiddenMessageIds(): Flow<List<String>> =
            flowOf(states.values.filter { it.hiddenLocally }.map { it.messageId })

        override suspend fun upsert(entity: LocalMessageStateEntity) {
            states[entity.messageId] = entity
            hiddenFlow.value = states.values.filter { it.hiddenLocally }.map { it.messageId }
        }

        override suspend fun delete(messageId: String) {
            states.remove(messageId)
            hiddenFlow.value = states.values.filter { it.hiddenLocally }.map { it.messageId }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            states.entries.removeIf { it.value.conversationId == conversationId }
            hiddenFlow.value = states.values.filter { it.hiddenLocally }.map { it.messageId }
        }
    }

    class InMemoryOutboxStore : OutboxStore {
        val items = ConcurrentHashMap<String, DeliveryItem>()
        override suspend fun insert(item: DeliveryItem) { items[item.deliveryId] = item }
        override suspend fun getPendingItems(): List<DeliveryItem> = items.values.filter { it.status == DeliveryStatus.QUEUED }
        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status) }
        }
        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt) }
        }
        override suspend fun removeByMessageId(logicalMessageId: String) {
            items.values.removeIf { it.logicalMessageId == logicalMessageId }
        }
    }

    class InMemoryProcessedStore : ProcessedEnvelopeStore {
        val processed = ConcurrentHashMap.newKeySet<String>()
        override suspend fun isProcessed(envelopeId: String): Boolean = processed.contains(envelopeId)
        override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = false
        override suspend fun markProcessed(record: ProcessedEnvelope) { processed.add(record.envelopeId) }
    }

    class InMemorySessionStore : com.torxone.app.crypto.SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
    }

    class DirectLoopbackTransport(
        val destinationHub: IncomingTransportHub
    ) : Transport {
        override val type: TransportType = TransportType.NEARBY
        override fun availability(): Flow<TransportAvailability> = flowOf(TransportAvailability.Available)
        override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
            destinationHub.onRawFrameReceived(payload, TransportType.NEARBY)
            return TransportResult.Accepted(TransportType.NEARBY)
        }
    }

    class TestNode(
        val name: String,
        val peerName: String,
        val relationshipId: String,
        val crypto: DoubleRatchetSessionCrypto,
        val msgDao: TestMessageDao = TestMessageDao(),
        val convDao: TestConversationDao = TestConversationDao(),
        val rxDao: TestReactionDao = TestReactionDao(),
        val localStateDao: TestLocalMessageStateDao = TestLocalMessageStateDao(),
        val outboxStore: InMemoryOutboxStore = InMemoryOutboxStore(),
        val processedStore: InMemoryProcessedStore = InMemoryProcessedStore(),
        val connManager: ConnectionManager = ConnectionManager(),
        val router: TransportRouter = TransportRouter(),
        var agent: TorXAgent? = null,
        var chatService: ChatService? = null,
        var incomingDispatcher: IncomingDispatcher? = null,
        var incomingHub: IncomingTransportHub? = null
    )

    private suspend fun createBilateralNodes(relationshipId: String = "rel-delete-test"): Pair<TestNode, TestNode> {
        val rootKey = "shared-test-root-key-32-bytes!!!".toByteArray()
        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()

        val cryptoAlice = DoubleRatchetSessionCrypto(InMemorySessionStore())
        val cryptoBob = DoubleRatchetSessionCrypto(InMemorySessionStore())

        cryptoAlice.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = rootKey,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )

        cryptoBob.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = rootKey,
            isInitiator = false,
            remoteRatchetPublicKey = aliceRatchet.publicKey,
            localRatchetPrivateKey = bobRatchet.privateKey,
            localRatchetPublicKey = bobRatchet.publicKey
        )

        val connAlice = Connection(
            connectionId = "c-a",
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-a2b",
            recvQueueId = "q-b2a",
            sendAuth = "auth-a2b".toByteArray(),
            recvAuth = "auth-b2a".toByteArray()
        )
        val connBob = Connection(
            connectionId = "c-b",
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-b2a",
            recvQueueId = "q-a2b",
            sendAuth = "auth-b2a".toByteArray(),
            recvAuth = "auth-a2b".toByteArray()
        )

        val alice = TestNode("alice", "bob", relationshipId, cryptoAlice).apply {
            connManager.registerConnection(connAlice)
        }
        val bob = TestNode("bob", "alice", relationshipId, cryptoBob).apply {
            connManager.registerConnection(connBob)
        }

        alice.agent = TorXAgent(alice.router, alice.outboxStore, alice.processedStore, Dispatchers.Default)
        bob.agent = TorXAgent(bob.router, bob.outboxStore, bob.processedStore, Dispatchers.Default)

        val routeTableAlice = DirectRouteTable().apply { bindRoute(relationshipId, "ep-bob", RouteState.READY) }
        val routeTableBob = DirectRouteTable().apply { bindRoute(relationshipId, "ep-alice", RouteState.READY) }

        val activeTrackerAlice = ActiveConversationTracker()
        val activeTrackerBob = ActiveConversationTracker()

        val chatReceiverAlice = ChatReceiver(alice.msgDao, alice.convDao, activeTrackerAlice)
        val chatReceiverBob = ChatReceiver(bob.msgDao, bob.convDao, activeTrackerBob)

        val receiptHandlerAlice = DeliveryReceiptHandler(alice.msgDao, TestOutboxDao(), alice.agent!!)
        val receiptHandlerBob = DeliveryReceiptHandler(bob.msgDao, TestOutboxDao(), bob.agent!!)

        val presenceServiceAlice = PresenceService(alice.connManager, alice.crypto, alice.agent!!, routeTableAlice, { "alice" })
        val presenceServiceBob = PresenceService(bob.connManager, bob.crypto, bob.agent!!, routeTableBob, { "bob" })

        val rxHandlerAlice = ReactionHandler(alice.rxDao, alice.msgDao)
        val rxHandlerBob = ReactionHandler(bob.rxDao, bob.msgDao)

        val editHandlerAlice = EditHandler(alice.msgDao, alice.convDao)
        val editHandlerBob = EditHandler(bob.msgDao, bob.convDao)

        val deleteHandlerAlice = DeleteHandler(alice.msgDao, alice.convDao)
        val deleteHandlerBob = DeleteHandler(bob.msgDao, bob.convDao)

        alice.incomingDispatcher = IncomingDispatcher(
            connectionManager = alice.connManager,
            sessionCrypto = alice.crypto,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = false
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) {}
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = chatReceiverAlice,
            deliveryReceiptHandler = receiptHandlerAlice,
            agent = alice.agent!!,
            localIdentityIdProvider = { "alice" },
            presenceHandler = PresenceHandler(presenceServiceAlice),
            typingHandler = TypingHandler(presenceServiceAlice),
            reactionHandler = rxHandlerAlice,
            editHandler = editHandlerAlice,
            deleteHandler = deleteHandlerAlice,
            transactionRunner = { it() }
        )

        bob.incomingDispatcher = IncomingDispatcher(
            connectionManager = bob.connManager,
            sessionCrypto = bob.crypto,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = false
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) {}
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = chatReceiverBob,
            deliveryReceiptHandler = receiptHandlerBob,
            agent = bob.agent!!,
            localIdentityIdProvider = { "bob" },
            presenceHandler = PresenceHandler(presenceServiceBob),
            typingHandler = TypingHandler(presenceServiceBob),
            reactionHandler = rxHandlerBob,
            editHandler = editHandlerBob,
            deleteHandler = deleteHandlerBob,
            transactionRunner = { it() }
        )

        alice.incomingHub = IncomingTransportHub(alice.incomingDispatcher!!)
        bob.incomingHub = IncomingTransportHub(bob.incomingDispatcher!!)

        alice.router.registerTransport(DirectLoopbackTransport(bob.incomingHub!!))
        bob.router.registerTransport(DirectLoopbackTransport(alice.incomingHub!!))

        alice.chatService = ChatService(
            sessionCrypto = alice.crypto,
            connectionManager = alice.connManager,
            agent = alice.agent!!,
            messageDao = alice.msgDao,
            conversationDao = alice.convDao,
            outboxDao = TestOutboxDao(),
            reactionDao = alice.rxDao,
            localMessageStateDao = alice.localStateDao,
            transactionRunner = { it() }
        )

        bob.chatService = ChatService(
            sessionCrypto = bob.crypto,
            connectionManager = bob.connManager,
            agent = bob.agent!!,
            messageDao = bob.msgDao,
            conversationDao = bob.convDao,
            outboxDao = TestOutboxDao(),
            reactionDao = bob.rxDao,
            localMessageStateDao = bob.localStateDao,
            transactionRunner = { it() }
        )

        alice.agent!!.start()
        bob.agent!!.start()

        return Pair(alice, bob)
    }

    // ── Tests ──

    @Test
    fun testDeleteForMe_outgoingMessage_localStateSavedAndNoWirePacketEmitted() = runBlocking {
        val (alice, bob) = createBilateralNodes()
        val convId = "conv-1"

        alice.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Bob"))
        val msgId = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Secret Outgoing")

        // Wait for delivery to Bob
        delay(200)
        assertNotNull(bob.msgDao.getById(msgId))

        // Alice performs Delete for Me
        val outboxCountBefore = alice.outboxStore.items.size
        val result = alice.chatService!!.deleteForMe(convId, msgId)
        assertTrue(result)

        // 1. Verify local_message_state is marked hidden locally
        val localState = alice.localStateDao.getByMessageId(msgId)
        assertNotNull(localState)
        assertTrue(localState!!.hiddenLocally)

        // 2. Zero network/wire packets emitted (outbox item count unchanged)
        assertEquals(outboxCountBefore, alice.outboxStore.items.size)

        // 3. Bob's state is completely untouched (still has message and body intact)
        val bobMsg = bob.msgDao.getById(msgId)
        assertNotNull(bobMsg)
        assertEquals("Secret Outgoing", bobMsg!!.body)
        assertNull(bobMsg.deletedAt)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testDeleteForMe_incomingMessage_localStateSavedAndPeerUntouched() = runBlocking {
        val (alice, bob) = createBilateralNodes()
        val convId = "conv-2"

        bob.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Alice"))
        alice.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Bob"))

        val msgId = bob.chatService!!.sendTextMessage(convId, bob.relationshipId, "bob", "alice", "Hello Alice from Bob")
        delay(200)

        // Alice received the message
        val aliceMsg = alice.msgDao.getById(msgId)
        assertNotNull(aliceMsg)

        val outboxCountAliceBefore = alice.outboxStore.items.size

        // Alice deletes Bob's incoming message FOR ME
        val result = alice.chatService!!.deleteForMe(convId, msgId)
        assertTrue(result)

        // Local state marked hidden
        val hiddenIds = alice.localStateDao.getHiddenMessageIds(convId)
        assertTrue(hiddenIds.contains(msgId))

        // Alice emitted ZERO wire packets
        assertEquals(outboxCountAliceBefore, alice.outboxStore.items.size)

        // Bob's message remains completely untouched
        val bobMsg = bob.msgDao.getById(msgId)
        assertNotNull(bobMsg)
        assertEquals("Hello Alice from Bob", bobMsg!!.body)
        assertNull(bobMsg.deletedAt)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testDeleteForMe_recalculatesConversationPreviewToLatestVisible() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val convId = "conv-preview-test"

        alice.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Bob"))

        val msg1 = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "First message")
        delay(50)
        val msg2 = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Second message (latest)")

        // Initially, conversation preview is msg2
        val convInitial = alice.convDao.getById(convId)
        assertNotNull(convInitial)
        assertEquals("Second message (latest)", convInitial!!.lastMessagePreview)
        assertEquals(msg2, convInitial.lastMessageId)

        // Alice deletes msg2 for me -> preview should revert to msg1
        alice.chatService!!.deleteForMe(convId, msg2)

        val convAfterDelete2 = alice.convDao.getById(convId)
        assertNotNull(convAfterDelete2)
        assertEquals("First message", convAfterDelete2!!.lastMessagePreview)
        assertEquals(msg1, convAfterDelete2.lastMessageId)

        // Alice deletes msg1 for me -> preview should be cleared
        alice.chatService!!.deleteForMe(convId, msg1)

        val convAfterDeleteAll = alice.convDao.getById(convId)
        assertNotNull(convAfterDeleteAll)
        assertNull(convAfterDeleteAll!!.lastMessagePreview)

        alice.agent!!.stop()
    }

    @Test
    fun testDeleteForEveryone_convertsToTombstoneAndSyncsToPeer() = runBlocking {
        val (alice, bob) = createBilateralNodes()
        val convId = "conv-everyone-test"

        alice.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Bob"))
        bob.convDao.upsert(ConversationEntity(conversationId = convId, type = ConversationType.DIRECT, title = "Alice"))

        val msgId = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Mistake message")
        delay(200)

        // Both have the message body
        assertEquals("Mistake message", alice.msgDao.getById(msgId)?.body)
        assertEquals("Mistake message", bob.msgDao.getById(msgId)?.body)

        // Alice deletes for everyone
        val deleted = alice.chatService!!.deleteForEveryone(convId, alice.relationshipId, "alice", "bob", msgId)
        assertTrue(deleted)

        // Alice's local message becomes a tombstone
        val aliceMsg = alice.msgDao.getById(msgId)
        assertNotNull(aliceMsg)
        assertNull(aliceMsg!!.body)
        assertNotNull(aliceMsg.deletedAt)

        // Wait for DELETE envelope delivery to Bob
        delay(300)

        // Bob's message is also converted to a tombstone
        val bobMsg = bob.msgDao.getById(msgId)
        assertNotNull(bobMsg)
        assertNull(bobMsg!!.body)
        assertNotNull(bobMsg.deletedAt)

        // Conversation preview updated to "This message was deleted"
        assertEquals("This message was deleted", alice.convDao.getById(convId)?.lastMessagePreview)
        assertEquals("This message was deleted", bob.convDao.getById(convId)?.lastMessagePreview)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testDeleteForEveryone_rejectsIfNotOriginalAuthor() = runBlocking {
        val (alice, bob) = createBilateralNodes()
        val convId = "conv-auth-test"

        val msgId = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Alice's Message")
        delay(200)

        // Bob attempts to delete Alice's message for everyone -> MUST BE REJECTED
        val bobDeleted = bob.chatService!!.deleteForEveryone(convId, bob.relationshipId, "bob", "alice", msgId)
        assertFalse(bobDeleted)

        // Verify message remains intact
        val aliceMsg = alice.msgDao.getById(msgId)
        assertEquals("Alice's Message", aliceMsg?.body)
        assertNull(aliceMsg?.deletedAt)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testDeleteForEveryone_rejectsEditOnDeletedMessage() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val convId = "conv-edit-del-test"

        val msgId = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Will be deleted")
        delay(100)

        alice.chatService!!.deleteForEveryone(convId, alice.relationshipId, "alice", "bob", msgId)

        // Attempting to edit a deleted message must be rejected
        val editResult = alice.chatService!!.editMessage(convId, alice.relationshipId, "alice", "bob", msgId, "Try to revive")
        assertFalse(editResult)

        val msg = alice.msgDao.getById(msgId)
        assertNull(msg?.body)
        assertNotNull(msg?.deletedAt)

        alice.agent!!.stop()
    }

    @Test
    fun testDeleteSemantics_viewModelFiltersHiddenAndHidesReactionsOnDeleted() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val convId = "conv-vm-test"

        val msg1 = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Message 1 (Visible)")
        delay(50)
        val msg2 = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Message 2 (Delete for me)")
        delay(50)
        val msg3 = alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Message 3 (Tombstone)")

        // Add reaction to msg3
        alice.rxDao.insertOrUpdate(
            ReactionEntity(messageId = msg3, conversationId = convId, senderId = "alice", emoji = "👍", createdAt = System.currentTimeMillis())
        )

        // Delete msg3 for everyone (tombstone)
        alice.chatService!!.deleteForEveryone(convId, alice.relationshipId, "alice", "bob", msg3)

        // Delete msg2 for me
        alice.chatService!!.deleteForMe(convId, msg2)

        val vm = ChatViewModel(
            conversationId = convId,
            relationshipId = alice.relationshipId,
            localIdentityId = "alice",
            recipientId = "bob",
            contactName = "Bob",
            chatService = alice.chatService!!
        )

        delay(100)
        val uiMessages = vm.uiState.value.messages

        // 1. msg2 must be completely absent from UI messages (filtered out by delete for me)
        assertFalse(uiMessages.any { it.logicalMessageId == msg2 })

        // 2. msg1 is present and normal
        val uiMsg1 = uiMessages.find { it.logicalMessageId == msg1 }
        assertNotNull(uiMsg1)
        assertEquals("Message 1 (Visible)", uiMsg1!!.body)
        assertFalse(uiMsg1.isDeleted)

        // 3. msg3 is present as a tombstone
        val uiMsg3 = uiMessages.find { it.logicalMessageId == msg3 }
        assertNotNull(uiMsg3)
        assertTrue(uiMsg3!!.isDeleted)

        // 4. Reactions on tombstoned message are suppressed in UI
        assertTrue(uiMsg3.reactions.isEmpty())

        alice.agent!!.stop()
    }
}
