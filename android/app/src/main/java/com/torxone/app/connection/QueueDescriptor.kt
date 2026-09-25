package com.torxone.app.connection

/**
 * Describes a message queue endpoint.
 *
 * Sender and receiver have DIFFERENT addresses and credentials.
 * For Milestone 1 (Nearby only), relayId is null and addresses are logical/local.
 */
data class QueueDescriptor(
    val generation: Int = 1,
    val sendAddress: String,
    val receiveAddress: String,
    val sendCredential: ByteArray,
    val receiveCredential: ByteArray,
    val relayId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueueDescriptor) return false
        return generation == other.generation &&
                sendAddress == other.sendAddress &&
                receiveAddress == other.receiveAddress &&
                relayId == other.relayId
    }

    override fun hashCode(): Int {
        var result = generation
        result = 31 * result + sendAddress.hashCode()
        result = 31 * result + receiveAddress.hashCode()
        result = 31 * result + (relayId?.hashCode() ?: 0)
        return result
    }
}
