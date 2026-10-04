package com.torxone.app.transport

import com.torxone.app.transport.tor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedDeliveryRoutingTest {
    private class Stub(override val type: TransportType, val behavior: suspend (TransportDestination) -> TransportResult) : Transport, AddressableTransport {
        var sends = 0
        var bytes: ByteArray? = null
        override fun availability() = flowOf(TransportAvailability.Available)
        override fun canRoute(destination: TransportDestination) = true
        override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
            sends++; bytes = payload.copyOf(); return behavior(destination)
        }
    }

    @Test fun timedOutTorFallsBackWithSameBytesWithinBudget() = runTest {
        val tor = Stub(TransportType.TOR) { awaitCancellation() }
        val nearby = Stub(TransportType.NEARBY) { TransportResult.Accepted(TransportType.NEARBY) }
        val router = TransportRouter(torAttemptMs = 100, fallbackAttemptMs = 50, attemptMs = 200)
        router.registerTransport(tor); router.registerTransport(nearby)
        val payload = byteArrayOf(1, 2, 3)
        assertEquals(TransportResult.Accepted(TransportType.NEARBY), router.send(TransportDestination("q", relationshipId = "a"), payload))
        assertEquals(100, testScheduler.currentTime)
        assertArrayEquals(payload, nearby.bytes)
        assertArrayEquals(payload, tor.bytes)
    }

    @Test fun nativeStalledConnectIsClosedBeforeRouterFallsBack() = runBlocking {
        val closed = CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { closed.countDown() }
            override fun isClosed() = closed.count == 0L
        }
        val manager = TorPeerConnectionManager(socketFactory = { socket }, connectSocket = { _, _ ->
            closed.await(); throw IOException("Connect aborted")
        })
        val tor = object : Transport, AddressableTransport, RecoverableTransport {
            override val type = TransportType.TOR
            override fun availability() = flowOf(TransportAvailability.Available)
            override fun canRoute(destination: TransportDestination) = true
            override fun invalidate(destination: TransportDestination) { manager.invalidate("a") }
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                manager.send("a", TorRoute("a".repeat(56) + ".onion"), 9050, "b".repeat(56) + ".onion", payload)
                return TransportResult.Accepted(type)
            }
        }
        val fallback = Stub(TransportType.NEARBY) { TransportResult.Accepted(TransportType.NEARBY) }
        val router = TransportRouter(torAttemptMs = 50)
        router.registerTransport(tor); router.registerTransport(fallback)
        try {
            withTimeout(2000) { assertEquals(TransportResult.Accepted(TransportType.NEARBY), router.send(TransportDestination("q", relationshipId = "a"), byteArrayOf(1))) }
            assertTrue(socket.isClosed); assertEquals(1, fallback.sends)
        } finally { manager.closeAll() }
    }

    @Test fun staleOnionOpensOnlyItsPeerAndVerifiedRefreshAllowsProbe() = runTest {
        val breaker = TorCircuitBreaker(clock = { testScheduler.currentTime }, openMs = 100)
        var stale = true
        val tor = Stub(TransportType.TOR) {
            if (it.relationshipId == "bob" && stale) TransportResult.Failed(TransportType.TOR, "unreachable")
            else TransportResult.Accepted(TransportType.TOR)
        }
        val nearby = Stub(TransportType.NEARBY) { TransportResult.Accepted(TransportType.NEARBY) }
        val router = TransportRouter(breaker = breaker)
        router.registerTransport(tor); router.registerTransport(nearby)
        val bob = TransportDestination("bob-q", relationshipId = "bob")
        repeat(3) { router.send(bob, byteArrayOf(1)) }
        assertEquals(3, tor.sends)
        router.send(bob, byteArrayOf(1)); assertEquals(3, tor.sends)
        assertEquals(TransportResult.Accepted(TransportType.TOR), router.send(TransportDestination("charlie-q", relationshipId = "charlie"), byteArrayOf(2)))
        stale = false
        router.resetRelationship("bob")
        assertEquals(TransportResult.Accepted(TransportType.TOR), router.send(bob, byteArrayOf(1)))
        assertEquals(5, tor.sends)
    }

    @Test fun halfOpenHasExactlyOneProbeAndOldGenerationCannotPoisonRefresh() {
        var now = 0L
        val breaker = TorCircuitBreaker(clock = { now }, failureThreshold = 1, openMs = 10)
        val old = breaker.acquire("peer")!!
        breaker.failed(old); assertNull(breaker.acquire("peer"))
        now = 10
        val probe = breaker.acquire("peer")!!
        assertNull(breaker.acquire("peer"))
        breaker.reset("peer")
        breaker.failed(probe)
        assertNotNull(breaker.acquire("peer"))
        assertNotNull(breaker.acquire("other"))
    }

    @Test fun missingFallbackOrUnknownAckDoesNotPoisonTorHealth() {
        val breaker = TorCircuitBreaker(failureThreshold = 1)
        val router = TransportRouter(breaker = breaker)
        val destination = TransportDestination("q", relationshipId = "peer")
        router.onAckTimeout(destination, TransportType.NEARBY)
        assertNotNull(breaker.acquire("peer"))
        router.onAckTimeout(destination)
        assertNotNull(breaker.acquire("peer"))
        router.onAckTimeout(destination, TransportType.TOR)
        assertNull(breaker.acquire("peer"))
    }

    @Test fun unavailablePreparationAndThrowingCandidateDoNotKillWorker() = runTest {
        val router = TransportRouter(preparationMs = 10)
        val tor = object : Transport, AddressableTransport {
            override val type = TransportType.TOR
            override fun availability() = flowOf(TransportAvailability.Available)
            override fun canRoute(destination: TransportDestination) = false
            override suspend fun prepareRoute(destination: TransportDestination): Boolean = awaitCancellation()
            override suspend fun send(destination: TransportDestination, payload: ByteArray) = error("Must not send")
        }
        val nearby = Stub(TransportType.NEARBY) { throw IOException("Synthetic failure") }
        val wifi = Stub(TransportType.WIFI_DIRECT) { TransportResult.Accepted(TransportType.WIFI_DIRECT) }
        listOf(tor, nearby, wifi).forEach(router::registerTransport)
        assertEquals(TransportResult.Accepted(TransportType.WIFI_DIRECT), router.send(TransportDestination("q"), byteArrayOf(1)))
        assertEquals(10, testScheduler.currentTime)
    }

    @Test fun aggregateDeadlineCancelsActiveFallbackAndStopsFurtherCandidates() = runTest {
        val tor = Stub(TransportType.TOR) {
            delay(80)
            TransportResult.Failed(TransportType.TOR, "unreachable")
        }
        var invalidated = false
        var fallbackCancelled = false
        val nearby = object : Transport, RecoverableTransport {
            override val type = TransportType.NEARBY
            override fun availability() = flowOf(TransportAvailability.Available)
            override fun invalidate(destination: TransportDestination) { invalidated = true }
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                try { awaitCancellation() } finally { fallbackCancelled = true }
            }
        }
        val wifi = Stub(TransportType.WIFI_DIRECT) { TransportResult.Accepted(TransportType.WIFI_DIRECT) }
        val router = TransportRouter(torAttemptMs = 100, fallbackAttemptMs = 80, attemptMs = 100)
        listOf(tor, nearby, wifi).forEach(router::registerTransport)
        val result = router.send(TransportDestination("q", relationshipId = "peer"), byteArrayOf(1))
        assertEquals(TransportResult.Failed(TransportType.TOR, "Delivery attempt deadline"), result)
        assertEquals(100, testScheduler.currentTime)
        assertTrue(fallbackCancelled)
        assertTrue(invalidated)
        assertEquals(0, wifi.sends)
    }

    @Test fun availabilityWithoutEmissionCannotHoldFallback() = runTest {
        val tor = object : Transport {
            override val type = TransportType.TOR
            override fun availability() = kotlinx.coroutines.flow.flow<TransportAvailability> { awaitCancellation() }
            override suspend fun send(destination: TransportDestination, payload: ByteArray) = error("Unavailable transport must not send")
        }
        val nearby = Stub(TransportType.NEARBY) { TransportResult.Accepted(TransportType.NEARBY) }
        val router = TransportRouter(availabilityMs = 10)
        listOf(tor, nearby).forEach(router::registerTransport)
        assertEquals(TransportResult.Accepted(TransportType.NEARBY), router.send(TransportDestination("q"), byteArrayOf(1)))
        assertEquals(10, testScheduler.currentTime)
        assertEquals(1, nearby.sends)
    }
}
