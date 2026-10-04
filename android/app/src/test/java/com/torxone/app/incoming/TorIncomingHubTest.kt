package com.torxone.app.incoming

import com.torxone.app.media.DedicatedMediaFrame
import com.torxone.app.media.DedicatedMediaFrameCodec
import com.torxone.app.media.DedicatedMediaFragments
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TorIncomingHubTest {
    @Test fun torMediaCannotUseTheHandlerWithoutAuthenticatedAdmission() = runBlocking {
        var legacyCalled = false
        val hub = IncomingTransportHub(dedicatedMediaFrameHandler = { _, _ -> legacyCalled = true; true })
        val frame = DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(java.util.UUID.randomUUID().toString(), 0, 1, ByteArray(32)))
        assertFalse(hub.onRawTorFrameReceived(frame) { true })
        assertFalse(legacyCalled)
        assertTrue(hub.onRawFrameReceived(frame, TransportType.NEARBY))
        assertTrue(legacyCalled)
    }

    @Test fun torMediaPassesTheSameVerifiedAdmissionCallbackToTheMediaConsumer() = runBlocking {
        val hub = IncomingTransportHub()
        val frame = DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(java.util.UUID.randomUUID().toString(), 0, 1, ByteArray(32)))
        var admitted: String? = null
        hub.admittedMediaFrameHandler = { bytes, type, authenticate ->
            assertArrayEquals(frame, bytes)
            assertEquals(TransportType.TOR, type)
            authenticate("verified-peer")
        }
        assertFalse(hub.onRawTorFrameReceived(frame) { admitted = it; false })
        assertEquals("verified-peer", admitted)
        assertTrue(hub.onRawTorFrameReceived(frame) { it == "verified-peer" })
    }

    @Test fun torRejectsFragmentsBeforeReassemblyOrRelationshipAdmission() = runBlocking {
        var called = false
        val hub = IncomingTransportHub(dedicatedMediaFrameHandler = { _, _ -> called = true; true })
        hub.admittedMediaFrameHandler = { _, _, _ -> called = true; true }
        val fragmentHeader = ByteBuffer.allocate(4).putInt(DedicatedMediaFragments.MAGIC).array()
        assertFalse(hub.onRawTorFrameReceived(fragmentHeader) { called = true; true })
        assertFalse(called)
    }
}
