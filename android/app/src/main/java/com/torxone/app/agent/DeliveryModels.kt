package com.torxone.app.agent

import java.util.UUID

/**
 * Message delivery states — the single source of truth for message lifecycle.
 *
 * User sees only: clock, ✓, ✓✓, colored ✓✓, !
 * Internal states drive retry logic and diagnostics.
 */
enum class DeliveryStatus {
    /** Message created in UI, not yet encrypted */
    CREATED,

    /** Encrypted and persisted atomically with ratchet state */
    ENCRYPTED,

    /** In the durable outbox, ready for transport */
    QUEUED,

    /** Currently being transmitted */
    TRANSMITTING,

    /** Transport layer accepted the data */
    TRANSPORT_ACCEPTED,

    /** Recipient's device received the ciphertext */
    DEVICE_RECEIVED,

    /** Recipient decrypted and persisted — ACK received */
    DELIVERED,

    /** Recipient viewed the conversation — READ received */
    READ,

    /** Waiting before next retry attempt */
    RETRY_WAIT,

    /** Permanently failed after max retries */
    FAILED,

    /** Message expired before delivery */
    EXPIRED
}

/**
 * Priority levels for outbox items (Phase 27).
 */
object DeliveryPriority {
    const val HIGH = 20    // Critical receipts, ACKs, call signaling
    const val NORMAL = 10  // Chat messages
    const val LOW = 0      // Best-effort signals
}

/**
 * A durable delivery item in the TorXAgent outbox.
 *
 * The outbox survives app restarts. TorXAgent is the ONLY
 * component that processes outbox items and drives transport.
 */
data class DeliveryItem(
    /** Unique delivery attempt ID */
    val deliveryId: String = UUID.randomUUID().toString(),

    /** The logical message this delivery belongs to */
    val logicalMessageId: String,

    /** Conversation context */
    val conversationId: String,

    /** Connection to use for delivery */
    val connectionId: String,

    /** Queue address to send to */
    val queueAddress: String,

    /** The opaque encrypted ciphertext */
    val ciphertext: ByteArray,

    /**
     * Shared secret key (`sendAuth` capability) used by TorXAgent to compute the
     * HMAC-SHA256 queue authenticator for the transport envelope.
     * Stored as `queueAuthenticator` for schema and backward compatibility.
     */
    val queueAuthenticator: ByteArray,

    /** Current delivery status */
    val status: DeliveryStatus = DeliveryStatus.QUEUED,

    /** Priority for outbox scheduling (Phase 27) */
    val priority: Int = DeliveryPriority.NORMAL,

    /** Number of delivery attempts */
    val attemptCount: Int = 0,

    /** When to attempt next delivery (for retry backoff) */
    val nextAttemptAt: Long = 0L,

    /** Timestamp when the delivery was created */
    val createdAt: Long = System.currentTimeMillis(),

    /** Last status change timestamp */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Whether this item expects an end-to-end delivery ACK from peer */
    val expectsAck: Boolean = true
) {
    /**
     * Cryptographic semantic accessor: this field holds the shared secret (`sendAuth`)
     * used to compute the HMAC, NOT the derived HMAC itself.
     */
    val queueAuthSecret: ByteArray get() = queueAuthenticator

    companion object {
        /**
         * Factory function allowing explicit instantiation using the cryptographic name queueAuthSecret.
         */
        fun createWithSecret(
            deliveryId: String = UUID.randomUUID().toString(),
            logicalMessageId: String,
            conversationId: String,
            connectionId: String,
            queueAddress: String,
            ciphertext: ByteArray,
            queueAuthSecret: ByteArray,
            status: DeliveryStatus = DeliveryStatus.QUEUED,
            priority: Int = DeliveryPriority.NORMAL,
            attemptCount: Int = 0,
            nextAttemptAt: Long = 0L,
            createdAt: Long = System.currentTimeMillis(),
            updatedAt: Long = System.currentTimeMillis(),
            expectsAck: Boolean = true
        ): DeliveryItem = DeliveryItem(
            deliveryId = deliveryId,
            logicalMessageId = logicalMessageId,
            conversationId = conversationId,
            connectionId = connectionId,
            queueAddress = queueAddress,
            ciphertext = ciphertext,
            queueAuthenticator = queueAuthSecret,
            status = status,
            priority = priority,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            expectsAck = expectsAck
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveryItem) return false
        return deliveryId == other.deliveryId
    }

    override fun hashCode(): Int = deliveryId.hashCode()
}

/**
 * Record of a processed incoming envelope — for deduplication.
 * Must survive app restart to prevent duplicate messages.
 */
data class ProcessedEnvelope(
    /** Transport-level envelope ID */
    val envelopeId: String,

    /** Logical message ID from the inner SecureEnvelope */
    val logicalMessageId: String,

    /** When this envelope was processed */
    val processedAt: Long = System.currentTimeMillis()
)
