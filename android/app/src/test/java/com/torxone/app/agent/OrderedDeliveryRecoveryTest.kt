package com.torxone.app.agent

import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.transport.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedDeliveryRecoveryTest {
    private fun item(peer: String, sequence: Long) = DeliveryItem(deliveryId = "$peer-$sequence", logicalMessageId = "$peer-$sequence",
        conversationId = peer, connectionId = peer, queueAddress = "$peer-q", relationshipId = peer,
        ciphertext = byteArrayOf(sequence.toByte()), queueAuthenticator = ByteArray(32),
        applicationSequence = sequence, createdAt = 0, updatedAt = 0)

    @Test fun failedSequencedHeadBlocksLaterMessagesAndSurvivesRecovery() =
        assertTerminalHeadRemainsBlocking(DeliveryStatus.FAILED)

    @Test fun expiredSequencedHeadBlocksLaterMessagesAndSurvivesRecovery() =
        assertTerminalHeadRemainsBlocking(DeliveryStatus.EXPIRED)

    private fun assertTerminalHeadRemainsBlocking(terminalStatus: DeliveryStatus) = runTest {
        val backing = EndToEndPipelineTest.InMemoryOutboxStore()
        // Supply the complete persisted lane. The Android Room fixture separately
        // verifies terminal sequenced rows are included in pending/exact queries.
        val store = object : OutboxStore by backing {
            override suspend fun getPendingItems() = backing.items.values.toList()
            override suspend fun getByDeliveryId(deliveryId: String) = backing.items[deliveryId]
        }
        val terminalHead = item("bob", 1).copy(status = terminalStatus, attemptCount = 13, nextAttemptAt = 9_000)
        listOf(terminalHead, item("bob", 2), item("charlie", 1)).forEach { store.insert(it) }
        val sent = mutableListOf<String>()
        val updates = mutableListOf<DeliveryUpdate>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sent += ProtocolCodec.decodeTransportEnvelope(payload).envelopeId
                return TransportResult.Accepted(type)
            }
        }) }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        try {
            agent.start(); runCurrent(); advanceTimeBy(30); runCurrent()
            assertEquals(listOf("charlie-1"), sent)
            assertEquals(terminalStatus, backing.items["bob-1"]!!.status)
            assertEquals(DeliveryStatus.QUEUED, backing.items["bob-2"]!!.status)

            agent.triggerImmediateRetry(); runCurrent()
            agent.triggerImmediateRetry(relationshipId = "bob"); runCurrent()
            agent.onAuthenticatedPeerActivity("bob"); runCurrent(); advanceTimeBy(30); runCurrent()
            assertEquals(listOf("charlie-1"), sent)
            val retained = backing.items["bob-1"]!!
            assertEquals(terminalStatus, retained.status)
            assertEquals(13, retained.attemptCount)
            assertEquals(9_000L, retained.nextAttemptAt)
            assertArrayEquals(terminalHead.ciphertext, retained.ciphertext)
            assertFalse(updates.any { it.logicalMessageId == "bob-1" })

            // An exact late authenticated receipt remains authoritative even for
            // a terminal row; its removal releases only this relationship's N+1.
            agent.markDeliveryAcknowledged("bob-1", "bob-1"); runCurrent(); advanceTimeBy(6); runCurrent()
            assertNull(backing.items["bob-1"])
            assertEquals(listOf("charlie-1", "bob-2"), sent)
            assertEquals(DeliveryStatus.TRANSPORT_ACCEPTED, backing.items["bob-2"]!!.status)
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun fallbackMissingAckDoesNotOpenTorButTorMissingAckDoes() = runTest {
        val breaker = TorCircuitBreaker(clock = { testScheduler.currentTime }, failureThreshold = 1)
        var torAvailable = false
        var torSends = 0
        val router = TransportRouter(breaker = breaker).apply {
            registerTransport(object : Transport {
                override val type = TransportType.TOR
                override fun availability() = flowOf(if (torAvailable) TransportAvailability.Available else TransportAvailability.Unavailable("offline"))
                override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                    torSends++; return TransportResult.Accepted(type)
                }
            })
            registerTransport(object : Transport {
                override val type = TransportType.NEARBY
                override fun availability() = flowOf(TransportAvailability.Available)
                override suspend fun send(destination: TransportDestination, payload: ByteArray) = TransportResult.Accepted(type)
            })
        }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            receiverAckTimeoutMs = 20, outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        try {
            agent.enqueue(item("bob", 1)); agent.start(); runCurrent()
            assertEquals(0, torSends)
            torAvailable = true
            advanceTimeBy(21); runCurrent()
            assertEquals("Nearby missing ACK cannot suppress the first healthy Tor attempt", 1, torSends)
            advanceTimeBy(41); runCurrent()
            assertEquals("Tor missing ACK must open this peer's breaker", 1, torSends)
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun restartedAcceptanceHasUnknownTransportAndDoesNotPoisonTor() = runTest {
        val breaker = TorCircuitBreaker(clock = { testScheduler.currentTime }, failureThreshold = 1)
        var sends = 0
        val router = TransportRouter(breaker = breaker).apply { registerTransport(object : Transport {
            override val type = TransportType.TOR
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sends++; return TransportResult.Accepted(type)
            }
        }) }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        store.insert(item("bob", 1).copy(status = DeliveryStatus.TRANSPORT_ACCEPTED, attemptCount = 1))
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        try {
            agent.start(); runCurrent()
            assertEquals(1, sends)
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun ackBeforeTransportReturnsCannotPublishAcceptanceAfterDelivery() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val updates = mutableListOf<DeliveryUpdate>()
        lateinit var agent: TorXAgent
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                val id = ProtocolCodec.decodeTransportEnvelope(payload).envelopeId
                agent.markDeliveryAcknowledged(id, id)
                return TransportResult.Accepted(type)
            }
        }) }
        agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        try {
            agent.enqueue(item("bob", 1)); agent.start(); runCurrent()
            assertTrue(store.items.isEmpty())
            assertEquals(DeliveryStatus.DELIVERED, updates.last().status)
            assertFalse(updates.any { it.status == DeliveryStatus.TRANSPORT_ACCEPTED })
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun committedAckDuringFailedAttemptCannotPublishRetryAfterDelivery() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val networkEntered = CompletableDeferred<Unit>()
        val finishNetwork = CompletableDeferred<Unit>()
        val updates = mutableListOf<DeliveryUpdate>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                networkEntered.complete(Unit); finishNetwork.await()
                return TransportResult.Failed(type, "Disconnected after receiver committed")
            }
        }) }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        try {
            agent.enqueue(item("bob", 1)); agent.start(); runCurrent()
            assertTrue(networkEntered.isCompleted)
            agent.markDeliveryAcknowledged("bob-1", "bob-1")
            finishNetwork.complete(Unit); runCurrent()
            assertEquals(DeliveryStatus.DELIVERED, updates.last().status)
            assertFalse(updates.any { it.status == DeliveryStatus.RETRY_WAIT })
            assertTrue(store.items.isEmpty())
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun ackDuringAcceptancePersistencePublishesMonotonicEvents() = runTest {
        val backing = EndToEndPipelineTest.InMemoryOutboxStore()
        val acceptancePersisted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val store = object : OutboxStore by backing {
            override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
                backing.updateStatus(deliveryId, status)
                if (status == DeliveryStatus.TRANSPORT_ACCEPTED) {
                    acceptancePersisted.complete(Unit); releasePersistence.await()
                }
            }
        }
        val updates = mutableListOf<DeliveryUpdate>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray) = TransportResult.Accepted(type)
        }) }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        try {
            agent.enqueue(item("bob", 1)); agent.start(); runCurrent()
            assertTrue(acceptancePersisted.isCompleted)
            val acknowledgment = launch { agent.markDeliveryAcknowledged("bob-1", "bob-1") }
            runCurrent()
            assertFalse(acknowledgment.isCompleted)
            releasePersistence.complete(Unit); runCurrent()
            assertTrue(acknowledgment.isCompleted)
            assertEquals(DeliveryStatus.DELIVERED, updates.last().status)
            assertEquals(listOf(DeliveryStatus.TRANSPORT_ACCEPTED, DeliveryStatus.DELIVERED), updates.takeLast(2).map { it.status })
            assertTrue(backing.items.isEmpty())
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun staleRecoverySnapshotCannotRequeueAnAcknowledgedWaitingHead() = runTest {
        val backing = EndToEndPipelineTest.InMemoryOutboxStore()
        val snapshotCaptured = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val store = object : OutboxStore by backing {
            override suspend fun getPendingItems(): List<DeliveryItem> {
                val snapshot = backing.getPendingItems()
                snapshotCaptured.complete(Unit); releaseSnapshot.await()
                return snapshot
            }
            override suspend fun getByDeliveryId(deliveryId: String) = backing.items[deliveryId]
        }
        val updates = mutableListOf<DeliveryUpdate>()
        val agent = TorXAgent(TransportRouter(), store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { agent.deliveryUpdates.collect { updates += it } }
        backing.insert(item("bob", 1).copy(status = DeliveryStatus.WAITING_FOR_PEER, attemptCount = 8))
        agent.triggerImmediateRetry(relationshipId = "bob"); runCurrent()
        assertTrue(snapshotCaptured.isCompleted)
        agent.markDeliveryAcknowledged("bob-1", "bob-1")
        releaseSnapshot.complete(Unit); runCurrent()
        assertTrue(backing.items.isEmpty())
        assertEquals(listOf(DeliveryStatus.DELIVERED), updates.map { it.status })
    }

    @Test fun acceptanceCannotAdvanceHeadAndQuietBudgetResumesExactCiphertext() = runTest {
        val sent = mutableListOf<String>()
        val payloads = mutableListOf<ByteArray>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sent += ProtocolCodec.decodeTransportEnvelope(payload).envelopeId; payloads += payload.copyOf()
                return TransportResult.Accepted(type)
            }
        }) }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            baseRetryDelayMs = 10, receiverAckTimeoutMs = 20, outboxPollIntervalMs = 5, maxLiveAttempts = 2,
            clock = { testScheduler.currentTime })
        try {
            agent.enqueue(item("bob", 1)); agent.enqueue(item("bob", 2)); agent.enqueue(item("charlie", 1))
            agent.start(); runCurrent()
            assertFalse(sent.contains("bob-2"))
            assertTrue(sent.contains("charlie-1"))
            assertEquals(DeliveryStatus.TRANSPORT_ACCEPTED, store.items["bob-1"]!!.status)
            advanceTimeBy(100); runCurrent()
            assertEquals(DeliveryStatus.WAITING_FOR_PEER, store.items["bob-1"]!!.status)
            assertEquals(2, store.items["bob-1"]!!.attemptCount)
            assertFalse(sent.contains("bob-2"))
            val attempts = sent.size
            advanceTimeBy(1000); runCurrent(); assertEquals(attempts, sent.size)
            val original = payloads.first()
            agent.triggerImmediateRetry(relationshipId = "bob"); runCurrent(); advanceTimeBy(6); runCurrent()
            assertArrayEquals(original, payloads.last())
            assertEquals(1, store.items["bob-1"]!!.attemptCount)
            agent.markDeliveryAcknowledged("bob-1", "bob-1"); runCurrent(); advanceTimeBy(6); runCurrent()
            assertTrue(sent.contains("bob-2"))
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun restartKeepsWaitingHeadAndLateAckAllowsFollowingSequence() = runTest {
        var sends = 0
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sends++; return TransportResult.Accepted(type)
            }
        }) }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        store.insert(item("bob", 1).copy(status = DeliveryStatus.WAITING_FOR_PEER, attemptCount = 8))
        store.insert(item("bob", 2))
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime }, outboxPollIntervalMs = 5)
        try {
            agent.start(); runCurrent(); advanceTimeBy(100); runCurrent()
            assertEquals(0, sends); assertEquals(2, store.items.size)
            agent.markDeliveryAcknowledged("bob-1", "bob-1"); runCurrent(); advanceTimeBy(6); runCurrent()
            assertEquals(1, sends)
            assertEquals(DeliveryStatus.TRANSPORT_ACCEPTED, store.items["bob-2"]!!.status)
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun overLimitLegacyHeadStopsWithoutDiscardingOrAdvancing() = runTest {
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        store.insert(item("bob", 1).copy(attemptCount = 319)); store.insert(item("bob", 2))
        val agent = TorXAgent(TransportRouter(), store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime }, outboxPollIntervalMs = 5)
        try {
            agent.start(); runCurrent()
            assertEquals(DeliveryStatus.WAITING_FOR_PEER, store.items["bob-1"]!!.status)
            assertEquals(DeliveryStatus.QUEUED, store.items["bob-2"]!!.status)
            assertEquals(2, store.items.size)
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun pausedControlCannotStarveEligibleControlOrAdvancePausedSequence() = runTest {
        val sent = mutableListOf<String>()
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val pausedControl = item("bob", 3).copy(deliveryId = "paused-control", applicationSequence = null,
            priority = DeliveryPriority.HIGH)
        val eligibleControl = item("bob", 4).copy(deliveryId = "eligible-control", applicationSequence = null,
            priority = DeliveryPriority.HIGH)
        listOf(item("bob", 1), item("bob", 2), pausedControl, eligibleControl, item("charlie", 1)).forEach { store.insert(it) }
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sent += ProtocolCodec.decodeTransportEnvelope(payload).envelopeId
                return TransportResult.Accepted(type)
            }
        }) }
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            isDeliveryPaused = { it.deliveryId == "paused-control" || it.deliveryId == "bob-1" },
            outboxPollIntervalMs = 5, clock = { testScheduler.currentTime })
        try {
            agent.start(); runCurrent(); advanceTimeBy(100); runCurrent()
            assertEquals(setOf("eligible-control", "charlie-1"), sent.toSet())
            assertEquals(2, sent.size)
            assertEquals(DeliveryStatus.QUEUED, store.items["bob-1"]!!.status)
            assertEquals(DeliveryStatus.QUEUED, store.items["bob-2"]!!.status)
            assertEquals(DeliveryStatus.QUEUED, store.items["paused-control"]!!.status)
        } finally { agent.stop(); runCurrent() }
    }
}
