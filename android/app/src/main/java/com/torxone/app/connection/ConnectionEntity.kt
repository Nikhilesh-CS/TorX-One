package com.torxone.app.connection

import java.util.UUID

/**
 * A pairwise connection — replaceable routing generation between two peers.
 *
 * Relationship (long-lived)
 *   └── Connection generation 1 (current routing generation)
 */
data class Connection(
    val connectionId: String = UUID.randomUUID().toString(),
    val relationshipId: String,
    val generation: Int = 1,
    val sendQueueId: String,
    val recvQueueId: String,
    val sendAuth: ByteArray = ByteArray(0),
    val recvAuth: ByteArray = ByteArray(0),
    val state: ConnectionState = ConnectionState.ACTIVE,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Connection) return false
        return connectionId == other.connectionId
    }

    override fun hashCode(): Int = connectionId.hashCode()
}

enum class ConnectionState {
    ESTABLISHING,
    ACTIVE,
    ROTATING,
    CLOSED
}
