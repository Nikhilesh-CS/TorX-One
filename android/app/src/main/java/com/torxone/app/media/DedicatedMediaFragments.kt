package com.torxone.app.media

import com.torxone.app.protocol.ProtocolLimits
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID

/** Fragment only dedicated media ciphertext; the generic transport ceiling stays unchanged. */
object DedicatedMediaFragments {
    const val MAGIC = 0x54584d46 // TXMF
    const val PIECE_BYTES = 16 * 1024
    const val MAX_BYTES = DedicatedMediaFrameCodec.MAX_PLAINTEXT_CHUNK_BYTES + 256
    fun isFragment(bytes: ByteArray) = bytes.size >= 4 && ByteBuffer.wrap(bytes).int == MAGIC

    fun encode(payload: ByteArray): List<ByteArray> {
        require(payload.size in 1..MAX_BYTES && DedicatedMediaFrameCodec.isDedicatedMediaFrame(payload))
        val id = UUID.randomUUID().toString()
        val count = (payload.size + PIECE_BYTES - 1) / PIECE_BYTES
        return (0 until count).map { index ->
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC); out.writeUTF(id); out.writeInt(index)
                    out.writeInt(count); out.writeInt(payload.size)
                    val start = index * PIECE_BYTES
                    out.write(payload, start, minOf(PIECE_BYTES, payload.size - start))
                }
            }.toByteArray().also { require(it.size <= ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) }
        }
    }
}

class DedicatedMediaReassembler(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    sealed interface Result {
        data object Pending : Result
        data object Rejected : Result
        data class Complete(val bytes: ByteArray) : Result
    }
    private data class Assembly(val size: Int, val count: Int, val started: Long,
                                val pieces: MutableMap<Int, ByteArray> = mutableMapOf())
    private val assemblies = LinkedHashMap<String, Assembly>()

    @Synchronized fun accept(bytes: ByteArray): Result = try {
        require(bytes.size <= ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == DedicatedMediaFragments.MAGIC)
        val id = input.readUTF()
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val index = input.readInt(); val count = input.readInt(); val size = input.readInt()
        require(size in 1..DedicatedMediaFragments.MAX_BYTES)
        require(count == (size + DedicatedMediaFragments.PIECE_BYTES - 1) / DedicatedMediaFragments.PIECE_BYTES)
        require(index in 0 until count)
        require(input.available() == minOf(DedicatedMediaFragments.PIECE_BYTES, size - index * DedicatedMediaFragments.PIECE_BYTES))
        val now = clock()
        assemblies.entries.removeAll { now - it.value.started >= 120_000 }
        val assembly = assemblies[id] ?: run {
            if (assemblies.size >= 32) assemblies.remove(assemblies.keys.first())
            Assembly(size, count, now).also { assemblies[id] = it }
        }
        if (assembly.size != size || assembly.count != count) {
            assemblies.remove(id); Result.Rejected
        } else {
            val piece = ByteArray(input.available()).also(input::readFully)
            val previous = assembly.pieces[index]
            if (previous != null && !previous.contentEquals(piece)) {
                assemblies.remove(id); Result.Rejected
            } else {
                assembly.pieces[index] = piece
                if (assembly.pieces.size < count) Result.Pending
                else {
                    assemblies.remove(id)
                    val complete = ByteArrayOutputStream(size).apply {
                        (0 until count).forEach { write(assembly.pieces.getValue(it)) }
                    }.toByteArray()
                    if (DedicatedMediaFrameCodec.isDedicatedMediaFrame(complete)) Result.Complete(complete)
                    else Result.Rejected
                }
            }
        }
    } catch (_: Exception) { Result.Rejected }
}
