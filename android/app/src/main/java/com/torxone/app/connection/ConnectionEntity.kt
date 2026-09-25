package com.torxone.app.connection

import java.util.UUID

/**
 * A pairwise connection — one generation of network state between two contacts.
 *
 * Relationships are long-lived. Connections are replaceable:
 *   Relationship
 *     ├── Connection generation 1
 *     ├── Connection generation 2
 *     └── Connection generation 3
 *
 * This lets us rotate network state without changing identity.
 */
data class ConnectionEntity(
    val connectionId: String = UUID.randomUUID().toString(),

    /** Parent relationship */
    val relationshipId: String,

    /** Remote contact identifier */
    val remoteContactId: String,

    /** Connection generation — incremented on rotation */
    val generation: Int,

    /** Our queue for sending to remote */
    val sendQueueDescriptor: QueueDescriptor?,

    /** Queue where we receive from remote */
    val receiveQueueDescriptor: QueueDescriptor?,

    /** Hints about how to reach the remote peer */
    val activeTransportHints: List<TransportHint> = emptyList(),

    /** Connection state */
    val state: ConnectionState = ConnectionState.ESTABLISHING,

    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
)

/**
 * Describes a message queue endpoint.
 *
 * Sender and receiver have DIFFERENT addresses and credentials.
 * The relay maps senderAddress → mailbox → receiverAddress internally,
 * but network-visible operations never expose identical identifiers.
 */
data class QueueDescriptor(
    /** Which relay hosts this queue */
    val relayId: String?,

    /** Address the sender uses to enqueue */
    val senderAddress: String,

    /** Address the receiver uses to dequeue */
    val receiverAddress: String,

    /** Credential proving sender authority */
    val senderCredential: ByteArray,

    /** Credential proving receiver authority */
    val receiverCredential: ByteArray,

    /** Queue generation — for rotation tracking */
    val queueGeneration: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueueDescriptor) return false
        return senderAddress == other.senderAddress &&
                receiverAddress == other.receiverAddress &&
                queueGeneration == other.queueGeneration
    }

    override fun hashCode(): Int {
        var result = senderAddress.hashCode()
        result = 31 * result + receiverAddress.hashCode()
        result = 31 * result + queueGeneration
        return result
    }
}

/**
 * Hint about how to reach a peer via a specific transport.
 */
data class TransportHint(
    val type: TransportType,
    val address: String,
    val generation: Int,
    val lastVerified: Long = 0L
)

enum class TransportType {
    NEARBY,
    TOR,
    RELAY,
    WIFI_DIRECT
}

enum class ConnectionState {
    ESTABLISHING,
    ACTIVE,
    ROTATING,
    SUSPENDED,
    CLOSED
}
