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

/**
 * Logical authenticated read receipt with batch/up-to semantics.
 * Single receipt acknowledges all messages up to upToMessageId.
 */
data class ReadReceipt(
    val conversationId: String,
    val upToMessageId: String,
    val readAt: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(conversationId)
        dos.writeUTF(upToMessageId)
        dos.writeLong(readAt)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): ReadReceipt {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val convId = dis.readUTF()
            val upToMsgId = dis.readUTF()
            val time = dis.readLong()
            return ReadReceipt(convId, upToMsgId, time)
        }
    }
}

enum class PresenceState {
    ONLINE,
    OFFLINE
}

/**
 * Encrypted ephemeral presence update event.
 */
data class PresenceUpdate(
    val state: PresenceState,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(state.name)
        dos.writeLong(timestamp)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): PresenceUpdate {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val stateName = dis.readUTF()
            val time = dis.readLong()
            val state = try {
                PresenceState.valueOf(stateName)
            } catch (_: Exception) {
                PresenceState.OFFLINE
            }
            return PresenceUpdate(state, time)
        }
    }
}

