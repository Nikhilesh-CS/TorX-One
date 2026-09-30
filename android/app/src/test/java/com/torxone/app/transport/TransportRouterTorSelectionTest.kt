package com.torxone.app.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRouterTorSelectionTest {
    private val payload = byteArrayOf(7, 8, 9)
    private val destination = TransportDestination("queue")

    @Test
    fun `Tor is preferred when both routes are ready`() = runBlocking {
        val router = TransportRouter()
        val nearby = RoutedStub(TransportType.NEARBY)
        val tor = RoutedStub(TransportType.TOR)
        router.registerTransport(tor)
        router.registerTransport(nearby)

        val result = router.send(destination, payload)

        assertEquals(TransportResult.Accepted(TransportType.TOR), result)
        assertEquals(0, nearby.sends)
        assertEquals(1, tor.sends)
    }

    @Test
    fun `Tor is selected when Nearby has no route`() = runBlocking {
        val router = TransportRouter()
        val nearby = RoutedStub(TransportType.NEARBY, route = false)
        val tor = RoutedStub(TransportType.TOR)
        router.registerTransport(nearby)
        router.registerTransport(tor)

        assertEquals(TransportResult.Accepted(TransportType.TOR), router.send(destination, payload))
        assertEquals(0, nearby.sends)
        assertEquals(1, tor.sends)
    }

    @Test
    fun `router fails over from Tor send failure to Nearby with identical ciphertext`() = runBlocking {
        val router = TransportRouter()
        val nearby = RoutedStub(TransportType.NEARBY)
        val tor = RoutedStub(TransportType.TOR, result = TransportResult.Failed(TransportType.TOR, "lost"))
        router.registerTransport(nearby)
        router.registerTransport(tor)

        assertEquals(TransportResult.Accepted(TransportType.NEARBY), router.send(destination, payload))
        assertArrayEquals(payload, nearby.lastPayload)
        assertArrayEquals(payload, tor.lastPayload)
    }

    @Test
    fun `unavailable Nearby is skipped and Tor is used`() = runBlocking {
        val router = TransportRouter()
        val nearby = RoutedStub(
            TransportType.NEARBY,
            availability = TransportAvailability.Unavailable("offline")
        )
        val tor = RoutedStub(TransportType.TOR)
        router.registerTransport(nearby)
        router.registerTransport(tor)

        assertEquals(TransportResult.Accepted(TransportType.TOR), router.send(destination, payload))
        assertEquals(0, nearby.sends)
        assertEquals(1, tor.sends)
    }

    @Test
    fun `no usable route returns failure for durable outbox retry`() = runBlocking {
        val router = TransportRouter()
        router.registerTransport(RoutedStub(TransportType.NEARBY, route = false))
        router.registerTransport(RoutedStub(TransportType.TOR, route = false))

        val result = router.send(destination, payload)

        assertTrue(result is TransportResult.Failed)
        assertTrue((result as TransportResult.Failed).error.contains("No route"))
    }

    private class RoutedStub(
        override val type: TransportType,
        private val route: Boolean = true,
        availability: TransportAvailability = TransportAvailability.Available,
        private val result: TransportResult = TransportResult.Accepted(type)
    ) : Transport, AddressableTransport {
        private val available = MutableStateFlow(availability)
        var sends = 0
        var lastPayload: ByteArray? = null

        override fun availability(): Flow<TransportAvailability> = available
        override fun canRoute(destination: TransportDestination): Boolean = route
        override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
            sends++
            lastPayload = payload.copyOf()
            return result
        }
    }
}
