package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.EncryptedSessionMessage
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.ProcessedEnvelopeDao
import com.torxone.app.data.entity.ProcessedEnvelopeEntity
import com.torxone.app.protocol.*
import com.torxone.app.transport.TransportType
import java.util.UUID

/**
 * 12-Stage Incoming Dispatcher pipeline.
 *
 * Each stage is independent and strictly verified:
 * 1. Validate transport frame length
 * 2. Parse opaque transport envelope
 * 3. Resolve queue and connection
 * 4. Authenticate outer capability
 * 5. Dedupe transport envelope (re-ACKs duplicates)
 * 6. Crypto decrypt via Double Ratchet
 * 7. Parse SecureEnvelope
 * 8. Validate secure envelope bindings and timestamp skew
 * 9. Dispatch to feature handler
 * 10. Persist message state
 * 11. Commit receive state to deduplication table
 * 12. Send secure authenticated ACK back to sender
 */
class IncomingDispatcher(
    private val connectionManager: ConnectionManager,
    private val sessionCrypto: SessionCrypto,
    private val processedEnvelopeDao: ProcessedEnvelopeDao,
    private val chatReceiver: ChatReceiver,
    private val deliveryReceiptHandler: DeliveryReceiptHandler,
    private val agent: TorXAgent,
    private val localIdentityIdProvider: () -> String?
) {
    companion object {
        private const val TAG = "IncomingDispatcher"
    }

    suspend fun dispatch(rawBytes: ByteArray, transportType: TransportType): Boolean {
        // Stage 1: Validate transport frame length
        if (rawBytes.size > ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) {
            Log.e(TAG, "[STAGE 1 FAIL] Frame size ${rawBytes.size} exceeds maximum ${ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES}")
            return false
        }

        // Stage 2: Parse opaque transport envelope
        val opaqueEnvelope = try {
            ProtocolCodec.decodeTransportEnvelope(rawBytes)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 2 FAIL] Malformed transport envelope: ${e.message}")
            return false
        }

        val envShort = opaqueEnvelope.envelopeId.take(8)
        Log.d(TAG, "[RX] env=$envShort arrived via $transportType on queue ${opaqueEnvelope.queueAddress.take(8)}")

        // Stage 3: Resolve queue / connection
        val connection = connectionManager.getConnectionByRecvQueue(opaqueEnvelope.queueAddress)
        if (connection == null) {
            Log.w(TAG, "[STAGE 3 FAIL] No connection found for queue ${opaqueEnvelope.queueAddress}")
            return false
        }

        // Stage 4: Authenticate outer capability (if recvAuth present)
        if (connection.recvAuth.isNotEmpty() && !connection.recvAuth.contentEquals(opaqueEnvelope.queueAuthenticator)) {
            Log.e(TAG, "[STAGE 4 FAIL] Outer queue authenticator verification failed")
            return false
        }

        // Stage 5: Dedupe transport envelope
        if (processedEnvelopeDao.isProcessed(opaqueEnvelope.envelopeId)) {
            Log.w(TAG, "[STAGE 5] Duplicate envelope $envShort already processed — re-sending ACK")
            // Re-send ACK if this is a known connection to resolve lost-ACK retries
            sendAck(connection, opaqueEnvelope.envelopeId, opaqueEnvelope.envelopeId)
            return true
        }

        // Stage 6: Double Ratchet decrypt
        val decryptedBytes = try {
            val encryptedMsg = EncryptedSessionMessage.deserialize(opaqueEnvelope.opaqueCiphertext)
            val aad = "torx-aad-v1:${connection.generation}:${opaqueEnvelope.queueAddress}".toByteArray(Charsets.UTF_8)
            sessionCrypto.decrypt(connection.relationshipId, encryptedMsg, aad)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 6 FAIL] Decryption failed for env=$envShort: ${e.message}")
            return false
        }

        // Stage 7: Parse SecureEnvelope
        val secureEnvelope = try {
            ProtocolCodec.decodeSecureEnvelope(decryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 7 FAIL] SecureEnvelope decode failed: ${e.message}")
            return false
        }

        val msgShort = secureEnvelope.logicalMessageId.take(8)
        Log.i(TAG, "[STAGE 7] Decrypted msg=$msgShort type=${secureEnvelope.messageType}")

        // Stage 8: Validate secure envelope
        val now = System.currentTimeMillis()
        val skew = Math.abs(now - secureEnvelope.timestamp)
        if (skew > ProtocolLimits.MAX_TIMESTAMP_SKEW_MS) {
            Log.e(TAG, "[STAGE 8 FAIL] Timestamp skew $skew exceeds allowed ${ProtocolLimits.MAX_TIMESTAMP_SKEW_MS}")
            return false
        }

        val localId = localIdentityIdProvider()
        if (localId != null && secureEnvelope.recipientBinding.isNotEmpty() && secureEnvelope.recipientBinding != localId) {
            Log.e(TAG, "[STAGE 8 FAIL] Recipient binding mismatch: expected $localId, got ${secureEnvelope.recipientBinding}")
            return false
        }

        // Stage 9 & 10: Dispatch to feature handler & persist
        when (secureEnvelope.messageType) {
            MessageType.TEXT -> {
                chatReceiver.receiveTextMessage(connection, secureEnvelope)
            }
            MessageType.DELIVERY_ACK -> {
                deliveryReceiptHandler.handleDeliveryAck(secureEnvelope)
            }
            MessageType.READ_RECEIPT -> {
                deliveryReceiptHandler.handleReadReceipt(secureEnvelope)
            }
            else -> {
                Log.w(TAG, "[STAGE 9] Unsupported message type ${secureEnvelope.messageType} rejected")
                return false
            }
        }

        // Stage 11: Commit receive state to deduplication table
        processedEnvelopeDao.insert(
            ProcessedEnvelopeEntity(
                envelopeId = opaqueEnvelope.envelopeId,
                logicalMessageId = secureEnvelope.logicalMessageId,
                processedAt = now
            )
        )

        // Stage 12: If TEXT, send secure authenticated ACK
        if (secureEnvelope.messageType == MessageType.TEXT) {
            sendAck(connection, secureEnvelope.logicalMessageId, opaqueEnvelope.envelopeId, secureEnvelope.senderIdentity)
        }

        return true
    }

    private suspend fun sendAck(
        connection: Connection,
        originalMessageId: String,
        originalEnvelopeId: String,
        recipientBinding: String = ""
    ) {
        try {
            val ack = DeliveryAck(
                originalMessageId = originalMessageId,
                originalEnvelopeId = originalEnvelopeId,
                receivedAt = System.currentTimeMillis()
            )
            val ackEnvelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = "",
                senderIdentity = localIdentityIdProvider() ?: "",
                recipientBinding = recipientBinding,
                messageType = MessageType.DELIVERY_ACK,
                payload = ack.toByteArray()
            )
            val ackBytes = ProtocolCodec.encodeSecureEnvelope(ackEnvelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encryptedAck = sessionCrypto.encrypt(connection.relationshipId, ackBytes, aad)
            val opaqueAck = encryptedAck.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = ackEnvelope.logicalMessageId,
                conversationId = "",
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = opaqueAck,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED
            )

            Log.d(TAG, "[ACK] Enqueueing ACK for msg=${originalMessageId.take(8)}")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK: ${e.message}")
        }
    }
}
