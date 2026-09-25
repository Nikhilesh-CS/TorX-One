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
 * DH ratchet and root key state.
 */
data class RootDhState(
    var rootKey: ByteArray,
    var localRatchetPrivateKey: ByteArray,
    var localRatchetPublicKey: ByteArray,
    var remoteRatchetPublicKey: ByteArray
) {
    fun copyState(): RootDhState = RootDhState(
        rootKey = rootKey.copyOf(),
        localRatchetPrivateKey = localRatchetPrivateKey.copyOf(),
        localRatchetPublicKey = localRatchetPublicKey.copyOf(),
        remoteRatchetPublicKey = remoteRatchetPublicKey.copyOf()
    )
}

/**
 * Symmetric sending chain state.
 */
data class SendState(
    var chainKey: ByteArray? = null,
    var messageNumber: Int = 0,
    var previousChainLength: Int = 0
) {
    fun copyState(): SendState = SendState(
        chainKey = chainKey?.copyOf(),
        messageNumber = messageNumber,
        previousChainLength = previousChainLength
    )
}

/**
 * Symmetric receiving chain state and skipped keys.
 */
data class ReceiveState(
    var chainKey: ByteArray? = null,
    var messageNumber: Int = 0,
    val skippedKeys: MutableMap<SkippedKeyId, ByteArray> = mutableMapOf()
) {
    fun copyState(): ReceiveState = ReceiveState(
        chainKey = chainKey?.copyOf(),
        messageNumber = messageNumber,
        skippedKeys = HashMap(skippedKeys)
    )
}

/**
 * Double Ratchet session state (Phase 2).
 *
 * Holds decoupled RootDhState, SendState, and ReceiveState.
 */
data class SessionState(
    val sessionId: String = UUID.randomUUID().toString(),
    val relationshipId: String,
    val rootDh: RootDhState,
    val send: SendState = SendState(),
    val receive: ReceiveState = ReceiveState()
) {
    /** Secondary constructor for backwards compatibility */
    constructor(
        sessionId: String = UUID.randomUUID().toString(),
        relationshipId: String,
        rootKey: ByteArray,
        localRatchetPrivateKey: ByteArray,
        localRatchetPublicKey: ByteArray,
        remoteRatchetPublicKey: ByteArray,
        sendChainKey: ByteArray? = null,
        recvChainKey: ByteArray? = null,
        sendMessageNumber: Int = 0,
        receiveMessageNumber: Int = 0,
        previousSendCount: Int = 0,
        skippedKeys: MutableMap<SkippedKeyId, ByteArray> = mutableMapOf()
    ) : this(
        sessionId = sessionId,
        relationshipId = relationshipId,
        rootDh = RootDhState(
            rootKey = rootKey,
            localRatchetPrivateKey = localRatchetPrivateKey,
            localRatchetPublicKey = localRatchetPublicKey,
            remoteRatchetPublicKey = remoteRatchetPublicKey
        ),
        send = SendState(
            chainKey = sendChainKey,
            messageNumber = sendMessageNumber,
            previousChainLength = previousSendCount
        ),
        receive = ReceiveState(
            chainKey = recvChainKey,
            messageNumber = receiveMessageNumber,
            skippedKeys = skippedKeys
        )
    )

    // Direct property delegates for seamless backwards compatibility
    var rootKey: ByteArray
        get() = rootDh.rootKey
        set(v) { rootDh.rootKey = v }

    var localRatchetPrivateKey: ByteArray
        get() = rootDh.localRatchetPrivateKey
        set(v) { rootDh.localRatchetPrivateKey = v }

    var localRatchetPublicKey: ByteArray
        get() = rootDh.localRatchetPublicKey
        set(v) { rootDh.localRatchetPublicKey = v }

    var remoteRatchetPublicKey: ByteArray
        get() = rootDh.remoteRatchetPublicKey
        set(v) { rootDh.remoteRatchetPublicKey = v }

    var sendChainKey: ByteArray?
        get() = send.chainKey
        set(v) { send.chainKey = v }

    var recvChainKey: ByteArray?
        get() = receive.chainKey
        set(v) { receive.chainKey = v }

    var sendMessageNumber: Int
        get() = send.messageNumber
        set(v) { send.messageNumber = v }

    var receiveMessageNumber: Int
        get() = receive.messageNumber
        set(v) { receive.messageNumber = v }

    var previousSendCount: Int
        get() = send.previousChainLength
        set(v) { send.previousChainLength = v }

    val skippedKeys: MutableMap<SkippedKeyId, ByteArray>
        get() = receive.skippedKeys

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
            rootDh = rootDh.copyState(),
            send = send.copyState(),
            receive = receive.copyState()
        )
    }
}
