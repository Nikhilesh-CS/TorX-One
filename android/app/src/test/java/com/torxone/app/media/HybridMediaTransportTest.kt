package com.torxone.app.media

import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test fun busyRtcPeerDoesNotDelayAnotherPeersEncryptedTorFallback() = runTest {
        val gate = FileRtcSendGate()
        val enteredRtc = CompletableDeferred<Unit>()
        val releaseRtc = CompletableDeferred<Unit>()
        val fallbackPeers = mutableListOf<String>()
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, received: DedicatedMediaFrame): TransportResult {
                assertSame(frame, received)
                fallbackPeers += destination.relationshipId!!
                return TransportResult.Accepted(TransportType.TOR)
            }
        }
        val transport = HybridDedicatedMediaTransport({ _, _ ->
            gate.sendWhenIdle {
                enteredRtc.complete(Unit)
                releaseRtc.await()
                true
            }
        }, fallback)
        val peerA = async { transport.send(TransportDestination("queue-a", relationshipId = "peer-a"), frame) }
        enteredRtc.await()

        val before = currentTime
        assertEquals(TransportResult.Accepted(TransportType.TOR),
            transport.send(TransportDestination("queue-b", relationshipId = "peer-b"), frame))
        assertEquals(before, currentTime)
        assertEquals(listOf("peer-b"), fallbackPeers)
        assertFalse(peerA.isCompleted)

        releaseRtc.complete(Unit)
        assertEquals(TransportResult.Accepted(TransportType.WEBRTC), peerA.await())
        assertTrue(gate.sendWhenIdle { true })
    }

    @Test fun stalledRtcCallbackHasFiniteDeadlineAndPreservesEncryptedFallback() = runTest {
        var receivedDestination: TransportDestination? = null
        val destination = TransportDestination("queue", relationshipId = "peer")
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(target: TransportDestination, received: DedicatedMediaFrame): TransportResult {
                receivedDestination = target
                assertSame(frame, received)
                return TransportResult.Accepted(TransportType.TOR)
            }
        }
        val gate = FileRtcSendGate()
        val transport = HybridDedicatedMediaTransport({ _, _ ->
            gate.sendWhenIdle { awaitCancellation() }
        }, fallback, rtcAttemptTimeoutMs = 100)
        val before = currentTime

        assertEquals(TransportResult.Accepted(TransportType.TOR), transport.send(destination, frame))
        assertEquals(100L, currentTime - before)
        assertSame(destination, receivedDestination)
        assertTrue(gate.sendWhenIdle { true })
    }

    @Test fun callerCancellationReleasesRtcGateWithoutSendingCanceledChunkToTor() = runTest {
        val gate = FileRtcSendGate()
        val enteredRtc = CompletableDeferred<Unit>()
        var fallbackUsed = false
        val fallback = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult {
                fallbackUsed = true
                return TransportResult.Accepted(TransportType.TOR)
            }
        }
        val transport = HybridDedicatedMediaTransport({ _, _ ->
            gate.sendWhenIdle { enteredRtc.complete(Unit); awaitCancellation() }
        }, fallback)
        val sending = launch { transport.send(TransportDestination("queue"), frame) }
        enteredRtc.await()
        sending.cancelAndJoin()

        assertFalse(fallbackUsed)
        assertTrue(gate.sendWhenIdle { true })
    }
}
