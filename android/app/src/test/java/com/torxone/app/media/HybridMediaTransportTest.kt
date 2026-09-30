package com.torxone.app.media

import com.torxone.app.transport.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HybridMediaTransportTest {
    private val frame = DedicatedMediaFrame("12345678-1234-1234-1234-123456789abc", 0, 1, ByteArray(30))

    @Test fun receiverAckKeepsAcceptedChunkOffTor() = runTest {
        var fallbackUsed = false
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult {
                fallbackUsed = true
                return TransportResult.Accepted(TransportType.TOR)
            }
        }
        val transport = HybridDedicatedMediaTransport({ _, _ -> true }, fallback)
        assertEquals(TransportResult.Accepted(TransportType.WEBRTC), transport.send(TransportDestination("queue"), frame))
        assertFalse(fallbackUsed)
    }

    @Test fun unavailableRtcFallsBackWithoutChangingEncryptedChunk() = runTest {
        var received: DedicatedMediaFrame? = null
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult {
                received = frame
                return TransportResult.Accepted(TransportType.TOR)
            }
        }
        val transport = HybridDedicatedMediaTransport({ _, _ -> false }, fallback)
        assertEquals(TransportResult.Accepted(TransportType.TOR), transport.send(TransportDestination("queue"), frame))
        assertSame(frame, received)
    }

    @Test fun brokenRtcAlsoFallsBackToTor() = runTest {
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame) =
                TransportResult.Accepted(TransportType.TOR)
        }
        val transport = HybridDedicatedMediaTransport({ _, _ -> error("closed RTC lane") }, fallback)
        assertEquals(TransportResult.Accepted(TransportType.TOR), transport.send(TransportDestination("queue"), frame))
    }
}
