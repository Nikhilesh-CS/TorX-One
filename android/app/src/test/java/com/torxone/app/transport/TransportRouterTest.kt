package com.torxone.app.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 5 Unit Tests: Transport Layer Hardening
 *
 * Verifies:
 * 1. Active transport priority ordering: NEARBY_DIRECT (1) -> WIFI_DIRECT (2) -> TOR (3) -> OFFLINE_RELAY (4)
 * 2. TransportRouter priority-based failover chain
 * 3. Strict opaque byte pipe (payload passed unchanged)
 * 4. Global incoming listener registration
 */
@RunWith(RobolectricTestRunner::class)
class TransportRouterTest {

    class MockTransport(
        override val name: String,
        override val type: TransportType,
        private val available: Boolean = true,
        private val shouldSucceed: Boolean = true,
        private val errorMsg: String? = null
    ) : Transport {
        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(available)
        override val statusText: StateFlow<String> = MutableStateFlow(if (available) "Ready" else "Disabled")

        var lastDestination: String? = null
        var lastPayload: String? = null
        var sendAttemptCount: Int = 0
        var registeredListener: TransportIncomingListener? = null

        override suspend fun send(
            destination: String,
            payload: String,
            metadata: TransportMetadata?
        ): TransportResult {
            sendAttemptCount++
            lastDestination = destination
            lastPayload = payload
            return if (shouldSucceed) {
                TransportResult.success(type, latencyMs = 25L)
            } else {
                TransportResult.failure(type, errorMsg ?: "$name send failed")
            }
        }

        override fun setIncomingListener(listener: TransportIncomingListener?) {
            registeredListener = listener
        }

        override fun start() {}
        override fun stop() {}
        override fun getReachablePeers(): Set<String> = emptySet()
    }

    @Test
    fun testTransportPriorityOrdering_registeredInAnyOrder() {
        val router = TransportRouter()

        val relay = MockTransport("Relay", TransportType.OFFLINE_RELAY)
        val nearby = MockTransport("Nearby", TransportType.NEARBY_DIRECT)
        val tor = MockTransport("Tor", TransportType.TOR)
        val wifi = MockTransport("WiFiDirect", TransportType.WIFI_DIRECT)

        // Register in reverse order
        router.registerTransport(relay)
        router.registerTransport(tor)
        router.registerTransport(wifi)
        router.registerTransport(nearby)

        val active = router.activeTransports.value
        assertEquals(4, active.size)
        // Priorities: NEARBY_DIRECT(1) -> WIFI_DIRECT(2) -> TOR(3) -> OFFLINE_RELAY(4)
        assertEquals(TransportType.NEARBY_DIRECT, active[0].type)
        assertEquals(TransportType.WIFI_DIRECT, active[1].type)
        assertEquals(TransportType.TOR, active[2].type)
        assertEquals(TransportType.OFFLINE_RELAY, active[3].type)
    }

    @Test
    fun testPriorityFailover_nearbyFailsFallsBackToTor() = runBlocking {
        val router = TransportRouter()

        val nearbyFailing = MockTransport("Nearby", TransportType.NEARBY_DIRECT, available = true, shouldSucceed = false)
        val torSucceeding = MockTransport("Tor", TransportType.TOR, available = true, shouldSucceed = true)

        router.registerTransport(nearbyFailing)
        router.registerTransport(torSucceeding)

        val opaquePayload = "{\"version\":2,\"connectionId\":\"conn-abc\",\"ciphertext\":\"enc_blob\"}"
        val addresses = mapOf(
            TransportType.NEARBY_DIRECT to "endpoint-1",
            TransportType.TOR to "alice.onion"
        )

        val result = router.deliver(addresses, opaquePayload)

        assertTrue(result.success)
        assertEquals(TransportType.TOR, result.transportType)
        assertEquals(1, nearbyFailing.sendAttemptCount)
        assertEquals(1, torSucceeding.sendAttemptCount)
        assertEquals("alice.onion", torSucceeding.lastDestination)
        assertEquals(opaquePayload, torSucceeding.lastPayload) // Strict opaque byte pipe
    }

    @Test
    fun testPriorityFailover_fallsBackToOfflineRelayWhenAllDirectTransportsUnavailable() = runBlocking {
        val router = TransportRouter()

        val nearbyUnavailable = MockTransport("Nearby", TransportType.NEARBY_DIRECT, available = false)
        val torUnavailable = MockTransport("Tor", TransportType.TOR, available = false)
        val relayAvailable = MockTransport("Relay", TransportType.OFFLINE_RELAY, available = true, shouldSucceed = true)

        router.registerTransport(nearbyUnavailable)
        router.registerTransport(torUnavailable)
        router.registerTransport(relayAvailable)

        val opaquePayload = "{\"version\":2,\"queueId\":\"q-123\",\"ciphertext\":\"enc_data\"}"
        val addresses = mapOf(
            TransportType.NEARBY_DIRECT to "endpoint-1",
            TransportType.TOR to "onion-1",
            TransportType.OFFLINE_RELAY to "queue-recv-123"
        )

        val result = router.deliver(addresses, opaquePayload)

        assertTrue(result.success)
        assertEquals(TransportType.OFFLINE_RELAY, result.transportType)
        assertEquals(0, nearbyUnavailable.sendAttemptCount) // Skipped because unavailable
        assertEquals(0, torUnavailable.sendAttemptCount) // Skipped because unavailable
        assertEquals(1, relayAvailable.sendAttemptCount)
        assertEquals("queue-recv-123", relayAvailable.lastDestination)
        assertEquals(opaquePayload, relayAvailable.lastPayload)
    }

    @Test
    fun testGlobalIncomingListenerRegistration() {
        val router = TransportRouter()

        val nearby = MockTransport("Nearby", TransportType.NEARBY_DIRECT)
        val tor = MockTransport("Tor", TransportType.TOR)

        router.registerTransport(nearby)
        router.registerTransport(tor)

        var receivedCount = 0
        val listener = TransportIncomingListener { _, _, _ ->
            receivedCount++
        }

        router.setGlobalIncomingListener(listener)

        assertSame(listener, nearby.registeredListener)
        assertSame(listener, tor.registeredListener)

        nearby.registeredListener?.onPayloadReceived("addr1", "data1", TransportType.NEARBY_DIRECT)
        tor.registeredListener?.onPayloadReceived("addr2", "data2", TransportType.TOR)

        assertEquals(2, receivedCount)
    }
}
