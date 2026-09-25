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

    /** Queue authenticator */
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
    val updatedAt: Long = System.currentTimeMillis()
) {
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
