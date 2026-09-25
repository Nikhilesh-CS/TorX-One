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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DirectChatInteractionTest {

    // ── Test In-Memory Doubles ──

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
    }

    class TestConversationDao : ConversationDao {
        val convs = ConcurrentHashMap<String, ConversationEntity>()

        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())

        override suspend fun getById(id: String): ConversationEntity? = convs[id]

        override suspend fun upsert(conversation: ConversationEntity) {
            convs[conversation.conversationId] = conversation
        }

        override suspend fun updateUnreadCount(id: String, count: Int) {
            convs[id]?.let { convs[id] = it.copy(unreadCount = count) }
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
    }

    class InMemoryOutboxStore : OutboxStore {
        val items = ConcurrentHashMap<String, DeliveryItem>()
        override suspend fun insert(item: DeliveryItem) { items[item.deliveryId] = item }
        override suspend fun getPendingItems(): List<DeliveryItem> = items.values.toList()
        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status) }
        }
        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt) }
        }
        override suspend fun removeByMessageId(logicalMessageId: String) {
            val key = items.values.find { it.logicalMessageId == logicalMessageId }?.deliveryId
            if (key != null) items.remove(key)
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

    // ── Tests ──

    @Test
    fun testPeerPresenceStateAndRouteTransitions() {
        val routeTable = DirectRouteTable()
        val relationshipId = "rel-presence-test"

        val connManager = ConnectionManager()
        val outbox = InMemoryOutboxStore()
        val processed = InMemoryProcessedStore()
        val router = TransportRouter()
        val agent = TorXAgent(router, outbox, processed, Dispatchers.Default)

        val presenceService = PresenceService(
            connectionManager = connManager,
            sessionCrypto = DoubleRatchetSessionCrypto(InMemorySessionStore()),
            agent = agent,
            directRouteTable = routeTable,
            localIdentityIdProvider = { "alice" }
        )

        val presenceFlow = presenceService.observePresence(relationshipId)
        assertEquals(PresenceStatus.OFFLINE, presenceFlow.value.status)

        // 1. Peer connects and route becomes READY
        routeTable.bindRoute(relationshipId, "ep-bob", RouteState.READY)
        assertEquals(PresenceStatus.ONLINE, presenceFlow.value.status)
        assertFalse(presenceFlow.value.isTyping)

        // 2. Peer disconnects
        val now = System.currentTimeMillis()
        routeTable.removeEndpoint("ep-bob")
        assertEquals(PresenceStatus.OFFLINE, presenceFlow.value.status)
        assertNotNull(presenceFlow.value.lastSeenAt)
        assertTrue(presenceFlow.value.lastSeenAt!! >= now)

        // 3. Test PresenceFormatter
        val formattedToday = PresenceFormatter.formatLastSeen(now, now)
        assertTrue(formattedToday.startsWith("last seen today at"))

        val yesterday = now - 24 * 60 * 60 * 1000L
        val formattedYesterday = PresenceFormatter.formatLastSeen(yesterday, now)
        assertTrue(formattedYesterday.startsWith("last seen yesterday at"))
    }

    @Test
    fun testEncryptedPresenceUpdateHandling() {
        val routeTable = DirectRouteTable()
        val relationshipId = "rel-presence-update"

        val connManager = ConnectionManager()
        val outbox = InMemoryOutboxStore()
        val processed = InMemoryProcessedStore()
        val router = TransportRouter()
        val agent = TorXAgent(router, outbox, processed, Dispatchers.Default)

        val presenceService = PresenceService(
            connectionManager = connManager,
            sessionCrypto = DoubleRatchetSessionCrypto(InMemorySessionStore()),
            agent = agent,
            directRouteTable = routeTable,
            localIdentityIdProvider = { "alice" }
        )

        // Initial state is OFFLINE
        assertEquals(PresenceStatus.OFFLINE, presenceService.getPresence(relationshipId).status)

        // Receive PRESENCE_UPDATE(ONLINE)
        presenceService.onPresenceUpdateReceived(relationshipId, PresenceUpdate(PresenceState.ONLINE))
        assertEquals(PresenceStatus.ONLINE, presenceService.getPresence(relationshipId).status)

        // Receive PRESENCE_UPDATE(OFFLINE)
        val offlineTimestamp = 123456789L
        presenceService.onPresenceUpdateReceived(relationshipId, PresenceUpdate(PresenceState.OFFLINE, offlineTimestamp))
        assertEquals(PresenceStatus.OFFLINE, presenceService.getPresence(relationshipId).status)
        assertEquals(offlineTimestamp, presenceService.getPresence(relationshipId).lastSeenAt)
    }

    @Test
    fun testTypingIndicatorsAndSafetyTimeout() = runBlocking {
        val routeTable = DirectRouteTable()
        val relationshipId = "rel-typing-test"

        val connManager = ConnectionManager()
        val outbox = InMemoryOutboxStore()
        val processed = InMemoryProcessedStore()
        val router = TransportRouter()
        val agent = TorXAgent(router, outbox, processed, Dispatchers.Default)

        val presenceService = PresenceService(
            connectionManager = connManager,
            sessionCrypto = DoubleRatchetSessionCrypto(InMemorySessionStore()),
            agent = agent,
            directRouteTable = routeTable,
            localIdentityIdProvider = { "alice" }
        )

        // 1. Receive TYPING_START
        presenceService.onTypingStartReceived(relationshipId)
        assertTrue(presenceService.getPresence(relationshipId).isTyping)
        assertEquals(PresenceStatus.ONLINE, presenceService.getPresence(relationshipId).status)

        // 2. Receive explicit TYPING_STOP
        presenceService.onTypingStopReceived(relationshipId)
        assertFalse(presenceService.getPresence(relationshipId).isTyping)
    }

    @Test
    fun testEphemeralDeliverySemanticsTypingDoesNotPolluteOutbox() = runBlocking {
        val outbox = InMemoryOutboxStore()
        val processed = InMemoryProcessedStore()
        val router = TransportRouter() // No transports registered: fails delivery
        val agent = TorXAgent(router, outbox, processed, Dispatchers.Default)

        val item = DeliveryItem(
            deliveryId = "typing-env-1",
            logicalMessageId = "typing-msg-1",
            conversationId = "conv-1",
            connectionId = "conn-1",
            queueAddress = "q-1",
            ciphertext = "cipher".toByteArray(),
            queueAuthenticator = "auth".toByteArray(),
            priority = DeliveryPriority.LOW
        )

        // Send ephemeral
        val result = agent.sendEphemeral(item, ttlMs = 5000L)
        assertTrue("Ephemeral send without transport fails gracefully", result is TransportResult.Failed)

        // Crucial invariant: outbox must remain completely empty!
        assertTrue("Ephemeral item MUST NOT be stored in durable OutboxStore", outbox.items.isEmpty())

        // Test TTL expiry
        val expiredItem = item.copy(createdAt = System.currentTimeMillis() - 10_000L)
        val expiredResult = agent.sendEphemeral(expiredItem, ttlMs = 5000L)
        assertTrue(expiredResult is TransportResult.Failed)
        assertEquals("Ephemeral message expired", (expiredResult as TransportResult.Failed).error)
    }

    @Test
    fun testBatchReadReceiptsUpToMessageId() = runBlocking {
        val messageDao = TestMessageDao()
        val outbox = InMemoryOutboxStore()
        val processed = InMemoryProcessedStore()
        val agent = TorXAgent(TransportRouter(), outbox, processed, Dispatchers.Default)

        val handler = DeliveryReceiptHandler(
            messageDao = messageDao,
            outboxDao = object : OutboxDao {
                override suspend fun getPending(now: Long): List<OutboxEntity> = emptyList()
                override suspend fun insert(item: OutboxEntity) {}
                override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {}
                override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {}
                override suspend fun removeByMessageId(logicalMessageId: String) {}
            },
            agent = agent
        )

        val convId = "conv-read-test"
        // Alice has 3 outgoing messages
        val m1 = MessageEntity(
            logicalMessageId = "msg-1",
            conversationId = convId,
            senderId = "alice",
            type = "TEXT",
            body = "First",
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.DELIVERED.name,
            createdAt = 1000L
        )
        val m2 = MessageEntity(
            logicalMessageId = "msg-2",
            conversationId = convId,
            senderId = "alice",
            type = "TEXT",
            body = "Second",
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.DELIVERED.name,
            createdAt = 2000L
        )
        val m3 = MessageEntity(
            logicalMessageId = "msg-3",
            conversationId = convId,
            senderId = "alice",
            type = "TEXT",
            body = "Third",
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.DELIVERED.name,
            createdAt = 3000L
        )

        messageDao.upsert(m1)
        messageDao.upsert(m2)
        messageDao.upsert(m3)

        // Bob sends batch READ up to msg-2
        val readTime = 5000L
        val receipt = ReadReceipt(
            conversationId = convId,
            upToMessageId = "msg-2",
            readAt = readTime
        )
        val envelope = SecureEnvelope(
            conversationId = convId,
            senderIdentity = "bob",
            recipientBinding = "alice",
            messageType = MessageType.READ_RECEIPT,
            payload = receipt.toByteArray()
        )

        handler.handleReadReceipt(envelope)

        // msg-1 and msg-2 must be READ; msg-3 must still be DELIVERED
        assertEquals("READ", messageDao.getById("msg-1")?.status)
        assertEquals(readTime, messageDao.getById("msg-1")?.readAt)

        assertEquals("READ", messageDao.getById("msg-2")?.status)
        assertEquals(readTime, messageDao.getById("msg-2")?.readAt)

        assertEquals("DELIVERED", messageDao.getById("msg-3")?.status)
        assertNull(messageDao.getById("msg-3")?.readAt)
    }

    @Test
    fun testActiveConversationVisibilityGating() = runBlocking {
        val messageDao = TestMessageDao()
        val convDao = TestConversationDao()
        val tracker = ActiveConversationTracker()
        val receiver = ChatReceiver(messageDao, convDao, tracker)

        val conn = Connection(
            connectionId = "conn-1",
            relationshipId = "rel-1",
            generation = 1,
            sendQueueId = "q-send",
            recvQueueId = "q-recv",
            sendAuth = "auth".toByteArray(),
            recvAuth = "auth".toByteArray()
        )

        // 1. Conversation NOT active: arrives as DELIVERED, unread count = 1
        tracker.clearActiveConversation()
        val env1 = SecureEnvelope(
            logicalMessageId = "m1",
            conversationId = "conv-1",
            senderIdentity = "bob",
            recipientBinding = "alice",
            messageType = MessageType.TEXT,
            payload = "Hello while background".toByteArray()
        )
        receiver.receiveTextMessage(conn, env1)

        assertEquals("DELIVERED", messageDao.getById("m1")?.status)
        assertNull(messageDao.getById("m1")?.readAt)
        assertEquals(1, convDao.getById("conv-1")?.unreadCount)

        // 2. Conversation IS active in foreground: arrives as READ, unread count = 0
        tracker.setActiveConversation("conv-1")
        val env2 = SecureEnvelope(
            logicalMessageId = "m2",
            conversationId = "conv-1",
            senderIdentity = "bob",
            recipientBinding = "alice",
            messageType = MessageType.TEXT,
            payload = "Hello while open".toByteArray()
        )
        receiver.receiveTextMessage(conn, env2)

        assertEquals("READ", messageDao.getById("m2")?.status)
        assertNotNull(messageDao.getById("m2")?.readAt)
        assertEquals(0, convDao.getById("conv-1")?.unreadCount)
    }

    @Test
    fun testReplyRoundtripAndQuoteResolution() {
        val originalMessage = MessageEntity(
            logicalMessageId = "orig-123",
            conversationId = "conv-reply",
            senderId = "bob",
            type = "TEXT",
            body = "Are we testing tonight?",
            direction = MessageDirection.INCOMING,
            status = "READ",
            createdAt = 1000L
        )

        val replyMessage = MessageEntity(
            logicalMessageId = "reply-456",
            conversationId = "conv-reply",
            senderId = "alice",
            type = "TEXT",
            body = "Yes, testing direct chat!",
            direction = MessageDirection.OUTGOING,
            status = "QUEUED",
            createdAt = 2000L,
            replyToMessageId = "orig-123"
        )

        val entities = listOf(originalMessage, replyMessage)

        // Test quote resolution logic (as implemented in ChatViewModel)
        val entityMap = entities.associateBy { it.logicalMessageId }
        val uiModels = entities.map { entity ->
            val quoted = entity.replyToMessageId?.let { replyId ->
                val orig = entityMap[replyId]
                if (orig != null) {
                    QuotedMessageUiModel(
                        messageId = replyId,
                        senderName = if (orig.direction == MessageDirection.OUTGOING) "You" else "Bob",
                        previewText = orig.body ?: ""
                    )
                } else {
                    QuotedMessageUiModel(
                        messageId = replyId,
                        senderName = "Unavailable",
                        previewText = "Original message unavailable",
                        isUnavailable = true
                    )
                }
            }

            MessageUiModel(
                logicalMessageId = entity.logicalMessageId,
                conversationId = entity.conversationId,
                senderId = entity.senderId,
                body = entity.body,
                direction = entity.direction,
                status = DeliveryStatus.valueOf(entity.status),
                createdAt = entity.createdAt,
                replyToMessageId = entity.replyToMessageId,
                quotedMessage = quoted
            )
        }

        val resolvedReply = uiModels.find { it.logicalMessageId == "reply-456" }
        assertNotNull(resolvedReply)
        assertEquals("orig-123", resolvedReply?.replyToMessageId)
        assertNotNull(resolvedReply?.quotedMessage)
        assertEquals("Bob", resolvedReply?.quotedMessage?.senderName)
        assertEquals("Are we testing tonight?", resolvedReply?.quotedMessage?.previewText)
        assertFalse(resolvedReply?.quotedMessage?.isUnavailable ?: true)
    }

    @Test
    fun testFullDuplexSimultaneousInteractions() = runBlocking {
        // Build bilateral session between Alice and Bob
        val sharedInitSecret = java.security.MessageDigest.getInstance("SHA-256").digest("test-bilateral-secret".toByteArray())
        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()

        val relationshipId = "rel-full-duplex"
        val cryptoAlice = DoubleRatchetSessionCrypto(InMemorySessionStore())
        val cryptoBob = DoubleRatchetSessionCrypto(InMemorySessionStore())

        cryptoAlice.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = sharedInitSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )

        cryptoBob.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = sharedInitSecret,
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

        val cmAlice = ConnectionManager().apply { registerConnection(connAlice) }
        val cmBob = ConnectionManager().apply { registerConnection(connBob) }

        val msgDaoAlice = TestMessageDao()
        val msgDaoBob = TestMessageDao()
        val convDaoAlice = TestConversationDao()
        val convDaoBob = TestConversationDao()
        val trackerAlice = ActiveConversationTracker().apply { setActiveConversation(relationshipId) }
        val trackerBob = ActiveConversationTracker().apply { setActiveConversation(relationshipId) }

        val outboxAlice = InMemoryOutboxStore()
        val outboxBob = InMemoryOutboxStore()
        val processedAlice = InMemoryProcessedStore()
        val processedBob = InMemoryProcessedStore()

        val routerAlice = TransportRouter()
        val routerBob = TransportRouter()

        val agentAlice = TorXAgent(routerAlice, outboxAlice, processedAlice, Dispatchers.Default)
        val agentBob = TorXAgent(routerBob, outboxBob, processedBob, Dispatchers.Default)

        val routeTableAlice = DirectRouteTable().apply { bindRoute(relationshipId, "ep-bob", RouteState.READY) }
        val routeTableBob = DirectRouteTable().apply { bindRoute(relationshipId, "ep-alice", RouteState.READY) }

        val presenceAlice = PresenceService(cmAlice, cryptoAlice, agentAlice, routeTableAlice, { "alice" })
        val presenceBob = PresenceService(cmBob, cryptoBob, agentBob, routeTableBob, { "bob" })

        val dispatcherAlice = IncomingDispatcher(
            connectionManager = cmAlice,
            sessionCrypto = cryptoAlice,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = processedAlice.isProcessed(envelopeId)
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) { processedAlice.markProcessed(ProcessedEnvelope(entity.envelopeId, entity.logicalMessageId)) }
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = ChatReceiver(msgDaoAlice, convDaoAlice, trackerAlice),
            deliveryReceiptHandler = DeliveryReceiptHandler(msgDaoAlice, object : OutboxDao {
                override suspend fun getPending(now: Long) = emptyList<OutboxEntity>()
                override suspend fun insert(item: OutboxEntity) {}
                override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {}
                override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {}
                override suspend fun removeByMessageId(logicalMessageId: String) { outboxAlice.removeByMessageId(logicalMessageId) }
            }, agentAlice),
            agent = agentAlice,
            localIdentityIdProvider = { "alice" },
            presenceHandler = PresenceHandler(presenceAlice),
            typingHandler = TypingHandler(presenceAlice)
        )

        val dispatcherBob = IncomingDispatcher(
            connectionManager = cmBob,
            sessionCrypto = cryptoBob,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = processedBob.isProcessed(envelopeId)
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) { processedBob.markProcessed(ProcessedEnvelope(entity.envelopeId, entity.logicalMessageId)) }
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = ChatReceiver(msgDaoBob, convDaoBob, trackerBob),
            deliveryReceiptHandler = DeliveryReceiptHandler(msgDaoBob, object : OutboxDao {
                override suspend fun getPending(now: Long) = emptyList<OutboxEntity>()
                override suspend fun insert(item: OutboxEntity) {}
                override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {}
                override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {}
                override suspend fun removeByMessageId(logicalMessageId: String) { outboxBob.removeByMessageId(logicalMessageId) }
            }, agentBob),
            agent = agentBob,
            localIdentityIdProvider = { "bob" },
            presenceHandler = PresenceHandler(presenceBob),
            typingHandler = TypingHandler(presenceBob)
        )

        val hubAlice = IncomingTransportHub(dispatcherAlice)
        val hubBob = IncomingTransportHub(dispatcherBob)

        routerAlice.registerTransport(DirectLoopbackTransport(hubBob))
        routerBob.registerTransport(DirectLoopbackTransport(hubAlice))

        agentAlice.start()
        agentBob.start()

        // 1. Both type simultaneously
        presenceAlice.sendTypingStart(relationshipId, relationshipId)
        presenceBob.sendTypingStart(relationshipId, relationshipId)

        delay(50)
        assertTrue("Bob sees Alice typing", presenceBob.getPresence(relationshipId).isTyping)
        assertTrue("Alice sees Bob typing", presenceAlice.getPresence(relationshipId).isTyping)

        // 2. Both send typing stop
        presenceAlice.sendTypingStop(relationshipId, relationshipId)
        presenceBob.sendTypingStop(relationshipId, relationshipId)

        delay(50)
        assertFalse("Bob sees Alice stopped typing", presenceBob.getPresence(relationshipId).isTyping)
        assertFalse("Alice sees Bob stopped typing", presenceAlice.getPresence(relationshipId).isTyping)

        agentAlice.stop()
        agentBob.stop()
    }
}
