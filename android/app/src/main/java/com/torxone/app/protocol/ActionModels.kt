package com.torxone.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class ReactionOperation {
    ADD,
    REMOVE
}

/**
 * Encrypted payload for MessageType.REACTION
 */
data class MessageReaction(
    val targetMessageId: String,
    val emoji: String,
    val operation: ReactionOperation
) {
    fun toByteArray(): ByteArray {
        require(targetMessageId.isNotBlank() && targetMessageId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(emoji.isNotBlank() && emoji.length <= 32)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(targetMessageId)
        dos.writeUTF(emoji)
        dos.writeUTF(operation.name)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MessageReaction {
            require(bytes.size <= 512)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val emoji = dis.readUTF()
            val op = ReactionOperation.valueOf(dis.readUTF())
            require(targetId.isNotBlank() && targetId.length <= ProtocolLimits.MAX_ID_LENGTH)
            require(emoji.isNotBlank() && emoji.length <= 32 && dis.available() == 0)
            return MessageReaction(targetId, emoji, op)
        }
    }
}

/**
 * Encrypted payload for MessageType.EDIT
 */
data class MessageEdit(
    val targetMessageId: String,
    val newText: String,
    val editVersion: Int,
    val editedAt: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(targetMessageId)
        dos.writeUTF(newText)
        dos.writeInt(editVersion)
        dos.writeLong(editedAt)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MessageEdit {
            require(bytes.size <= ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val newText = dis.readUTF()
            val version = dis.readInt()
            val editedAt = dis.readLong()
            require(targetId.isNotBlank() && targetId.length <= ProtocolLimits.MAX_ID_LENGTH)
            require(newText.length <= ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES && version > 0 && editedAt > 0 && dis.available() == 0)
            return MessageEdit(targetId, newText, version, editedAt)
        }
    }
}

/**
 * Encrypted payload for MessageType.DELETE (tombstone)
 */
data class MessageDelete(
    val targetMessageId: String,
    val deletedAt: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(targetMessageId)
        dos.writeLong(deletedAt)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MessageDelete {
            require(bytes.size <= 512)
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val time = dis.readLong()
            require(targetId.isNotBlank() && targetId.length <= ProtocolLimits.MAX_ID_LENGTH && time > 0 && dis.available() == 0)
            return MessageDelete(targetId, time)
        }
    }
}
