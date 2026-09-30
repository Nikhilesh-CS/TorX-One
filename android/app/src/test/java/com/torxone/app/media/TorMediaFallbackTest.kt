package com.torxone.app.media

import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.protocol.ProtocolLimits
import com.torxone.app.transport.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class TorMediaFallbackTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val key = ByteArray(32) { it.toByte() }

    private suspend fun transfer(chunkSize: Int) {
        val original = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val encrypted = MediaCrypto.encrypt(key, original)
        val total = (encrypted.size + chunkSize - 1) / chunkSize
        val reconstructed = ByteArrayOutputStream()
        var frames = 0
        val hub = IncomingTransportHub(dedicatedMediaFrameHandler = { raw, _ ->
            val frame = DedicatedMediaFrameCodec.decode(raw)
            assertEquals(reconstructed.size() / chunkSize, frame.chunkIndex)
            reconstructed.write(DedicatedMediaChunkCrypto.decrypt(key, id, "rel", frame.chunkIndex, total, frame.encryptedChunk))
            true
        })
        val router = TransportRouter()
        router.registerTransport(object : Transport {
            override val type = TransportType.TOR
            override fun availability() = flowOf<TransportAvailability>(TransportAvailability.Available)
            override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
                frames++
                assertTrue("Every Tor frame must fit its unchanged ceiling", payload.size <= ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
                return if (hub.onRawFrameReceived(payload, type)) TransportResult.Accepted(type)
                else TransportResult.Failed(type, "Receiver rejected frame")
            }
        })
        val hybrid = HybridDedicatedMediaTransport({ _, _ -> false }, RoutedDedicatedMediaTransport(router))
        for (index in 0 until total) {
            val chunk = encrypted.copyOfRange(index * chunkSize, minOf(encrypted.size, (index + 1) * chunkSize))
            val frame = DedicatedMediaFrame(id, index, total,
                DedicatedMediaChunkCrypto.encrypt(key, id, "rel", index, total, chunk))
            assertEquals(TransportResult.Accepted(TransportType.TOR), hybrid.send(TransportDestination("send", relationshipId = "rel"), frame))
        }
        val received = reconstructed.toByteArray()
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(encrypted), MessageDigest.getInstance("SHA-256").digest(received))
        assertArrayEquals(original, MediaCrypto.decrypt(key, received))
        if (chunkSize > ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) assertTrue(frames > total)
    }

    @Test fun fourMbMediaUsesTorWhenRtcUnavailable() = runBlocking { transfer(MediaService.DEFAULT_CHUNK_SIZE) }
    @Test fun existing256KbGeometryStillFallsBackToTor() = runBlocking { transfer(DedicatedMediaFrameCodec.MAX_PLAINTEXT_CHUNK_BYTES) }
    @Test fun fragmentsReassembleOutOfOrderAndIgnoreDuplicates() {
        val frame = DedicatedMediaFrame(id, 0, 1, ByteArray(256 * 1024))
        val encoded = DedicatedMediaFrameCodec.encode(frame)
        val pieces = DedicatedMediaFragments.encode(encoded)
        val receiver = DedicatedMediaReassembler()
        assertEquals(DedicatedMediaReassembler.Result.Pending, receiver.accept(pieces.last()))
        assertEquals(DedicatedMediaReassembler.Result.Pending, receiver.accept(pieces.last()))
        var result: DedicatedMediaReassembler.Result = DedicatedMediaReassembler.Result.Pending
        pieces.dropLast(1).reversed().forEach { result = receiver.accept(it) }
        assertArrayEquals(encoded, (result as DedicatedMediaReassembler.Result.Complete).bytes)
    }
    @Test fun invalidLengthAndConflictingDuplicateAreRejected() {
        val pieces = DedicatedMediaFragments.encode(DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(id, 0, 1, ByteArray(70_000))))
        val receiver = DedicatedMediaReassembler()
        assertEquals(DedicatedMediaReassembler.Result.Pending, receiver.accept(pieces.first()))
        val conflicting = pieces.first().clone().apply { this[lastIndex] = 1 }
        assertEquals(DedicatedMediaReassembler.Result.Rejected, receiver.accept(conflicting))
        assertEquals(DedicatedMediaReassembler.Result.Rejected, receiver.accept(pieces.first().copyOf(4)))
        assertEquals(DedicatedMediaReassembler.Result.Rejected, receiver.accept(ByteArray(ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES + 1)))
    }
    @Test fun fragmentsExpireWithoutGrowingUnbounded() {
        var now = 0L
        val pieces = DedicatedMediaFragments.encode(DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(id, 0, 1, ByteArray(70_000))))
        val receiver = DedicatedMediaReassembler { now }
        receiver.accept(pieces.first())
        now = 120_001
        pieces.drop(1).forEach { assertEquals(DedicatedMediaReassembler.Result.Pending, receiver.accept(it)) }
        val result = receiver.accept(pieces.first()) as DedicatedMediaReassembler.Result.Complete
        assertTrue(DedicatedMediaFrameCodec.isDedicatedMediaFrame(result.bytes))
    }
}
