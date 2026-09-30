package com.torxone.app.media

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RtcMediaPipeTest {
    @Test fun largeChunkIsFragmentedAndAckedAfterReceiverAcceptance() = runTest {
        val source = ByteArray(256 * 1024 + 100) { (it % 251).toByte() }
        var received: ByteArray? = null
        var largestPacket = 0
        lateinit var sender: RtcMediaPipe
        lateinit var receiver: RtcMediaPipe
        sender = RtcMediaPipe(backgroundScope, { packet ->
            largestPacket = maxOf(largestPacket, packet.size)
            receiver.receive(packet)
            true
        }, { 0 }, { false })
        receiver = RtcMediaPipe(backgroundScope, { sender.receive(it); true }, { 0 }, {
            received = it
            true
        })
        val result = async { sender.send(source) }
        runCurrent()
        assertTrue(result.await())
        assertArrayEquals(source, received)
        assertTrue(largestPacket <= RtcMediaPipe.FRAGMENT_BYTES + 21)
        sender.close(); receiver.close()
    }

    @Test fun rejectedReceiverFrameIsNotAcknowledgedAsAccepted() = runTest {
        lateinit var sender: RtcMediaPipe
        val receiver = RtcMediaPipe(backgroundScope, { sender.receive(it); true }, { 0 }, { false })
        sender = RtcMediaPipe(backgroundScope, { receiver.receive(it); true }, { 0 }, { false })
        val result = async { sender.send(ByteArray(100)) }
        runCurrent()
        assertFalse(result.await())
        sender.close(); receiver.close()
    }

    @Test fun missingAckTimesOutForTorFallback() = runTest {
        val sender = RtcMediaPipe(backgroundScope, { true }, { 0 }, { false }, ackTimeoutMs = 100)
        val result = async { sender.send(ByteArray(100)) }
        advanceUntilIdle()
        assertFalse(result.await())
        sender.close()
    }

    @Test fun closedChannelDoesNotAcceptFileChunks() = runTest {
        val sender = RtcMediaPipe(backgroundScope, { true }, { 0 }, { true })
        sender.close()
        assertFalse(sender.send(ByteArray(100)))
    }

    @Test fun oversizedInputClosesBoundedReceiver() = runTest {
        val pipe = RtcMediaPipe(backgroundScope, { true }, { 0 }, { fail("Must not dispatch invalid input"); false })
        pipe.receive(ByteArray(RtcMediaPipe.FRAGMENT_BYTES + 22))
        assertFalse(pipe.send(ByteArray(1)))
    }
}
