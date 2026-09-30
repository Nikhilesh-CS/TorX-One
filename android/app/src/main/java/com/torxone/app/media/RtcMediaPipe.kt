package com.torxone.app.media

import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded framing on an authenticated, reliable, ordered RTCDataChannel.
 * ACK means the receiver accepted/persisted the chunk, not merely SCTP send().
 * Existing media AEAD, durable chunk bitmaps and FILE_COMPLETE remain authoritative.
 */
class RtcMediaPipe(
    parentScope: CoroutineScope,
    private val sendPacket: (ByteArray) -> Boolean,
    private val bufferedBytes: () -> Long,
    private val acceptFrame: suspend (ByteArray) -> Boolean,
    private val ackTimeoutMs: Long = 30_000
) {
    companion object {
        const val FRAGMENT_BYTES = 16 * 1024
        const val MAX_FRAME_BYTES = 263_000
        private const val MAGIC = 0x5458524D
        private const val HEADER_BYTES = 21
    }

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val inbox = Channel<ByteArray>(32)
    private val sendMutex = Mutex()
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    @Volatile private var closed = false

    init {
        scope.launch {
            var frameId = 0L
            var frame: ByteArray? = null
            var received = 0
            try {
                for (packet in inbox) {
                    val input = ByteBuffer.wrap(packet)
                    require(input.remaining() >= 13 && input.int == MAGIC)
                    when (input.get().toInt()) {
                        2 -> {
                            val id = input.long
                            require(input.remaining() == 1)
                            pending[id]?.complete(input.get().toInt() == 1)
                        }
                        1 -> {
                            val id = input.long
                            require(input.remaining() >= 8)
                            val size = input.int
                            val offset = input.int
                            require(size in 1..MAX_FRAME_BYTES && offset in 0 until size)
                            require(input.remaining() in 1..FRAGMENT_BYTES)
                            if (offset == 0) {
                                frameId = id
                                frame = ByteArray(size)
                                received = 0
                            }
                            val target = requireNotNull(frame)
                            require(id == frameId && target.size == size && offset == received)
                            require(input.remaining() <= size - received)
                            val count = input.remaining()
                            input.get(target, received, count)
                            received += count
                            if (received == size) {
                                val accepted = try { acceptFrame(target) } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    false
                                }
                                frame = null
                                val ack = ByteBuffer.allocate(14).putInt(MAGIC).put(2.toByte())
                                    .putLong(id).put(if (accepted) 1.toByte() else 0.toByte()).array()
                                if (!sendPacket(ack)) close()
                            }
                        }
                        else -> error("Unknown RTC media packet")
                    }
                }
            } catch (error: Exception) {
                close()
                if (error is CancellationException) throw error
            }
        }
    }

    /** Called from WebRTC's observer. Never queues an unbounded file or blocks its thread. */
    fun receive(packet: ByteArray) {
        if (closed) return
        if (packet.size !in 14..(FRAGMENT_BYTES + HEADER_BYTES) || !inbox.trySend(packet).isSuccess) close()
    }

    suspend fun send(frame: ByteArray): Boolean = sendMutex.withLock {
        require(frame.size in 1..MAX_FRAME_BYTES)
        if (closed) return@withLock false
        val id = SecureRandom().nextLong()
        val ack = CompletableDeferred<Boolean>()
        pending[id] = ack
        try {
            withTimeoutOrNull(ackTimeoutMs) {
                var offset = 0
                while (offset < frame.size) {
                    ensureActive()
                    while (!closed && bufferedBytes() > 256 * 1024) delay(10)
                    if (closed) return@withTimeoutOrNull false
                    val count = minOf(FRAGMENT_BYTES, frame.size - offset)
                    val packet = ByteBuffer.allocate(HEADER_BYTES + count).putInt(MAGIC)
                        .put(1.toByte()).putLong(id).putInt(frame.size).putInt(offset)
                        .put(frame, offset, count).array()
                    if (!sendPacket(packet)) return@withTimeoutOrNull false
                    offset += count
                    yield()
                }
                ack.await()
            } ?: false
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        closed = true
        pending.values.forEach { it.complete(false) }
        inbox.close()
        job.cancel()
    }
}
