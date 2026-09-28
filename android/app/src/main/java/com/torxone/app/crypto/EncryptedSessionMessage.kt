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
            require(bytes.size == 40) { "Invalid ratchet header size" }
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val pub = ByteArray(32)
            dis.readFully(pub)
            val pn = dis.readInt()
            val n = dis.readInt()
            require(pn >= 0 && n >= 0) { "Negative ratchet counters are invalid" }
            require(dis.available() == 0) { "Trailing bytes in ratchet header" }
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
        const val MAX_CIPHERTEXT_BYTES = 60 * 1024

        fun deserialize(bytes: ByteArray): EncryptedSessionMessage {
            require(bytes.size in 2 + 40 + 4..(2 + 40 + 4 + MAX_CIPHERTEXT_BYTES)) { "Invalid encrypted session message size" }
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val headerLen = dis.readShort().toInt()
            require(headerLen == 40) { "Invalid ratchet header length" }
            val headerBytes = ByteArray(headerLen)
            dis.readFully(headerBytes)
            val header = MessageHeader.fromByteArray(headerBytes)

            val cipherLen = dis.readInt()
            require(cipherLen in 16..MAX_CIPHERTEXT_BYTES && cipherLen == dis.available()) { "Invalid ciphertext length" }
            val ciphertext = ByteArray(cipherLen)
            dis.readFully(ciphertext)
            require(dis.available() == 0) { "Trailing bytes in encrypted session message" }

            return EncryptedSessionMessage(header, ciphertext)
        }
    }
}
