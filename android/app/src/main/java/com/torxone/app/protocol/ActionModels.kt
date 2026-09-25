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
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val emoji = dis.readUTF()
            val op = try {
                ReactionOperation.valueOf(dis.readUTF())
            } catch (_: Exception) {
                ReactionOperation.ADD
            }
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
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val newText = dis.readUTF()
            val version = dis.readInt()
            val editedAt = dis.readLong()
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
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val targetId = dis.readUTF()
            val time = dis.readLong()
            return MessageDelete(targetId, time)
        }
    }
}
