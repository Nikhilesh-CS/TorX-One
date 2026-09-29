package com.torxone.app.transport.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.UUID

data class MeshCustodyAck(
    val packetId: String,
    val expiresAt: Long
)

object MeshCustodyAckCodec {
    private const val MAGIC = 0x54584341 // TXCA
    private const val MAX_BYTES = 128

    fun isAck(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    fun encode(ack: MeshCustodyAck): ByteArray {
        require(runCatching { UUID.fromString(ack.packetId) }.isSuccess)
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeUTF(ack.packetId)
                data.writeLong(ack.expiresAt)
            }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): MeshCustodyAck {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC)
            val packetId = data.readUTF()
            val expiresAt = data.readLong()
            require(data.available() == 0 && runCatching { UUID.fromString(packetId) }.isSuccess)
            MeshCustodyAck(packetId, expiresAt)
        }
    }
}
