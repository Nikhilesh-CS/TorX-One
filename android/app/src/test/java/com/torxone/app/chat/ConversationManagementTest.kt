package com.torxone.app.chat

import com.torxone.app.agent.*
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.conversations.ConversationListViewModel
import com.torxone.app.conversations.ConversationUiModel
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionState
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.*
import com.torxone.app.notifications.NotificationPolicy
import com.torxone.app.protocol.*
import com.torxone.app.transport.*
import com.torxone.app.transport.nearby.DirectRouteTable
import com.torxone.app.transport.nearby.RouteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationManagementTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    class TestContactDao : ContactDao {
        val contacts = ConcurrentHashMap<String, ContactEntity>()

        override fun observeAll(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun getById(id: String): ContactEntity? = contacts[id]
        override suspend fun getByRelationshipId(relationshipId: String): ContactEntity? =
            contacts.values.find { it.relationshipId == relationshipId }
        override suspend fun getByConversationId(conversationId: String): ContactEntity? =
            contacts.values.find { it.conversationId == conversationId }
        override suspend fun getAll(): List<ContactEntity> = contacts.values.toList()
        override suspend fun upsert(contact: ContactEntity) { contacts[contact.contactId] = contact }
    }

    class InMemorySessionStore : com.torxone.app.crypto.SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
        override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
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

    class DirectLoopbackTransport(val destinationHub: IncomingTransportHub) : Transport {
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

    private suspend fun createBilateralNodes(relationshipId: String = "rel-conv-test"): Pair<TestNode, TestNode> {
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

        val activeTrackerAlice = ActiveConversationTracker()
        val activeTrackerBob = ActiveConversationTracker()

        val chatReceiverAlice = ChatReceiver(alice.msgDao, alice.convDao, activeTrackerAlice)
        val chatReceiverBob = ChatReceiver(bob.msgDao, bob.convDao, activeTrackerBob)

        val receiptHandlerAlice = DeliveryReceiptHandler(alice.msgDao, TestOutboxDao(), alice.agent!!)
        val receiptHandlerBob = DeliveryReceiptHandler(bob.msgDao, TestOutboxDao(), bob.agent!!)

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

    @Test
    fun testPinOrderingAndUnpin() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao

        val c1 = ConversationEntity(conversationId = "conv_1", title = "Alice", lastMessageTime = 100L)
        val c2 = ConversationEntity(conversationId = "conv_2", title = "Bob", lastMessageTime = 200L)
        val c3 = ConversationEntity(conversationId = "conv_3", title = "Charlie", lastMessageTime = 300L)
        convDao.upsert(c1)
        convDao.upsert(c2)
        convDao.upsert(c3)

        // Initial order by lastMessageTime DESC -> Charlie (300), Bob (200), Alice (100)
        var active = convDao.observeActive().first()
        assertEquals(listOf("conv_3", "conv_2", "conv_1"), active.map { it.conversationId })

        // Pin Bob
        chatService.setChatPinned("conv_2", true)
        active = convDao.observeActive().first()
        assertEquals("conv_2", active[0].conversationId)
        assertTrue(active[0].isPinned)
        assertNotNull(active[0].pinnedAt)

        // Pin Alice (small delay to ensure distinct pinnedAt timestamp across fast clock ticks)
        kotlinx.coroutines.delay(25)
        chatService.setChatPinned("conv_1", true)
        active = convDao.observeActive().first()
        assertEquals("conv_1", active[0].conversationId)
        assertEquals("conv_2", active[1].conversationId)
        assertEquals("conv_3", active[2].conversationId)

        // Unpin Bob
        chatService.setChatPinned("conv_2", false)
        active = convDao.observeActive().first()
        assertEquals("conv_1", active[0].conversationId)
        assertEquals("conv_3", active[1].conversationId)
        assertEquals("conv_2", active[2].conversationId)
    }

    @Test
    fun testArchiveAndUnarchive() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao

        val c1 = ConversationEntity(conversationId = "conv_1", title = "Alice")
        val c2 = ConversationEntity(conversationId = "conv_2", title = "Bob")
        convDao.upsert(c1)
        convDao.upsert(c2)

        assertEquals(2, convDao.observeActive().first().size)
        assertEquals(0, convDao.observeArchivedCount().first())

        // Archive Bob
        chatService.setChatArchived("conv_2", true)

        val active = convDao.observeActive().first()
        val archived = convDao.observeArchived().first()
        val archivedCount = convDao.observeArchivedCount().first()

        assertEquals(1, active.size)
        assertEquals("conv_1", active[0].conversationId)

        assertEquals(1, archived.size)
        assertEquals("conv_2", archived[0].conversationId)
        assertTrue(archived[0].isArchived)
        assertNotNull(archived[0].archivedAt)
        assertEquals(1, archivedCount)

        // Unarchive Bob
        chatService.setChatArchived("conv_2", false)

        assertEquals(2, convDao.observeActive().first().size)
        assertEquals(0, convDao.observeArchived().first().size)
        assertEquals(0, convDao.observeArchivedCount().first())
    }

    @Test
    fun testOutgoingMessageAutoUnarchives() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao

        val c1 = ConversationEntity(
            conversationId = "conv_1",
            title = "Bob",
            isArchived = true,
            archivedAt = System.currentTimeMillis()
        )
        convDao.upsert(c1)

        assertTrue(convDao.getById("conv_1")!!.isArchived)

        // Send a text message from Alice to Bob
        chatService.sendTextMessage(
            conversationId = "conv_1",
            relationshipId = alice.relationshipId,
            localIdentityId = "alice",
            recipientId = "bob",
            text = "Hey Bob!"
        )

        val updated = convDao.getById("conv_1")!!
        assertFalse("Conversation must auto-unarchive on sendTextMessage", updated.isArchived)
        assertNull(updated.archivedAt)
    }

    @Test
    fun testIncomingMessageAutoUnarchives() = runBlocking {
        val (alice, bob) = createBilateralNodes()

        // Alice archives conversation with Bob
        val convAlice = ConversationEntity(
            conversationId = "conv_ab",
            title = "Bob",
            isArchived = true,
            archivedAt = 12345L,
            unreadCount = 0
        )
        alice.convDao.upsert(convAlice)
        assertTrue(alice.convDao.getById("conv_ab")!!.isArchived)

        // Bob sends message to Alice
        bob.chatService!!.sendTextMessage(
            conversationId = "conv_ab",
            relationshipId = bob.relationshipId,
            localIdentityId = "bob",
            recipientId = "alice",
            text = "Are you there Alice?"
        )

        // Wait briefly for direct loopback delivery
        delay(200)

        val updatedAlice = alice.convDao.getById("conv_ab")!!
        assertFalse("Conversation must auto-unarchive upon receiving incoming message", updatedAlice.isArchived)
        assertNull(updatedAlice.archivedAt)
        assertEquals(1, updatedAlice.unreadCount)
    }

    @Test
    fun testMuteControls() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao

        val c = ConversationEntity(conversationId = "conv_1", title = "Bob")
        convDao.upsert(c)

        // Not muted initially
        assertFalse(NotificationPolicy.isConversationMuted(convDao.getById("conv_1")!!.mutedUntil))

        // Mute 8 hours
        val eightHoursMs = System.currentTimeMillis() + 8 * 3600_000L
        chatService.setChatMuted("conv_1", eightHoursMs)
        val mutedConv = convDao.getById("conv_1")!!
        assertTrue(NotificationPolicy.isConversationMuted(mutedConv.mutedUntil))

        // Mute Always
        chatService.setChatMuted("conv_1", Long.MAX_VALUE)
        val alwaysMuted = convDao.getById("conv_1")!!
        assertEquals(Long.MAX_VALUE, alwaysMuted.mutedUntil)
        assertTrue(NotificationPolicy.isConversationMuted(alwaysMuted.mutedUntil))

        // Unmute
        chatService.setChatMuted("conv_1", null)
        val unmuted = convDao.getById("conv_1")!!
        assertNull(unmuted.mutedUntil)
        assertFalse(NotificationPolicy.isConversationMuted(unmuted.mutedUntil))

        // Expired mute in past
        convDao.setMutedUntil("conv_1", System.currentTimeMillis() - 5000L)
        assertFalse(NotificationPolicy.isConversationMuted(convDao.getById("conv_1")!!.mutedUntil))
    }

    @Test
    fun testMarkUnreadIsLocalOnly() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao

        val c = ConversationEntity(
            conversationId = "conv_1",
            title = "Bob",
            unreadCount = 0,
            manuallyUnread = false
        )
        convDao.upsert(c)

        val outboxInitialCount = alice.outboxStore.items.size

        chatService.markChatUnread("conv_1")

        val conv = convDao.getById("conv_1")!!
        assertTrue("manuallyUnread must be true", conv.manuallyUnread)
        assertEquals("Zero packets must be queued for local mark-unread", outboxInitialCount, alice.outboxStore.items.size)

        // Mark read clears both
        chatService.markConversationRead("conv_1")
        val clearedConv = convDao.getById("conv_1")!!
        assertFalse("manuallyUnread must be reset to false", clearedConv.manuallyUnread)
        assertEquals(0, clearedConv.unreadCount)
    }

    @Test
    fun testDeleteChatPreservesContactAndSession() = runBlocking {
        val (alice, bob) = createBilateralNodes()
        val convId = "conv_ab"

        alice.convDao.upsert(ConversationEntity(conversationId = convId, title = "Bob"))
        bob.convDao.upsert(ConversationEntity(conversationId = convId, title = "Alice"))

        // Send a message back and forth so session is advanced
        alice.chatService!!.sendTextMessage(convId, alice.relationshipId, "alice", "bob", "Hello Bob")
        delay(150)
        bob.chatService!!.sendTextMessage(convId, bob.relationshipId, "bob", "alice", "Hello Alice")
        delay(150)

        assertTrue(alice.msgDao.getMessagesForConversationDesc(convId).isNotEmpty())

        // Alice deletes chat locally
        alice.chatService!!.deleteChatLocally(convId)

        // Assert local conversation and messages are removed for Alice
        assertNull("Conversation record must be deleted", alice.convDao.getById(convId))
        assertTrue("Messages must be deleted", alice.msgDao.getMessagesForConversationDesc(convId).isEmpty())

        // Cryptographic session in Alice's crypto is still alive and intact!
        val sessionBeforeNewMsg = alice.crypto.hasSession(alice.relationshipId)
        assertTrue("Session must NOT be destroyed by local chat deletion", sessionBeforeNewMsg)

        // Now Bob sends another message to Alice
        bob.chatService!!.sendTextMessage(convId, bob.relationshipId, "bob", "alice", "Can you still hear me?")
        delay(200)

        // Alice successfully decrypts and automatically recreates conversation record!
        val newConvAlice = alice.convDao.getById(convId)
        assertNotNull("Incoming message after delete chat must successfully decrypt and recreate conversation", newConvAlice)
        val aliceMessages = alice.msgDao.getMessagesForConversationDesc(convId)
        assertEquals(1, aliceMessages.size)
        assertEquals("Can you still hear me?", aliceMessages[0].body)
    }

    @Test
    fun testSearchByTitleAndMessageText() = runBlocking {
        val convDao = TestConversationDao()
        val c1 = ConversationEntity(conversationId = "c1", title = "Alice Wonderland", lastMessagePreview = "See you tomorrow")
        val c2 = ConversationEntity(conversationId = "c2", title = "Bob Builder", lastMessagePreview = "Can we fix it?")
        val c3 = ConversationEntity(conversationId = "c3", title = "Charlie", lastMessagePreview = "Classified secret")

        convDao.upsert(c1)
        convDao.upsert(c2)
        convDao.upsert(c3)

        // Search title
        val r1 = convDao.searchConversations("Alice").first()
        assertEquals(1, r1.size)
        assertEquals("c1", r1[0].conversationId)

        // Search message preview
        val r2 = convDao.searchConversations("fix").first()
        assertEquals(1, r2.size)
        assertEquals("c2", r2[0].conversationId)

        // Case insensitive
        val r3 = convDao.searchConversations("BUILDER").first()
        assertEquals(1, r3.size)
        assertEquals("c2", r3[0].conversationId)

        val r4 = convDao.searchConversations("nonexistent").first()
        assertTrue(r4.isEmpty())
    }

    @Test
    fun testConversationUiModelMapping() {
        val conv = ConversationEntity(
            conversationId = "c1",
            title = "Alice",
            lastMessagePreview = "Hello there",
            lastMessageTime = 123456789L,
            unreadCount = 3,
            manuallyUnread = true,
            isPinned = true,
            pinnedAt = 100L,
            isArchived = false,
            mutedUntil = Long.MAX_VALUE
        )

        val lastMsg = MessageEntity(
            logicalMessageId = "m1",
            conversationId = "c1",
            senderId = "me",
            type = "TEXT",
            body = "Hello there",
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.DELIVERED.name
        )

        val uiModel = ConversationUiModel.from(conv, lastMsg)

        assertEquals("c1", uiModel.conversationId)
        assertEquals("Alice", uiModel.title)
        assertEquals("Hello there", uiModel.preview)
        assertEquals(3, uiModel.unreadCount)
        assertTrue(uiModel.manuallyUnread)
        assertTrue(uiModel.isPinned)
        assertFalse(uiModel.isArchived)
        assertTrue(uiModel.isMuted)
        assertEquals(DeliveryStatus.DELIVERED, uiModel.lastMessageStatus)
        assertTrue(uiModel.isLastMessageOutgoing)

        // Test deleted message tombstone
        val deletedMsg = lastMsg.copy(body = null, deletedAt = 999L)
        val deletedUi = ConversationUiModel.from(conv, deletedMsg)
        assertEquals("This message was deleted", deletedUi.preview)
    }

    @Test
    fun testViewModelSearchAndFiltering() = runBlocking {
        val (alice, _) = createBilateralNodes()
        val chatService = alice.chatService!!
        val convDao = alice.convDao
        val msgDao = alice.msgDao

        val c1 = ConversationEntity(conversationId = "c1", title = "Alice", lastMessagePreview = "Hey", lastMessageTime = 1000L)
        val c2 = ConversationEntity(conversationId = "c2", title = "Bob", lastMessagePreview = "Hi", lastMessageTime = 2000L, isArchived = true, archivedAt = 500L)
        convDao.upsert(c1)
        convDao.upsert(c2)

        val vm = ConversationListViewModel(chatService, msgDao)

        val state = vm.uiState.first { it.conversations.isNotEmpty() && it.archivedConversations.isNotEmpty() }
        assertEquals(1, state.conversations.size)
        assertEquals("c1", state.conversations[0].conversationId)
        assertEquals(1, state.archivedConversations.size)
        assertEquals("c2", state.archivedConversations[0].conversationId)
        assertEquals(1, state.archivedCount)

        // Toggle search
        vm.toggleSearch(true)
        assertTrue(vm.uiState.first { it.isSearching }.isSearching)

        vm.onSearchQueryChanged("Alice")
        assertEquals("Alice", vm.uiState.first { it.searchQuery == "Alice" }.searchQuery)
    }
}
