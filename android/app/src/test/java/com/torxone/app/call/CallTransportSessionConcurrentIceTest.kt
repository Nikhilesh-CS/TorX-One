package com.torxone.app.call

import com.torxone.app.network.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallTransportSessionConcurrentIceTest {

    private class TestMockSocket : Socket() {
        private val closed = AtomicBoolean(false)
        val stream = ByteArrayOutputStream()

        override fun isClosed(): Boolean = closed.get()
        override fun isConnected(): Boolean = !closed.get()
        override fun getOutputStream(): OutputStream = stream
        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun testEightConcurrentIceCandidates_singleSocketReusedAndFramesIntact() = runTest {
        val socketCreationCount = AtomicInteger(0)
        var createdSocket: TestMockSocket? = null

        val session = CallTransportSession(
            callId = "call-concurrent-1",
            peerKey = "peer_test_key",
            transport = Transport.TOR,
            onionHost = "peer.onion",
            torSocketFactory = { host, port, timeout ->
                socketCreationCount.incrementAndGet()
                TestMockSocket().also { createdSocket = it }
            }
        )

        // Generate 8 distinct ICE candidate payload frames
        val candidateFrames = (1..8).map { index ->
            """{"callId":"call-concurrent-1","candidate":"candidate_$index","sdpMid":"audio","sdpMLineIndex":$index}"""
        }

        // Fire 8 sends concurrently across Dispatchers.IO
        val sendResults = candidateFrames.map { frame ->
            async(Dispatchers.IO) {
                session.sendFrame(frame)
            }
        }.awaitAll()

        // 1. All 8 sends must report success
        assertEquals(8, sendResults.size)
        assertTrue(sendResults.all { it })

        // 2. Exactly ONE socket must have been established
        assertEquals(1, socketCreationCount.get())

        // 3. Inspect the written output
        val outputString = createdSocket?.stream?.toString("UTF-8") ?: ""
        val writtenLines = outputString.lines().filter { it.isNotBlank() }

        // 4. All 8 frames must be delivered intact
        assertEquals(8, writtenLines.size)

        // 5. No duplicate frames and all original frames are present
        val writtenSet = writtenLines.toSet()
        assertEquals(8, writtenSet.size)
        candidateFrames.forEach { frame ->
            assertTrue("Expected frame $frame was not in written output", writtenSet.contains(frame))
        }

        // 6. Verify close() behavior: socket is closed and further sends return false without new sockets
        session.close()
        assertTrue(createdSocket?.isClosed == true)

        val postCloseResult = session.sendFrame("""{"callId":"call-concurrent-1","candidate":"post_close"}""")
        assertFalse(postCloseResult)
        assertEquals(1, socketCreationCount.get()) // No new socket created after close
    }

    @Test
    fun testNearbyTransport_routesDirectlyWithoutTorSocket() = runTest {
        val torSocketCreationCount = AtomicInteger(0)
        val deliveredFrames = mutableListOf<String>()

        val session = CallTransportSession(
            callId = "call-nearby-1",
            peerKey = "peer_nearby_key",
            transport = Transport.NEARBY_DIRECT,
            endpointId = "ep_123",
            nearbySender = { ep, frame ->
                assertEquals("ep_123", ep)
                deliveredFrames.add(frame)
                true
            },
            torSocketFactory = { _, _, _ ->
                torSocketCreationCount.incrementAndGet()
                TestMockSocket()
            }
        )

        val frame = """{"type":"call_offer","callId":"call-nearby-1"}"""
        val result = session.sendFrame(frame)

        assertTrue(result)
        assertEquals(1, deliveredFrames.size)
        assertEquals(frame, deliveredFrames[0])
        assertEquals(0, torSocketCreationCount.get()) // Tor socket factory never touched
    }
}
