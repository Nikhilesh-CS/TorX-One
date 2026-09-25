package com.torxone.app.crypto

import java.util.UUID

/**
 * Key identifier for skipped message keys.
 */
data class SkippedKeyId(
    val ratchetPublicKeyHex: String,
    val counter: Int
)

/**
 * Double Ratchet session state.
 *
 * Holds root key, symmetric chain keys, ephemeral DH ratchet keys, message counters,
 * and skipped keys for out-of-order delivery.
 */
data class SessionState(
    val sessionId: String = UUID.randomUUID().toString(),
    val relationshipId: String,

    /** Current root key (32 bytes) */
    var rootKey: ByteArray,

    /** Local DH ratchet key pair */
    var localRatchetPrivateKey: ByteArray,
    var localRatchetPublicKey: ByteArray,

    /** Remote peer's current DH ratchet public key */
    var remoteRatchetPublicKey: ByteArray,

    /** Symmetric sending chain key (null until first DH step if responder) */
    var sendChainKey: ByteArray? = null,

    /** Symmetric receiving chain key (null until first message received if initiator) */
    var recvChainKey: ByteArray? = null,

    /** Number of messages sent in current sending chain */
    var sendMessageNumber: Int = 0,

    /** Number of messages received in current receiving chain */
    var receiveMessageNumber: Int = 0,

    /** Length of previous sending chain */
    var previousSendCount: Int = 0,

    /** Skipped message keys for out-of-order delivery */
    val skippedKeys: MutableMap<SkippedKeyId, ByteArray> = mutableMapOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionState) return false
        return sessionId == other.sessionId && relationshipId == other.relationshipId
    }

    override fun hashCode(): Int = sessionId.hashCode()

    fun copyState(): SessionState {
        return SessionState(
            sessionId = sessionId,
            relationshipId = relationshipId,
            rootKey = rootKey.copyOf(),
            localRatchetPrivateKey = localRatchetPrivateKey.copyOf(),
            localRatchetPublicKey = localRatchetPublicKey.copyOf(),
            remoteRatchetPublicKey = remoteRatchetPublicKey.copyOf(),
            sendChainKey = sendChainKey?.copyOf(),
            recvChainKey = recvChainKey?.copyOf(),
            sendMessageNumber = sendMessageNumber,
            receiveMessageNumber = receiveMessageNumber,
            previousSendCount = previousSendCount,
            skippedKeys = HashMap(skippedKeys)
        )
    }
}
