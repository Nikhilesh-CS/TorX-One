package com.torxone.app.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Ratchet header attached to every encrypted message.
 */
data class MessageHeader(
    /** Current ratchet public key of the sender (32 bytes) */
    val ratchetPublicKey: ByteArray,
    /** Length of previous sending chain (pn) */
    val previousChainLength: Int,
    /** Message counter in current sending chain (n) */
    val messageNumber: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageHeader) return false
        return ratchetPublicKey.contentEquals(other.ratchetPublicKey) &&
                previousChainLength == other.previousChainLength &&
                messageNumber == other.messageNumber
    }

    override fun hashCode(): Int {
        var result = ratchetPublicKey.contentHashCode()
        result = 31 * result + previousChainLength
        result = 31 * result + messageNumber
        return result
    }

    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(ratchetPublicKey)
        dos.writeInt(previousChainLength)
        dos.writeInt(messageNumber)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MessageHeader {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val pub = ByteArray(32)
            dis.readFully(pub)
            val pn = dis.readInt()
            val n = dis.readInt()
            return MessageHeader(pub, pn, n)
        }
    }
}

/**
 * Encrypted message produced by Double Ratchet.
 */
data class EncryptedSessionMessage(
    val header: MessageHeader,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedSessionMessage) return false
        return header == other.header && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    /**
     * Serializes header + ciphertext to opaque payload.
     */
    fun serialize(): ByteArray {
        val headerBytes = header.toByteArray()
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(headerBytes.size)
        dos.write(headerBytes)
        dos.writeInt(ciphertext.size)
        dos.write(ciphertext)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun deserialize(bytes: ByteArray): EncryptedSessionMessage {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val headerLen = dis.readShort().toInt()
            val headerBytes = ByteArray(headerLen)
            dis.readFully(headerBytes)
            val header = MessageHeader.fromByteArray(headerBytes)

            val cipherLen = dis.readInt()
            val ciphertext = ByteArray(cipherLen)
            dis.readFully(ciphertext)

            return EncryptedSessionMessage(header, ciphertext)
        }
    }
}
