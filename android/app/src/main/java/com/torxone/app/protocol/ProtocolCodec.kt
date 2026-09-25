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
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(SECURE_MAGIC)
        dos.writeShort(envelope.protocolVersion)
        dos.writeUTF(envelope.logicalMessageId.take(ProtocolLimits.MAX_ID_LENGTH))
        dos.writeUTF(envelope.conversationId.take(ProtocolLimits.MAX_ID_LENGTH))
        dos.writeUTF(envelope.senderIdentity.take(ProtocolLimits.MAX_ID_LENGTH))
        dos.writeUTF(envelope.recipientBinding.take(ProtocolLimits.MAX_ID_LENGTH))
        dos.writeUTF(envelope.messageType.name)
        dos.writeLong(envelope.timestamp)
        dos.writeUTF(envelope.replyToMessageId ?: "")
        dos.writeInt(envelope.payload.size)
        dos.write(envelope.payload)
        dos.flush()

        return baos.toByteArray()
    }

    fun decodeSecureEnvelope(bytes: ByteArray): SecureEnvelope {
        require(bytes.size <= ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES + 1024) { "Payload exceeds maximum limit" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == SECURE_MAGIC) { "Invalid secure envelope magic header" }

        val version = dis.readShort().toInt()
        val messageId = dis.readUTF()
        val conversationId = dis.readUTF()
        val senderIdentity = dis.readUTF()
        val recipientBinding = dis.readUTF()
        val typeStr = dis.readUTF()
        val messageType = try {
            MessageType.valueOf(typeStr)
        } catch (_: Exception) {
            throw IllegalArgumentException("Unsupported message type: $typeStr")
        }
        val timestamp = dis.readLong()
        val replyToRaw = dis.readUTF()
        val replyTo = if (replyToRaw.isEmpty()) null else replyToRaw

        val payloadSize = dis.readInt()
        require(payloadSize in 0..ProtocolLimits.MAX_SECURE_PAYLOAD_BYTES) { "Invalid payload size: $payloadSize" }
        val payload = ByteArray(payloadSize)
        dis.readFully(payload)

        return SecureEnvelope(
            protocolVersion = version,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = senderIdentity,
            recipientBinding = recipientBinding,
            messageType = messageType,
            timestamp = timestamp,
            payload = payload,
            replyToMessageId = replyTo
        )
    }

    fun encodeTransportEnvelope(envelope: OpaqueTransportEnvelope): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(TRANSPORT_MAGIC)
        dos.writeShort(envelope.version)
        dos.writeUTF(envelope.envelopeId.take(ProtocolLimits.MAX_ID_LENGTH))
        dos.writeUTF(envelope.queueAddress.take(ProtocolLimits.MAX_ID_LENGTH))

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
        val envelopeId = dis.readUTF()
        val queueAddress = dis.readUTF()

        val authLen = dis.readShort().toInt()
        val auth = ByteArray(authLen)
        dis.readFully(auth)

        val cipherLen = dis.readInt()
        require(cipherLen in 0..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) { "Invalid ciphertext length: $cipherLen" }
        val ciphertext = ByteArray(cipherLen)
        dis.readFully(ciphertext)

        return OpaqueTransportEnvelope(
            version = version,
            envelopeId = envelopeId,
            queueAddress = queueAddress,
            opaqueCiphertext = ciphertext,
            queueAuthenticator = auth
        )
    }
}
