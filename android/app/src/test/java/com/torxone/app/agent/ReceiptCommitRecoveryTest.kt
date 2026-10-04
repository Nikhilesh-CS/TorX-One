package com.torxone.app.agent

import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.NoOpKeyProtector
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.ActiveConversationTracker
import com.torxone.app.incoming.ChatReceiver
import com.torxone.app.incoming.DeliveryReceiptHandler
import com.torxone.app.incoming.IncomingDispatcher
import com.torxone.app.protocol.*
import com.torxone.app.transport.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptCommitRecoveryTest {
    @Test fun delayedCommittedAckCannotRegressAReadCommittedAndPublishedAfterItsSnapshot() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val messages = EndToEndPipelineTest.InMemoryMessageDao()
        val agent = TorXAgent(TransportRouter(), store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            committedMessageStatus = { id -> messages.getById(id)?.let {
                if (it.readAt != null) DeliveryStatus.READ else DeliveryStatus.valueOf(it.status)
            } })
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        val connection = Connection("conn", "rel", sendQueueId = "out", recvQueueId = "in")
        store.insert(DeliveryItem("head", "message", "chat", "conn", "out", byteArrayOf(1), ByteArray(32), relationshipId = "rel"))
        messages.insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "hello",
            MessageDirection.OUTGOING, DeliveryStatus.QUEUED.name))
        val handler = DeliveryReceiptHandler(messages, EndToEndPipelineTest.InMemoryOutboxDao(store), agent,
            authenticatedContactProvider = { "chat" to "remote" })

        // The ACK transaction commits first, but its post-commit callback is delayed.
        val oldAck = handler.handleDeliveryAck(SecureEnvelope(1, "ack", "chat", "remote", "local", MessageType.DELIVERY_ACK,
            payload = DeliveryAck("message", "head", receivedAt = 5).toByteArray()), connection)!!
        assertEquals(DeliveryStatus.DELIVERED, oldAck.visibleStatus)
        assertTrue(updates.isEmpty())
        val readId = handler.handleReadReceipt(SecureEnvelope(1, "read", "chat", "remote", "local", MessageType.READ_RECEIPT,
            payload = ReadReceipt("chat", "message", readAt = 7).toByteArray()), connection)!!
        assertTrue(updates.isEmpty())
        handler.afterReadCommit(readId)
        handler.afterCommit(oldAck)
        agent.wake("message"); runCurrent()

        assertEquals(listOf(DeliveryStatus.READ, DeliveryStatus.READ), updates.map { it.status })
        assertEquals(DeliveryStatus.READ.name, messages.messages["message"]!!.status)
        assertEquals(7L, messages.messages["message"]!!.readAt)
        assertEquals(5L, messages.messages["message"]!!.deliveredAt)
        assertTrue(store.items.isEmpty())
        assertEquals(0, agent.activeMessagePublicationCount())
    }

    @Test fun readDuringNetworkAttemptSuppressesItsLaterAcceptanceEventWithoutDiscardingCiphertext() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val messages = EndToEndPipelineTest.InMemoryMessageDao()
        val networkEntered = CompletableDeferred<Unit>()
        val releaseNetwork = CompletableDeferred<Unit>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                networkEntered.complete(Unit); releaseNetwork.await()
                return TransportResult.Accepted(type)
            }
        }) }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime },
            committedMessageStatus = { id -> messages.getById(id)?.let { DeliveryStatus.valueOf(it.status) } })
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        messages.insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "hello",
            MessageDirection.OUTGOING, DeliveryStatus.QUEUED.name))
        val head = DeliveryItem("head", "message", "chat", "conn", "out", byteArrayOf(1), ByteArray(32), relationshipId = "rel")
        val handler = DeliveryReceiptHandler(messages, EndToEndPipelineTest.InMemoryOutboxDao(store), agent,
            authenticatedContactProvider = { "chat" to "remote" })
        try {
            agent.enqueue(head); agent.start(); runCurrent()
            assertTrue(networkEntered.isCompleted)
            val readId = handler.handleReadReceipt(SecureEnvelope(1, "read", "chat", "remote", "local", MessageType.READ_RECEIPT,
                payload = ReadReceipt("chat", "message", readAt = 7).toByteArray()),
                Connection("conn", "rel", sendQueueId = "out", recvQueueId = "in"))!!
            handler.afterReadCommit(readId)
            releaseNetwork.complete(Unit); runCurrent()
            assertEquals(DeliveryStatus.READ, updates.last().status)
            assertFalse(updates.any { it.status == DeliveryStatus.TRANSPORT_ACCEPTED })
            assertEquals(DeliveryStatus.TRANSPORT_ACCEPTED, store.items["head"]!!.status)
            assertArrayEquals(head.ciphertext, store.items["head"]!!.ciphertext)
            assertEquals(0, agent.activeMessagePublicationCount())
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun suspendedAckAndReadPublishInOrderAndCancelledWaiterCannotSplitOrLeakTheirLock() = runTest {
        val statusRead = CompletableDeferred<Unit>()
        val releaseStatusRead = CompletableDeferred<Unit>()
        var committedStatus = DeliveryStatus.DELIVERED
        var firstQuery = true
        val agent = TorXAgent(TransportRouter(), EndToEndPipelineTest.InMemoryOutboxStore(),
            coroutineDispatcher = StandardTestDispatcher(testScheduler), committedMessageStatus = {
                val snapshot = committedStatus
                if (firstQuery) {
                    firstQuery = false; statusRead.complete(Unit); releaseStatusRead.await()
                }
                snapshot
            })
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        val delayedAck = launch { agent.onAcknowledgmentCommitted("head", "message", null) }
        runCurrent(); assertTrue(statusRead.isCompleted)
        committedStatus = DeliveryStatus.READ
        val read = launch { agent.markRead("message") }
        val cancelledWaiter = launch { agent.markRead("message") }
        runCurrent()
        assertFalse(read.isCompleted)
        assertEquals(1, agent.activeMessagePublicationCount())
        cancelledWaiter.cancel(); runCurrent()
        assertEquals(1, agent.activeMessagePublicationCount())
        releaseStatusRead.complete(Unit); runCurrent()
        assertTrue(delayedAck.isCompleted); assertTrue(read.isCompleted)
        assertEquals(listOf(DeliveryStatus.DELIVERED, DeliveryStatus.READ), updates.map { it.status })
        assertEquals(0, agent.activeMessagePublicationCount())
        // A new publisher after the old lock was released consults durable READ,
        // rather than needing an unbounded map of already-completed messages.
        agent.onAcknowledgmentCommitted("head", "message", null)
        assertEquals(DeliveryStatus.READ, updates.last().status)
        assertEquals(0, agent.activeMessagePublicationCount())
    }

    @Test fun groupRecipientReadDoesNotPublishAggregateReadBeforeCommittedMessageState() = runTest {
        var aggregateStatus = DeliveryStatus.DELIVERED
        val agent = TorXAgent(TransportRouter(), EndToEndPipelineTest.InMemoryOutboxStore(),
            committedMessageStatus = { aggregateStatus })
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        agent.markRead("group-message")
        assertTrue(updates.isEmpty())
        aggregateStatus = DeliveryStatus.READ
        agent.markRead("group-message")
        assertEquals(listOf(DeliveryStatus.READ), updates.map { it.status })
        assertEquals(0, agent.activeMessagePublicationCount())
    }

    @Test fun lateDeliveryAckKeepsReadStateInRoomAndPublishedEvent() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val messages = EndToEndPipelineTest.InMemoryMessageDao()
        val agent = TorXAgent(TransportRouter(), store, coroutineDispatcher = StandardTestDispatcher(testScheduler))
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        val connection = Connection("conn", "rel", sendQueueId = "out", recvQueueId = "in")
        store.insert(DeliveryItem("head", "message", "chat", "conn", "out", byteArrayOf(1), ByteArray(32), relationshipId = "rel"))
        messages.insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "hello",
            MessageDirection.OUTGOING, DeliveryStatus.READ.name, readAt = 7))
        val handler = DeliveryReceiptHandler(messages, EndToEndPipelineTest.InMemoryOutboxDao(store), agent)
        val receipt = handler.handleDeliveryAck(SecureEnvelope(1, "ack", "chat", "remote", "local", MessageType.DELIVERY_ACK,
            payload = DeliveryAck("message", "head", receivedAt = 5).toByteArray()), connection)!!
        assertTrue(updates.isEmpty())
        handler.afterCommit(receipt)
        assertEquals(DeliveryStatus.READ.name, messages.messages["message"]!!.status)
        assertEquals(7L, messages.messages["message"]!!.readAt)
        assertEquals(5L, messages.messages["message"]!!.deliveredAt)
        assertEquals(DeliveryStatus.READ, updates.single().status)
    }

    @Test fun readDatabaseFailureAndCancellationPropagateInsteadOfConsumingRatchet() = runTest {
        val messages = EndToEndPipelineTest.InMemoryMessageDao()
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(TransportRouter(), store, coroutineDispatcher = StandardTestDispatcher(testScheduler))
        messages.insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "hello",
            MessageDirection.OUTGOING, DeliveryStatus.DELIVERED.name))
        val connection = Connection("conn", "rel", sendQueueId = "out", recvQueueId = "in")
        val receipt = SecureEnvelope(1, "read", "chat", "remote", "local", MessageType.READ_RECEIPT,
            payload = ReadReceipt("chat", "message").toByteArray())
        for (failure in listOf(IllegalStateException("DB commit unavailable"), CancellationException("Stop"))) {
            val failingDao = object : MessageDao by messages {
                override suspend fun markOutgoingReadUpTo(conversationId: String, upToCreatedAt: Long, status: String, readAt: Long) {
                    throw failure
                }
            }
            val handler = DeliveryReceiptHandler(failingDao, EndToEndPipelineTest.InMemoryOutboxDao(store), agent,
                authenticatedContactProvider = { "chat" to "remote" })
            try {
                handler.handleReadReceipt(receipt, connection)
                fail("Receipt mutation failure must abort the outer transaction")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
        assertEquals(DeliveryStatus.DELIVERED.name, messages.messages["message"]!!.status)
    }

    @Test fun rolledBackReceiptCannotPublishDeliveryOrResetHealthAndExactRetryCommits() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val messages = EndToEndPipelineTest.InMemoryMessageDao()
        val processed = EndToEndPipelineTest.InMemoryProcessedStore()
        val sessions = EndToEndPipelineTest.InMemorySessionStore()
        val senderSessions = EndToEndPipelineTest.InMemorySessionStore()
        val receiverCrypto = DoubleRatchetSessionCrypto(sessions)
        val senderCrypto = DoubleRatchetSessionCrypto(senderSessions)
        val breaker = TorCircuitBreaker(clock = { testScheduler.currentTime }, failureThreshold = 1, openMs = 100_000)
        breaker.failed(breaker.acquire("rel")!!)
        var sends = 0
        val router = TransportRouter(breaker = breaker).apply {
            registerTransport(object : Transport {
                override val type = TransportType.TOR
                override fun availability() = flowOf(TransportAvailability.Available)
                override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                    sends++
                    return TransportResult.Accepted(type)
                }
            })
        }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime }, outboxPollIntervalMs = 5)
        val updates = mutableListOf<DeliveryUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        val connection = Connection("conn", "rel", sendQueueId = "out", recvQueueId = "in",
            sendAuth = ByteArray(32) { 1 }, recvAuth = ByteArray(32) { 2 })
        val manager = ConnectionManager(keyProtector = NoOpKeyProtector()).apply { registerConnection(connection) }
        val head = DeliveryItem("head", "message", "chat", "conn", "out", byteArrayOf(1), ByteArray(32),
            status = DeliveryStatus.WAITING_FOR_PEER, attemptCount = 8, applicationSequence = 1, relationshipId = "rel")
        store.insert(head)
        store.insert(head.copy(deliveryId = "next", logicalMessageId = "next-message", status = DeliveryStatus.QUEUED,
            attemptCount = 0, applicationSequence = 2))
        messages.insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "hello",
            MessageDirection.OUTGOING, DeliveryStatus.WAITING_FOR_PEER.name))
        val handler = DeliveryReceiptHandler(messages, EndToEndPipelineTest.InMemoryOutboxDao(store), agent)
        val receiverKey = IdentityCrypto.generateX25519KeyPair()
        val senderKey = IdentityCrypto.generateX25519KeyPair()
        val initializationSecret = ByteArray(32) { 3 }
        receiverCrypto.initializeSession("rel", initializationSecret, false, senderKey.publicKey,
            receiverKey.privateKey, receiverKey.publicKey)
        senderCrypto.initializeSession("rel", initializationSecret, true, receiverKey.publicKey,
            senderKey.privateKey, senderKey.publicKey)
        var rejectCommit = true
        var transactionAttempts = 0
        val dispatcher = IncomingDispatcher(manager, receiverCrypto, processed,
            ChatReceiver(messages, EndToEndPipelineTest.InMemoryConversationDao(), ActiveConversationTracker()),
            handler, agent, localIdentityIdProvider = { "local" }, authenticatedRemoteIdentityProvider = { "remote" },
            keyProtector = NoOpKeyProtector(), sessionStore = sessions,
            transactionRunner = { block ->
                transactionAttempts++
                val oldMessages = messages.messages.toMap()
                val oldOutbox = store.items.toMap()
                val oldProcessed = processed.records.toMap()
                val oldSessions = sessions.sessions.mapValues { it.value.copyState() }
                try {
                    block()
                    if (rejectCommit) throw IllegalStateException("Forced commit failure")
                } catch (error: Exception) {
                    messages.messages.clear(); messages.messages.putAll(oldMessages)
                    store.items.clear(); store.items.putAll(oldOutbox)
                    processed.records.clear(); processed.records.putAll(oldProcessed)
                    sessions.sessions.clear(); sessions.sessions.putAll(oldSessions)
                    throw error
                }
            })
        val ack = SecureEnvelope(1, "ack", "chat", "remote", "local", MessageType.DELIVERY_ACK,
            payload = DeliveryAck("message", "head").toByteArray())
        val ciphertext = senderCrypto.encrypt("rel", ProtocolCodec.encodeSecureEnvelope(ack),
            "torx-aad-v1:${connection.generation}:in".toByteArray()).serialize()
        val raw = ProtocolCodec.encodeTransportEnvelope(OpaqueTransportEnvelope(version = 1,
            queueAddress = "in", envelopeId = "ack-delivery", opaqueCiphertext = ciphertext,
            queueAuthenticator = IdentityCrypto.computeQueueAuthenticator(connection.recvAuth, "ack-delivery", "in", ciphertext)))
        try {
            agent.start(); runCurrent()
            assertFalse(dispatcher.dispatch(raw, TransportType.TOR))
            assertEquals("Fault must happen inside the receipt transaction, after authentication/decryption", 1, transactionAttempts)
            runCurrent(); advanceTimeBy(20); runCurrent()
            assertTrue(updates.isEmpty())
            assertNull(breaker.acquire("rel"))
            assertEquals(0, sends)
            assertNotNull(store.items["head"])
            assertEquals(DeliveryStatus.WAITING_FOR_PEER.name, messages.messages["message"]!!.status)
            assertFalse(processed.isProcessed("ack-delivery"))
            rejectCommit = false
            assertTrue(dispatcher.dispatch(raw, TransportType.TOR))
            assertEquals(2, transactionAttempts)
            runCurrent()
            assertNull(store.items["head"])
            assertTrue(processed.isProcessed("ack-delivery"))
            assertEquals(1, updates.count { it.logicalMessageId == "message" && it.status == DeliveryStatus.DELIVERED })
            assertEquals(1, sends)
        } finally {
            agent.stop(); runCurrent()
            receiverCrypto.closeSession("rel"); senderCrypto.closeSession("rel")
        }
    }
}
