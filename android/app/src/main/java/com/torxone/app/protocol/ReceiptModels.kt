package com.torxone.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Logical authenticated delivery acknowledgment.
 */
data class DeliveryAck(
    val originalMessageId: String,
    val originalEnvelopeId: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(originalMessageId)
        dos.writeUTF(originalEnvelopeId ?: "")
        dos.writeLong(receivedAt)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): DeliveryAck {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val msgId = dis.readUTF()
            val envIdRaw = dis.readUTF()
            val envId = if (envIdRaw.isEmpty()) null else envIdRaw
            val time = dis.readLong()
            return DeliveryAck(msgId, envId, time)
        }
    }
}
