package com.torxone.app.agent

import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionState
import com.torxone.app.crypto.SessionStore
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.dao.ProcessedEnvelopeDao
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.data.entity.ProcessedEnvelopeEntity
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.identity.TorXIdentity
import com.torxone.app.incoming.*
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.relationship.RelationshipService
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EndToEndPipelineTest {

    class InMemoryOutboxStore : OutboxStore {
        val items = ConcurrentHashMap<String, DeliveryItem>()

        override suspend fun insert(item: DeliveryItem) {
            items[item.deliveryId] = item
        }

        override suspend fun getPendingItems(): List<DeliveryItem> {
            val now = System.currentTimeMillis()
            return items.values.filter {
                (it.status == DeliveryStatus.QUEUED ||
                 it.status == DeliveryStatus.RETRY_WAIT ||
                 it.status == DeliveryStatus.TRANSMITTING ||
                 it.status == DeliveryStatus.TRANSPORT_ACCEPTED) &&
                it.nextAttemptAt <= now
            }
        }

        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            val key = items.values.find { it.logicalMessageId == logicalMessageId }?.deliveryId
            if (key != null) items.remove(key)
        }
    }

    class InMemoryProcessedStore : ProcessedEnvelopeStore, ProcessedEnvelopeDao {
        val envelopes = ConcurrentHashMap.newKeySet<String>()

        override suspend fun isProcessed(envelopeId: String): Boolean = envelopes.contains(envelopeId)
        override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = false
        override suspend fun markProcessed(record: ProcessedEnvelope) { envelopes.add(record.envelopeId) }
        override suspend fun insert(entity: ProcessedEnvelopeEntity) { envelopes.add(entity.envelopeId) }
        override suspend fun pruneOlderThan(before: Long) {}
    }

    class InMemorySessionStore : SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
    }

    class InMemoryMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()
        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId })
        override suspend fun getById(messageId: String): MessageEntity? = messages[messageId]
        override suspend fun exists(messageId: String): Boolean = messages.containsKey(messageId)
        override suspend fun insertIfAbsent(message: MessageEntity): Long {
            return if (messages.putIfAbsent(message.logicalMessageId, message) == null) 1L else -1L
        }
        override suspend fun upsert(message: MessageEntity) { messages[message.logicalMessageId] = message }
        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }
        override suspend fun markDelivered(messageId: String, status: String, deliveredAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status, deliveredAt = deliveredAt) }
        }
        override suspend fun markRead(messageId: String, status: String, readAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status, readAt = readAt) }
        }
    }

    class InMemoryConversationDao : ConversationDao {
        val convs = ConcurrentHashMap<String, ConversationEntity>()
        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())
        override suspend fun getById(id: String): ConversationEntity? = convs[id]
        override suspend fun upsert(conversation: ConversationEntity) { convs[conversation.conversationId] = conversation }
        override suspend fun updateUnreadCount(id: String, count: Int) {
            convs[id]?.let { convs[id] = it.copy(unreadCount = count) }
        }
        override suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String?, time: Long) {
            convs[conversationId]?.let { convs[conversationId] = it.copy(lastMessageId = messageId, lastMessagePreview = preview, lastMessageTime = time) }
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
                    attemptCount = it.attemptCount,
                    nextAttemptAt = it.nextAttemptAt,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
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
                attemptCount = item.attemptCount,
                nextAttemptAt = item.nextAttemptAt,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt
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

    class Node(val name: String) {
        val identity: TorXIdentity
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val outboxStore = InMemoryOutboxStore()
        val outboxDao = InMemoryOutboxDao(outboxStore)
        val processedDao = InMemoryProcessedStore()
        val sessionStore = InMemorySessionStore()
        val sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)
        val connectionManager = ConnectionManager()
        val activeTracker = ActiveConversationTracker()
        val transportRouter = TransportRouter()
        val fakeTransport = FakeTransport()
        val agent = TorXAgent(
            transportRouter = transportRouter,
            outboxStore = outboxStore,
            processedStore = processedDao,
            coroutineDispatcher = Dispatchers.Default,
            baseRetryDelayMs = 200L,
            outboxPollIntervalMs = 50L
        )

        val chatReceiver = ChatReceiver(messageDao, conversationDao, activeTracker)
        val receiptHandler = DeliveryReceiptHandler(messageDao, outboxDao, agent)
        val dispatcher = IncomingDispatcher(
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            processedEnvelopeDao = processedDao,
            chatReceiver = chatReceiver,
            deliveryReceiptHandler = receiptHandler,
            agent = agent,
            localIdentityIdProvider = { identity.identityId }
        )
        val incomingHub = IncomingTransportHub(dispatcher)

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
            transportRouter.registerTransport(fakeTransport)
            agent.start()
        }

        fun stop() {
            agent.stop()
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    private suspend fun setupAliceAndBobNodes(): Pair<Node, Node> {
        val alice = Node("Alice")
        val bob = Node("Bob")

        // Connect fake transports bidirectionally
        alice.fakeTransport.peerHub = bob.incomingHub
        bob.fakeTransport.peerHub = alice.incomingHub

        val relationshipId = "rel-alice-bob"
        val pairRootSecret = IdentityCrypto.generateX25519KeyPair().privateKey
        val secrets = RelationshipService.deriveSecrets(pairRootSecret)

        val (aToBQueue, bToAQueue, aSendAuth, bSendAuth) = RelationshipService.deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            alice.identity.signingPublicKey,
            bob.identity.signingPublicKey
        )

        // Register connections
        val aliceConn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = aToBQueue,
            recvQueueId = bToAQueue,
            sendAuth = aSendAuth,
            recvAuth = bSendAuth
        )
        alice.connectionManager.registerConnection(aliceConn)

        val bobConn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = bToAQueue,
            recvQueueId = aToBQueue,
            sendAuth = bSendAuth,
            recvAuth = aSendAuth
        )
        bob.connectionManager.registerConnection(bobConn)

        // Initialize Double Ratchet sessions
        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()

        alice.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )

        bob.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = false,
            remoteRatchetPublicKey = aliceRatchet.publicKey,
            localRatchetPrivateKey = bobRatchet.privateKey,
            localRatchetPublicKey = bobRatchet.publicKey
        )

        return alice to bob
    }

    @Test
    fun testGoldenPathAliceSendsBobReceivesBobAcksAliceMarkedDelivered() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()

        val conversationId = "conv-1"
        val relationshipId = "rel-alice-bob"
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Alice creates and encrypts message
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = alice.identity.identityId,
            recipientBinding = bob.identity.identityId,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = "Hello Bob!".toByteArray(Charsets.UTF_8)
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray(Charsets.UTF_8)

        val encryptedMsg = alice.sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)

        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderId = alice.identity.identityId,
            type = MessageType.TEXT.name,
            body = "Hello Bob!",
            direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name,
            createdAt = now
        )
        alice.messageDao.insertIfAbsent(messageEntity)

        val deliveryItem = DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = conn.connectionId,
            queueAddress = conn.sendQueueId,
            ciphertext = encryptedMsg.serialize(),
            queueAuthenticator = conn.sendAuth,
            status = DeliveryStatus.QUEUED
        )

        // 2. Alice enqueues in TorXAgent
        alice.agent.enqueue(deliveryItem)

        // 3. Wait for Bob to receive and persist message
        waitFor { bob.messageDao.exists(messageId) }
        val bobMsg = bob.messageDao.getById(messageId)
        assertNotNull("Bob must have received and persisted the message", bobMsg)
        assertEquals("Hello Bob!", bobMsg!!.body)

        // 4. Verify Bob recorded dedup state
        waitFor { bob.processedDao.envelopes.isNotEmpty() }
        assertTrue("Bob must have marked envelope in processed table", bob.processedDao.envelopes.isNotEmpty())

        // 5. Verify Alice received Bob's authenticated ACK and marked message DELIVERED!
        waitFor { alice.messageDao.getById(messageId)?.status == DeliveryStatus.DELIVERED.name }
        val aliceMsg = alice.messageDao.getById(messageId)
        assertNotNull(aliceMsg)
        assertEquals("Alice message status must be DELIVERED (✓✓)", DeliveryStatus.DELIVERED.name, aliceMsg!!.status)
        assertNotNull("DeliveredAt timestamp must be recorded", aliceMsg.deliveredAt)

        alice.stop()
        bob.stop()
    }

    @Test
    fun testDuplicatePacketInjectionDeduplication() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()

        // Enable duplicate packet delivery on FakeTransport
        alice.fakeTransport.duplicateDelivery = true

        val conversationId = "conv-1"
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        for (i in 1..20) {
            val msgId = "dup-msg-$i"
            val env = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = msgId,
                conversationId = conversationId,
                senderIdentity = alice.identity.identityId,
                recipientBinding = bob.identity.identityId,
                messageType = MessageType.TEXT,
                payload = "Message $i".toByteArray()
            )
            val envBytes = ProtocolCodec.encodeSecureEnvelope(env)
            val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray()
            val enc = alice.sessionCrypto.encrypt(relationshipId, envBytes, aad)

            alice.messageDao.insertIfAbsent(MessageEntity(
                logicalMessageId = msgId,
                conversationId = conversationId,
                senderId = alice.identity.identityId,
                type = "TEXT",
                body = "Message $i",
                direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
                status = "QUEUED"
            ))

            alice.agent.enqueue(DeliveryItem(
                deliveryId = "dup-env-$i",
                logicalMessageId = msgId,
                conversationId = conversationId,
                connectionId = conn.connectionId,
                queueAddress = conn.sendQueueId,
                ciphertext = enc.serialize(),
                queueAuthenticator = conn.sendAuth
            ))
        }

        // Wait for all 20 messages to arrive at Bob
        waitFor(10000) { bob.messageDao.messages.size == 20 }

        // Bob should have EXACTLY 20 messages, despite receiving 40 packets on the wire!
        assertEquals("Bob should have exactly 20 messages with 0 duplicates", 20, bob.messageDao.messages.size)

        alice.stop()
        bob.stop()
    }

    private suspend fun sendMessage(
        sender: Node,
        recipient: Node,
        relationshipId: String,
        conversationId: String,
        msgId: String,
        text: String
    ) {
        val now = System.currentTimeMillis()
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = msgId,
            conversationId = conversationId,
            senderIdentity = sender.identity.identityId,
            recipientBinding = recipient.identity.identityId,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val conn = sender.connectionManager.getConnectionByRelationship(relationshipId)!!
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray(Charsets.UTF_8)
        val encryptedMsg = sender.sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)

        sender.messageDao.insertIfAbsent(MessageEntity(
            logicalMessageId = msgId,
            conversationId = conversationId,
            senderId = sender.identity.identityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name,
            createdAt = now
        ))

        val deliveryItem = DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = msgId,
            conversationId = conversationId,
            connectionId = conn.connectionId,
            queueAddress = conn.sendQueueId,
            ciphertext = encryptedMsg.serialize(),
            queueAuthenticator = conn.sendAuth,
            status = DeliveryStatus.QUEUED
        )
        sender.agent.enqueue(deliveryItem)
    }

    @Test
    fun testBidirectionalSimultaneousMessaging() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"

        // Both nodes start sending concurrently
        val jobA = launch(Dispatchers.Default) {
            for (i in 1..10) {
                sendMessage(alice, bob, relationshipId, "conv-ab", "a-to-b-$i", "Alice message $i")
            }
        }
        val jobB = launch(Dispatchers.Default) {
            for (i in 1..10) {
                sendMessage(bob, alice, relationshipId, "conv-ba", "b-to-a-$i", "Bob message $i")
            }
        }
        jobA.join()
        jobB.join()

        // Wait for all 10 messages from Alice to arrive at Bob
        waitFor(10000) {
            bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == 10
        }
        // Wait for all 10 messages from Bob to arrive at Alice
        waitFor(10000) {
            alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == 10
        }

        // Wait for authenticated ACKs to confirm delivery on both sides (DELIVERED status = ✓✓)
        waitFor(10000) {
            alice.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name } &&
            bob.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name }
        }

        assertEquals(10, bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })
        assertEquals(10, alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })

        alice.stop()
        bob.stop()
    }

    @Test
    fun testAckLossAndRetryReliability() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"

        // Inject 30% packet loss on Bob's outgoing transport (dropping 30% of ACKs)
        bob.fakeTransport.packetLossRate = 0.3

        for (i in 1..10) {
            sendMessage(alice, bob, relationshipId, "conv-ab", "loss-msg-$i", "Reliable test $i")
        }

        // Even with 30% ACK drops, Alice's retry engine and Bob's deduplication table ensure:
        // 1. Bob persists each logical message exactly once (no duplicates)
        // 2. Alice eventually receives ACKs for all 10 messages and marks them DELIVERED
        waitFor(15000) {
            alice.messageDao.messages.values.all { it.status == DeliveryStatus.DELIVERED.name }
        }

        assertEquals(10, bob.messageDao.messages.size)
        assertTrue(alice.messageDao.messages.values.all { it.status == DeliveryStatus.DELIVERED.name })

        alice.stop()
        bob.stop()
    }

    @Test
    fun testMalformedAndTamperedPacketsRejected() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        // 1. Send truncated/malformed frame (< 8 bytes)
        val shortFrame = byteArrayOf(0x01, 0x02, 0x03)
        bob.incomingHub.onRawFrameReceived(shortFrame, TransportType.FAKE)

        // 2. Send unknown queue address
        val unknownQueueEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-1",
            queueAddress = "non-existent-queue",
            opaqueCiphertext = byteArrayOf(0x01, 0x02),
            queueAuthenticator = ByteArray(32)
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(unknownQueueEnv),
            TransportType.FAKE
        )

        // 3. Send invalid capability authenticator (bad HMAC)
        val badAuthEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-2",
            queueAddress = conn.sendQueueId, // destination queue is valid
            opaqueCiphertext = byteArrayOf(0x01, 0x02),
            queueAuthenticator = ByteArray(32) { 0xFF.toByte() } // wrong HMAC
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(badAuthEnv),
            TransportType.FAKE
        )

        // 4. Send corrupted ciphertext (valid queue and auth, but corrupted encrypted payload)
        val badCiphertextEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-3",
            queueAddress = conn.sendQueueId,
            opaqueCiphertext = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            queueAuthenticator = conn.sendAuth
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(badCiphertextEnv),
            TransportType.FAKE
        )

        // Verify: None of the above invalid/tampered packets were accepted into Bob's message database!
        delay(100)
        assertEquals("Bob must not persist any messages from malformed or tampered packets", 0, bob.messageDao.messages.size)

        alice.stop()
        bob.stop()
    }
}
