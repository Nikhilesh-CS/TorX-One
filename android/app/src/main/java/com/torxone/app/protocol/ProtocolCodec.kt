package com.torxone.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * High-performance, deterministic canonical binary codec for protocol envelopes.
 */
object ProtocolCodec {

    private const val SECURE_MAGIC = 0x54585345 // "TXSE" (TorX Secure Envelope)
    private const val TRANSPORT_MAGIC = 0x54585445 // "TXTE" (TorX Transport Envelope)

    fun encodeSecureEnvelope(envelope: SecureEnvelope): ByteArray {
        require(envelope.protocolVersion == 1)
        require(envelope.logicalMessageId.isNotBlank() && envelope.logicalMessageId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.conversationId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.senderIdentity.isNotBlank() && envelope.senderIdentity.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.recipientBinding.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.timestamp > 0 && envelope.directionSequence >= 0)
        com.torxone.app.privacy.DisappearingPolicy.validate(envelope.timestamp, envelope.expiresAt)
        require(envelope.payload.size <= ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES)
        require(envelope.replyToMessageId == null || envelope.replyToMessageId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.groupMetadata == null || (envelope.groupMetadata.groupId.isNotBlank() && envelope.groupMetadata.groupId.length <= ProtocolLimits.MAX_ID_LENGTH && envelope.groupMetadata.groupEpoch >= 0 && envelope.groupMetadata.keyVersion >= 0))
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(SECURE_MAGIC)
        dos.writeShort(envelope.protocolVersion)
        dos.writeUTF(envelope.logicalMessageId)
        dos.writeUTF(envelope.conversationId)
        dos.writeUTF(envelope.senderIdentity)
        dos.writeUTF(envelope.recipientBinding)
        val wireType = if (envelope.messageType == MessageType.UNKNOWN) requireNotNull(envelope.unknownMessageType) else envelope.messageType.name
        require(wireType.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        dos.writeUTF(wireType)
        dos.writeLong(envelope.timestamp)
        dos.writeUTF(envelope.replyToMessageId ?: "")
        dos.writeLong(envelope.directionSequence)
        dos.writeInt(envelope.payload.size)
        dos.write(envelope.payload)

        if (envelope.groupMetadata != null) {
            dos.writeBoolean(true)
            dos.writeUTF(envelope.groupMetadata.groupId)
            dos.writeInt(envelope.groupMetadata.groupEpoch)
            dos.writeInt(envelope.groupMetadata.keyVersion)
        } else {
            dos.writeBoolean(false)
        }

        if (envelope.expiresAt != null) {
            dos.writeInt(0x54584531) // TXE1; only sent after DISAPPEARING_V1 advertisement.
            dos.writeLong(envelope.expiresAt)
        }
        dos.flush()

        return baos.toByteArray()
    }

    fun decodeSecureEnvelope(bytes: ByteArray): SecureEnvelope {
        require(bytes.size <= ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES + 1024) { "Payload exceeds maximum limit" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == SECURE_MAGIC) { "Invalid secure envelope magic header" }

        val version = dis.readShort().toInt()
        require(version == 1) { "Unsupported secure envelope version" }
        val messageId = dis.readUTF()
        val conversationId = dis.readUTF()
        val senderIdentity = dis.readUTF()
        val recipientBinding = dis.readUTF()
        val typeStr = dis.readUTF()
        require(typeStr.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        val messageType = try {
            MessageType.valueOf(typeStr)
        } catch (_: Exception) {
            MessageType.UNKNOWN
        }
        val timestamp = dis.readLong()
        val replyToRaw = dis.readUTF()
        val replyTo = if (replyToRaw.isEmpty()) null else replyToRaw
        val directionSequence = dis.readLong()
        require(messageId.isNotBlank() && messageId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(conversationId.length <= ProtocolLimits.MAX_ID_LENGTH && senderIdentity.isNotBlank() && senderIdentity.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(recipientBinding.length <= ProtocolLimits.MAX_ID_LENGTH && (replyTo == null || replyTo.length <= ProtocolLimits.MAX_ID_LENGTH))
        require(timestamp > 0 && directionSequence >= 0)

        val payloadSize = dis.readInt()
        require(payloadSize in 0..ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES) { "Invalid payload size: $payloadSize" }
        val payload = ByteArray(payloadSize)
        dis.readFully(payload)

        require(dis.available() >= 1) { "Missing group metadata presence flag" }
        val groupMetadata = if (dis.available() > 0) {
            val hasGroup = dis.readBoolean()
            if (hasGroup) {
                val gId = dis.readUTF()
                val gEpoch = dis.readInt()
                val kVer = dis.readInt()
                require(gId.isNotBlank() && gId.length <= ProtocolLimits.MAX_ID_LENGTH && gEpoch >= 0 && kVer >= 0)
                GroupEnvelopeMetadata(groupId = gId, groupEpoch = gEpoch, keyVersion = kVer)
            } else null
        } else null
        val expiresAt = if (dis.available() == 0) null else {
            require(dis.available() == 12 && dis.readInt() == 0x54584531) { "Invalid secure envelope extension" }
            dis.readLong().also { com.torxone.app.privacy.DisappearingPolicy.validate(timestamp, it) }
        }
        require(dis.available() == 0) { "Trailing bytes in secure envelope" }

        return SecureEnvelope(
            protocolVersion = version,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = senderIdentity,
            recipientBinding = recipientBinding,
            messageType = messageType,
            timestamp = timestamp,
            payload = payload,
            replyToMessageId = replyTo,
            groupMetadata = groupMetadata,
            directionSequence = directionSequence,
            expiresAt = expiresAt,
            unknownMessageType = if (messageType == MessageType.UNKNOWN) typeStr else null
        )
    }

    fun encodeTransportEnvelope(envelope: OpaqueTransportEnvelope): ByteArray {
        require(envelope.version == 1)
        require(envelope.envelopeId.isNotBlank() && envelope.envelopeId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.queueAddress.isNotBlank() && envelope.queueAddress.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(envelope.queueAuthenticator.size == 32)
        require(envelope.opaqueCiphertext.size in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(TRANSPORT_MAGIC)
        dos.writeShort(envelope.version)
        dos.writeUTF(envelope.envelopeId)
        dos.writeUTF(envelope.queueAddress)

        dos.writeShort(envelope.queueAuthenticator.size)
        dos.write(envelope.queueAuthenticator)

        dos.writeInt(envelope.opaqueCiphertext.size)
        dos.write(envelope.opaqueCiphertext)
        dos.flush()

        return baos.toByteArray()
    }

    fun decodeTransportEnvelope(bytes: ByteArray): OpaqueTransportEnvelope {
        require(bytes.size <= ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) { "Transport frame exceeds max size" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == TRANSPORT_MAGIC) { "Invalid transport envelope magic header" }

        val version = dis.readShort().toInt()
        require(version == 1) { "Unsupported transport envelope version" }
        val envelopeId = dis.readUTF()
        val queueAddress = dis.readUTF()

        val authLen = dis.readShort().toInt()
        require(authLen == 32) { "Invalid queue authenticator length" }
        val auth = ByteArray(authLen)
        dis.readFully(auth)

        val cipherLen = dis.readInt()
        require(cipherLen in 0..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) { "Invalid ciphertext length: $cipherLen" }
        val ciphertext = ByteArray(cipherLen)
        dis.readFully(ciphertext)
        require(envelopeId.isNotBlank() && envelopeId.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(queueAddress.isNotBlank() && queueAddress.length <= ProtocolLimits.MAX_ID_LENGTH)
        require(cipherLen > 0 && dis.available() == 0) { "Invalid ciphertext or trailing transport bytes" }

        return OpaqueTransportEnvelope(
            version = version,
            envelopeId = envelopeId,
            queueAddress = queueAddress,
            opaqueCiphertext = ciphertext,
            queueAuthenticator = auth
        )
    }
}
