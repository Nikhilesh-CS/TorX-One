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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessageActionsTest {

    // ── In-Memory Test Doubles ──

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

    class TestReactionDao : ReactionDao {
        val reactions = ConcurrentHashMap<String, ReactionEntity>()

        private fun key(messageId: String, senderId: String, emoji: String) = "$messageId:$senderId:$emoji"

        override suspend fun getForMessage(messageId: String): List<ReactionEntity> {
            return reactions.values.filter { it.messageId == messageId }
        }

        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> {
            return flowOf(reactions.values.filter { it.conversationId == conversationId })
        }

        override suspend fun insertOrUpdate(reaction: ReactionEntity) {
            reactions[key(reaction.messageId, reaction.senderId, reaction.emoji)] = reaction
        }

        override suspend fun remove(messageId: String, senderId: String, emoji: String) {
            reactions.remove(key(messageId, senderId, emoji))
        }

        override suspend fun removeAllFromSender(messageId: String, senderId: String) {
            reactions.values.removeAll { it.messageId == messageId && it.senderId == senderId }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            reactions.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class InMemoryOutboxStore : OutboxStore {
        private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)
        private val insertOrder = ConcurrentHashMap<String, Long>()
        val items = ConcurrentHashMap<String, DeliveryItem>()

        override suspend fun insert(item: DeliveryItem) {
            insertOrder.putIfAbsent(item.deliveryId, seqCounter.incrementAndGet())
            items[item.deliveryId] = item
        }

        override suspend fun getPendingItems(): List<DeliveryItem> {
            val now = System.currentTimeMillis()
            return items.values
                .filter {
                    (it.status == DeliveryStatus.QUEUED ||
                     it.status == DeliveryStatus.RETRY_WAIT ||
                     it.status == DeliveryStatus.TRANSMITTING ||
                     it.status == DeliveryStatus.TRANSPORT_ACCEPTED) &&
                    it.nextAttemptAt <= now
                }
                .sortedWith(
                    compareByDescending<DeliveryItem> { it.priority }
                        .thenBy { insertOrder[it.deliveryId] ?: 0L }
                        .thenBy { it.createdAt }
                )
        }

        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            val keys = items.values.filter { it.logicalMessageId == logicalMessageId }.map { it.deliveryId }
            for (key in keys) {
                items.remove(key)
                insertOrder.remove(key)
            }
        }
    }

    class InMemoryOutboxDao(val store: InMemoryOutboxStore) : OutboxDao {
        override suspend fun getPending(now: Long): List<OutboxEntity> {
            return store.getPendingItems().map {
                OutboxEntity(
                    deliveryId = it.deliveryId,
                    logicalMessageId = it.logicalMessageId,
                    conversationId = it.conversationId,
                    connectionId = it.connectionId,
                    queueAddress = it.queueAddress,
                    ciphertext = it.ciphertext,
                    queueAuthenticator = it.queueAuthenticator,
                    status = it.status.name,
                    priority = it.priority,
                    attemptCount = it.attemptCount,
                    nextAttemptAt = it.nextAttemptAt,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    expectsAck = it.expectsAck
                )
            }
        }

        override suspend fun insert(item: OutboxEntity) {
            store.insert(DeliveryItem(
                deliveryId = item.deliveryId,
                logicalMessageId = item.logicalMessageId,
                conversationId = item.conversationId,
                connectionId = item.connectionId,
                queueAddress = item.queueAddress,
                ciphertext = item.ciphertext,
                queueAuthenticator = item.queueAuthenticator,
                status = DeliveryStatus.valueOf(item.status),
                priority = item.priority,
                attemptCount = item.attemptCount,
                nextAttemptAt = item.nextAttemptAt,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                expectsAck = item.expectsAck
            ))
        }

        override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {
            store.updateStatus(deliveryId, DeliveryStatus.valueOf(status))
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {
            store.updateRetry(deliveryId, attemptCount, nextAttemptAt)
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            store.removeByMessageId(logicalMessageId)
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
        override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
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

    // ── Helper to setup complete bilateral Alice-Bob node pair ──

    class TestNode(
        val name: String,
        val peerName: String,
        val relationshipId: String,
        val crypto: DoubleRatchetSessionCrypto,
        val msgDao: TestMessageDao = TestMessageDao(),
        val convDao: TestConversationDao = TestConversationDao(),
        val rxDao: TestReactionDao = TestReactionDao(),
        val outboxStore: InMemoryOutboxStore = InMemoryOutboxStore(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(outboxStore),
        val processedStore: InMemoryProcessedStore = InMemoryProcessedStore(),
        val connManager: ConnectionManager = ConnectionManager(),
        val router: TransportRouter = TransportRouter(),
        var agent: TorXAgent? = null,
        var presenceService: PresenceService? = null,
        var chatService: ChatService? = null,
        var incomingDispatcher: IncomingDispatcher? = null,
        var incomingHub: IncomingTransportHub? = null
    )

    private suspend fun createBilateralNodes(relationshipId: String = "rel-action-test"): Pair<TestNode, TestNode> {
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

        alice.presenceService = PresenceService(alice.connManager, cryptoAlice, alice.agent!!, routeTableAlice, localIdentityIdProvider = { "alice" })
        bob.presenceService = PresenceService(bob.connManager, cryptoBob, bob.agent!!, routeTableBob, localIdentityIdProvider = { "bob" })

        val rxHandlerAlice = ReactionHandler(alice.rxDao, alice.msgDao)
        val rxHandlerBob = ReactionHandler(bob.rxDao, bob.msgDao)

        val editHandlerAlice = EditHandler(alice.msgDao, alice.convDao)
        val editHandlerBob = EditHandler(bob.msgDao, bob.convDao)

        val deleteHandlerAlice = DeleteHandler(alice.msgDao, alice.convDao)
        val deleteHandlerBob = DeleteHandler(bob.msgDao, bob.convDao)

        val trackerAlice = ActiveConversationTracker().apply { setActiveConversation(relationshipId) }
        val trackerBob = ActiveConversationTracker().apply { setActiveConversation(relationshipId) }

        alice.incomingDispatcher = IncomingDispatcher(
            connectionManager = alice.connManager,
            sessionCrypto = cryptoAlice,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = alice.processedStore.isProcessed(envelopeId)
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) { alice.processedStore.markProcessed(ProcessedEnvelope(entity.envelopeId, entity.logicalMessageId)) }
                override suspend fun getByEnvelopeId(envelopeId: String): ProcessedEnvelopeEntity? =
                    if (alice.processedStore.isProcessed(envelopeId)) ProcessedEnvelopeEntity(envelopeId, "test", System.currentTimeMillis()) else null
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = ChatReceiver(alice.msgDao, alice.convDao, trackerAlice),
            deliveryReceiptHandler = DeliveryReceiptHandler(alice.msgDao, alice.outboxDao, alice.agent!!),
            agent = alice.agent!!,
            localIdentityIdProvider = { "alice" },
            presenceHandler = PresenceHandler(alice.presenceService!!),
            typingHandler = TypingHandler(alice.presenceService!!),
            reactionHandler = rxHandlerAlice,
            editHandler = editHandlerAlice,
            deleteHandler = deleteHandlerAlice
        )

        bob.incomingDispatcher = IncomingDispatcher(
            connectionManager = bob.connManager,
            sessionCrypto = cryptoBob,
            processedEnvelopeDao = object : ProcessedEnvelopeDao {
                override suspend fun isProcessed(envelopeId: String) = bob.processedStore.isProcessed(envelopeId)
                override suspend fun isMessageProcessed(logicalMessageId: String) = false
                override suspend fun insert(entity: ProcessedEnvelopeEntity) { bob.processedStore.markProcessed(ProcessedEnvelope(entity.envelopeId, entity.logicalMessageId)) }
                override suspend fun getByEnvelopeId(envelopeId: String): ProcessedEnvelopeEntity? =
                    if (bob.processedStore.isProcessed(envelopeId)) ProcessedEnvelopeEntity(envelopeId, "test", System.currentTimeMillis()) else null
                override suspend fun pruneOlderThan(before: Long) {}
            },
            chatReceiver = ChatReceiver(bob.msgDao, bob.convDao, trackerBob),
            deliveryReceiptHandler = DeliveryReceiptHandler(bob.msgDao, bob.outboxDao, bob.agent!!),
            agent = bob.agent!!,
            localIdentityIdProvider = { "bob" },
            presenceHandler = PresenceHandler(bob.presenceService!!),
            typingHandler = TypingHandler(bob.presenceService!!),
            reactionHandler = rxHandlerBob,
            editHandler = editHandlerBob,
            deleteHandler = deleteHandlerBob
        )

        alice.incomingHub = IncomingTransportHub(alice.incomingDispatcher!!)
        bob.incomingHub = IncomingTransportHub(bob.incomingDispatcher!!)

        alice.router.registerTransport(DirectLoopbackTransport(bob.incomingHub!!))
        bob.router.registerTransport(DirectLoopbackTransport(alice.incomingHub!!))

        // Create ChatService on both ends
        alice.chatService = ChatService(
            sessionCrypto = cryptoAlice,
            connectionManager = alice.connManager,
            agent = alice.agent!!,
            messageDao = alice.msgDao,
            conversationDao = alice.convDao,
            outboxDao = alice.outboxDao,
            reactionDao = alice.rxDao
        )

        bob.chatService = ChatService(
            sessionCrypto = cryptoBob,
            connectionManager = bob.connManager,
            agent = bob.agent!!,
            messageDao = bob.msgDao,
            conversationDao = bob.convDao,
            outboxDao = bob.outboxDao,
            reactionDao = bob.rxDao
        )

        alice.agent!!.start()
        bob.agent!!.start()

        return Pair(alice, bob)
    }

    // ── Tests ──

    @Test
    fun testReactionAddAndRemove() = runBlocking {
        val (alice, bob) = createBilateralNodes()

        // 1. Alice sends message A1
        val msgId = "msg-a1"
        val now = System.currentTimeMillis()
        alice.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Hello Bob!",
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )
        bob.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Hello Bob!",
                direction = MessageDirection.INCOMING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )

        // 2. Bob reacts with ❤️ to A1
        bob.chatService!!.sendReaction(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "bob",
            recipientId = "alice",
            targetMessageId = msgId,
            emoji = "❤️",
            operation = ReactionOperation.ADD
        )

        // Give loopback time to deliver
        kotlinx.coroutines.delay(100)

        // Verify reaction is stored in Bob's DB
        val bobReactions = bob.rxDao.getForMessage(msgId)
        assertEquals(1, bobReactions.size)
        assertEquals("❤️", bobReactions[0].emoji)
        assertEquals("bob", bobReactions[0].senderId)

        // Verify reaction was received and stored in Alice's DB via wire
        val aliceReactions = alice.rxDao.getForMessage(msgId)
        assertEquals(1, aliceReactions.size)
        assertEquals("❤️", aliceReactions[0].emoji)
        assertEquals("bob", aliceReactions[0].senderId)

        // 3. Alice also reacts with 👍 to A1
        alice.chatService!!.sendReaction(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "alice",
            recipientId = "bob",
            targetMessageId = msgId,
            emoji = "👍",
            operation = ReactionOperation.ADD
        )

        kotlinx.coroutines.delay(100)

        val aliceAllReactions = alice.rxDao.getForMessage(msgId)
        assertEquals(2, aliceAllReactions.size)
        val bobAllReactions = bob.rxDao.getForMessage(msgId)
        assertEquals(2, bobAllReactions.size)

        // 4. Bob removes reaction ❤️
        bob.chatService!!.sendReaction(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "bob",
            recipientId = "alice",
            targetMessageId = msgId,
            emoji = "❤️",
            operation = ReactionOperation.REMOVE
        )

        kotlinx.coroutines.delay(100)

        // Bob's side only has Alice's 👍
        val bobRemaining = bob.rxDao.getForMessage(msgId)
        assertEquals(1, bobRemaining.size)
        assertEquals("👍", bobRemaining[0].emoji)
        assertEquals("alice", bobRemaining[0].senderId)

        // Alice's side only has Alice's 👍
        val aliceRemaining = alice.rxDao.getForMessage(msgId)
        assertEquals(1, aliceRemaining.size)
        assertEquals("👍", aliceRemaining[0].emoji)
        assertEquals("alice", aliceRemaining[0].senderId)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testEditOnlyOriginalSenderAllowed() = runBlocking {
        val (alice, bob) = createBilateralNodes()

        val msgId = "msg-a2"
        val now = System.currentTimeMillis()
        val origText = "Alice's original message"

        alice.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = origText,
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )
        bob.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = origText,
                direction = MessageDirection.INCOMING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )

        // 1. Bob tries to edit Alice's message locally via ChatService -> should be rejected
        val bobEditResult = bob.chatService!!.editMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "bob",
            recipientId = "alice",
            targetMessageId = msgId,
            newText = "Malicious edit by Bob"
        )
        assertFalse("Bob must NOT be able to edit Alice's message", bobEditResult)
        assertEquals(origText, bob.msgDao.getById(msgId)?.body)

        // 2. Direct envelope spoof test: Bob crafts an EDIT envelope with senderId = bob
        val spoofEnvelope = SecureEnvelope(
            conversationId = "rel-action-test",
            senderIdentity = "bob",
            recipientBinding = "alice",
            messageType = MessageType.EDIT,
            payload = MessageEdit(msgId, "Spoofed edit text", 1).toByteArray()
        )
        val editHandlerAlice = EditHandler(alice.msgDao, alice.convDao)
        val handled = editHandlerAlice.handleEdit(spoofEnvelope)
        assertFalse("EditHandler must reject edits where sender != original author", handled)
        assertEquals(origText, alice.msgDao.getById(msgId)?.body)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testEditVersionStrictIncrementAndDuplicateTolerance() = runBlocking {
        val (alice, bob) = createBilateralNodes()

        val msgId = "msg-a3"
        val now = System.currentTimeMillis()

        alice.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Version 0",
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now,
                editVersion = 0
            )
        )
        bob.msgDao.upsert(
            MessageEntity(
                logicalMessageId = msgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Version 0",
                direction = MessageDirection.INCOMING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now,
                editVersion = 0
            )
        )

        // 1. Alice edits to Version 1
        alice.chatService!!.editMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "alice",
            recipientId = "bob",
            targetMessageId = msgId,
            newText = "Version 1 text"
        )

        kotlinx.coroutines.delay(100)

        assertEquals("Version 1 text", alice.msgDao.getById(msgId)?.body)
        assertEquals(1, alice.msgDao.getById(msgId)?.editVersion)
        assertNotNull(alice.msgDao.getById(msgId)?.editedAt)

        assertEquals("Version 1 text", bob.msgDao.getById(msgId)?.body)
        assertEquals(1, bob.msgDao.getById(msgId)?.editVersion)

        // 2. Duplicate edit (version 1) arrives again -> harmless, ignored
        val editHandlerBob = EditHandler(bob.msgDao, bob.convDao)
        val dupEnvelope = SecureEnvelope(
            conversationId = "rel-action-test",
            senderIdentity = "alice",
            recipientBinding = "bob",
            messageType = MessageType.EDIT,
            payload = MessageEdit(msgId, "Duplicate version 1", 1).toByteArray()
        )
        val dupResult = editHandlerBob.handleEdit(dupEnvelope)
        assertFalse("Duplicate edit must be harmlessly ignored", dupResult)
        assertEquals("Version 1 text", bob.msgDao.getById(msgId)?.body)

        // 3. Older edit (version 0) arrives -> ignored
        val oldEnvelope = SecureEnvelope(
            conversationId = "rel-action-test",
            senderIdentity = "alice",
            recipientBinding = "bob",
            messageType = MessageType.EDIT,
            payload = MessageEdit(msgId, "Older version 0", 0).toByteArray()
        )
        val oldResult = editHandlerBob.handleEdit(oldEnvelope)
        assertFalse("Older edit must be ignored", oldResult)
        assertEquals("Version 1 text", bob.msgDao.getById(msgId)?.body)

        // 4. Valid higher version (version 2) -> accepted
        alice.chatService!!.editMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "alice",
            recipientId = "bob",
            targetMessageId = msgId,
            newText = "Version 2 final text"
        )

        kotlinx.coroutines.delay(100)
        assertEquals("Version 2 final text", bob.msgDao.getById(msgId)?.body)
        assertEquals(2, bob.msgDao.getById(msgId)?.editVersion)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testDeleteTombstonePreservesRowAndReplies() = runBlocking {
        val (alice, bob) = createBilateralNodes()

        val rootMsgId = "msg-root"
        val replyMsgId = "msg-reply"
        val now = System.currentTimeMillis()

        // 1. Alice sends root message
        alice.msgDao.upsert(
            MessageEntity(
                logicalMessageId = rootMsgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Will be deleted",
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )
        bob.msgDao.upsert(
            MessageEntity(
                logicalMessageId = rootMsgId,
                conversationId = "rel-action-test",
                senderId = "alice",
                type = MessageType.TEXT.name,
                body = "Will be deleted",
                direction = MessageDirection.INCOMING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now
            )
        )

        // 2. Bob replies to root message
        bob.msgDao.upsert(
            MessageEntity(
                logicalMessageId = replyMsgId,
                conversationId = "rel-action-test",
                senderId = "bob",
                type = MessageType.TEXT.name,
                body = "I am replying to root",
                direction = MessageDirection.OUTGOING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = now + 100,
                replyToMessageId = rootMsgId
            )
        )

        // 3. Bob attempts to delete Alice's message -> rejected
        val bobDeleteResult = bob.chatService!!.deleteMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "bob",
            recipientId = "alice",
            targetMessageId = rootMsgId
        )
        assertFalse("Bob cannot delete Alice's message", bobDeleteResult)
        assertNotNull(alice.msgDao.getById(rootMsgId)?.body)

        // 4. Alice deletes her root message
        val aliceDeleteResult = alice.chatService!!.deleteMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "alice",
            recipientId = "bob",
            targetMessageId = rootMsgId
        )
        assertTrue(aliceDeleteResult)

        kotlinx.coroutines.delay(100)

        // Verify tombstone on Alice's side:
        val aliceRoot = alice.msgDao.getById(rootMsgId)
        assertNotNull("Row must still exist in DB (tombstone)", aliceRoot)
        assertNull("Body must be nullified", aliceRoot?.body)
        assertNotNull("deletedAt must be recorded", aliceRoot?.deletedAt)

        // Verify tombstone on Bob's side:
        val bobRoot = bob.msgDao.getById(rootMsgId)
        assertNotNull("Row must still exist in Bob's DB", bobRoot)
        assertNull("Body must be nullified in Bob's DB", bobRoot?.body)
        assertNotNull("deletedAt must be recorded in Bob's DB", bobRoot?.deletedAt)

        // 5. Verify cannot edit a deleted message
        val editAfterDelete = alice.chatService!!.editMessage(
            conversationId = "rel-action-test",
            relationshipId = "rel-action-test",
            localIdentityId = "alice",
            recipientId = "bob",
            targetMessageId = rootMsgId,
            newText = "Trying to revive with edit"
        )
        assertFalse("Cannot edit a tombstone message", editAfterDelete)

        // 6. Test idempotency of delete
        val deleteHandlerBob = DeleteHandler(bob.msgDao, bob.convDao)
        val repeatDeleteEnv = SecureEnvelope(
            conversationId = "rel-action-test",
            senderIdentity = "alice",
            recipientBinding = "bob",
            messageType = MessageType.DELETE,
            payload = MessageDelete(rootMsgId).toByteArray()
        )
        val repeatResult = deleteHandlerBob.handleDelete(repeatDeleteEnv)
        assertTrue("Subsequent delete for tombstone is idempotent", repeatResult)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }

    @Test
    fun testFullDuplexInterleavedTrafficWithMessageActions() = runBlocking {
        // Stress test the exact scenario described by user:
        // Alice edits A3
        // Bob reacts ❤️ to A1
        // Alice sends A4
        // Bob sends B6
        // Bob deletes B2
        // ACKs + typing continue
        val (alice, bob) = createBilateralNodes()
        val now = System.currentTimeMillis()

        // Seed initial messages: A1, A3 by Alice, B2 by Bob
        alice.msgDao.upsert(MessageEntity("A1", "rel-action-test", "alice", "TEXT", "Alice 1", MessageDirection.OUTGOING, "DELIVERED", now))
        bob.msgDao.upsert(MessageEntity("A1", "rel-action-test", "alice", "TEXT", "Alice 1", MessageDirection.INCOMING, "DELIVERED", now))

        alice.msgDao.upsert(MessageEntity("B2", "rel-action-test", "bob", "TEXT", "Bob 2", MessageDirection.INCOMING, "DELIVERED", now + 10))
        bob.msgDao.upsert(MessageEntity("B2", "rel-action-test", "bob", "TEXT", "Bob 2", MessageDirection.OUTGOING, "DELIVERED", now + 10))

        alice.msgDao.upsert(MessageEntity("A3", "rel-action-test", "alice", "TEXT", "Alice 3 orig", MessageDirection.OUTGOING, "DELIVERED", now + 20))
        bob.msgDao.upsert(MessageEntity("A3", "rel-action-test", "alice", "TEXT", "Alice 3 orig", MessageDirection.INCOMING, "DELIVERED", now + 20))

        // Interleaved execution:
        // 1. Alice edits A3
        alice.chatService!!.editMessage("rel-action-test", "rel-action-test", "alice", "bob", "A3", "Alice 3 edited")

        // 2. Bob reacts ❤️ to A1
        bob.chatService!!.sendReaction("rel-action-test", "rel-action-test", "bob", "alice", "A1", "❤️", ReactionOperation.ADD)

        // 3. Alice sends A4
        alice.chatService!!.sendTextMessage("rel-action-test", "rel-action-test", "alice", "bob", "Alice 4 text")

        // 4. Bob sends B6
        bob.chatService!!.sendTextMessage("rel-action-test", "rel-action-test", "bob", "alice", "Bob 6 text")

        // 5. Bob deletes B2
        bob.chatService!!.deleteMessage("rel-action-test", "rel-action-test", "bob", "alice", "B2")

        // 6. Typing events interleaved
        alice.presenceService!!.sendTypingStart("rel-action-test", "rel-action-test")
        bob.presenceService!!.sendTypingStart("rel-action-test", "rel-action-test")

        // Allow loopback transport to process all full-duplex traffic
        kotlinx.coroutines.delay(500)

        // Verify A3 edit on Bob's side
        assertEquals("Alice 3 edited", bob.msgDao.getById("A3")?.body)
        assertEquals(1, bob.msgDao.getById("A3")?.editVersion)

        // Verify A1 reaction on Alice's side
        val a1ReactionsOnAlice = alice.rxDao.getForMessage("A1")
        assertEquals(1, a1ReactionsOnAlice.size)
        assertEquals("❤️", a1ReactionsOnAlice[0].emoji)
        assertEquals("bob", a1ReactionsOnAlice[0].senderId)

        // Verify A4 received by Bob
        val bobA4 = bob.msgDao.messages.values.find { it.body == "Alice 4 text" }
        assertNotNull("Bob must receive A4", bobA4)

        // Verify B6 received by Alice
        val aliceB6 = alice.msgDao.messages.values.find { it.body == "Bob 6 text" }
        assertNotNull("Alice must receive B6", aliceB6)

        // Verify B2 tombstoned on both sides
        assertNull("B2 body must be null on Bob", bob.msgDao.getById("B2")?.body)
        assertNotNull("B2 deletedAt set on Bob", bob.msgDao.getById("B2")?.deletedAt)
        assertNull("B2 body must be null on Alice", alice.msgDao.getById("B2")?.body)
        assertNotNull("B2 deletedAt set on Alice", alice.msgDao.getById("B2")?.deletedAt)

        // Verify ratchet state did not corrupt and is still synchronized
        val textAfterStress = "Post-stress test verification"
        alice.chatService!!.sendTextMessage("rel-action-test", "rel-action-test", "alice", "bob", textAfterStress)

        kotlinx.coroutines.delay(250)
        val bobReceivedPostStress = bob.msgDao.messages.values.find { it.body == textAfterStress }
        assertNotNull("Ratchet must remain fully operational after duplex actions", bobReceivedPostStress)

        alice.agent!!.stop()
        bob.agent!!.stop()
    }
}
