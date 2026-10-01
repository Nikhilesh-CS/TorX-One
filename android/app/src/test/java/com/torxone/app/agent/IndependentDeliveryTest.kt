package com.torxone.app.agent

import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndependentDeliveryTest {
    private fun item(queue: String, id: String = queue, sequence: Long? = null) = DeliveryItem(
        deliveryId = id, logicalMessageId = id, conversationId = queue, connectionId = queue,
        queueAddress = queue, ciphertext = byteArrayOf(1), queueAuthenticator = ByteArray(32),
        expectsAck = sequence != null, applicationSequence = sequence, relationshipId = queue)

    @Test fun stalledPeerCannotBlockNewMessagesForAnotherPeer() = runTest {
        val entered = mutableListOf<String>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                entered += destination.address
                if (destination.address == "slow") delay(60_000)
                return TransportResult.Accepted(type)
            }
        }) }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler))
        try {
            agent.enqueue(item("slow")); agent.start(); runCurrent()
            agent.enqueue(item("new-peer")); runCurrent()
            assertTrue(entered.contains("new-peer"))
            assertTrue(store.items.values.any { it.queueAddress == "slow" })
        } finally { agent.stop(); runCurrent() }
    }

    @Test fun pausedSequenceRemainsSavedAndCannotBeOvertakenThenResumes() = runTest {
        var paused = true
        val sent = mutableListOf<String>()
        val router = TransportRouter().apply { registerTransport(object : Transport {
            override val type = TransportType.FAKE
            override fun availability() = flowOf(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                sent += destination.address
                return TransportResult.Accepted(type)
            }
        }) }
        val store = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(router, store, coroutineDispatcher = StandardTestDispatcher(testScheduler),
            isDeliveryPaused = { paused })
        try {
            agent.enqueue(item("peer", "first", 1)); agent.enqueue(item("peer", "second", 2))
            agent.start(); runCurrent()
            assertTrue(sent.isEmpty()); assertEquals(2, store.items.size)
            paused = false; agent.triggerImmediateRetry(); runCurrent()
            advanceTimeBy(1001); runCurrent()
            assertTrue(sent.isNotEmpty())
            assertEquals(2, store.items.size)
        } finally { agent.stop(); runCurrent() }
    }
}
