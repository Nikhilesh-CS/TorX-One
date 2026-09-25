package com.torxone.app.crypto

import com.torxone.app.identity.IdentityCrypto
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ReplayDetectedException(message: String) : SecurityException(message)
class MaxSkipExceededException(message: String) : SecurityException(message)

/**
 * Pure Double Ratchet implementation adhering to the Signal protocol specification.
 *
 * Guarantees:
 * - Forward secrecy: old message keys cannot be recovered even if current state is compromised.
 * - Post-compromise security (break-in recovery): recovers confidentiality after fresh DH ratchet step.
 * - Out-of-order delivery via bounded skipped-message-keys table (MAX_SKIP = 1000).
 * - Replay detection: rejected if message counter is already consumed and no skipped key exists.
 */
object SessionRatchet {

    const val MAX_SKIP = 1000
    private const val INFO_RATCHET_RK = "torx-ratchet-rk-v1"
    private const val INFO_RATCHET_CK = "torx-ratchet-ck-v1"

    /**
     * Root KDF: takes rootKey (salt) and DH shared secret (ikm), outputs (newRootKey, chainKey).
     */
    fun kdfRk(rootKey: ByteArray, dhSharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val newRootKey = IdentityCrypto.hkdf(
            ikm = dhSharedSecret,
            salt = rootKey,
            info = INFO_RATCHET_RK.toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        val chainKey = IdentityCrypto.hkdf(
            ikm = dhSharedSecret,
            salt = rootKey,
            info = INFO_RATCHET_CK.toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        return newRootKey to chainKey
    }

    /**
     * Chain KDF: takes chainKey, outputs (nextChainKey, messageKey).
     */
    fun kdfCk(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))

        // Next chain key: HMAC(chainKey, 0x01)
        val nextChainKey = mac.doFinal(byteArrayOf(0x01))

        // Message key: HMAC(chainKey, 0x02)
        mac.reset()
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val messageKey = mac.doFinal(byteArrayOf(0x02))

        return nextChainKey to messageKey
    }

    /**
     * Encrypts plaintext through the Double Ratchet.
     */
    fun ratchetEncrypt(
        state: SessionState,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): EncryptedSessionMessage {
        // If sendChainKey is null, perform initial DH step
        if (state.sendChainKey == null) {
            val newLocalPair = IdentityCrypto.generateX25519KeyPair()
            val dhSecret = IdentityCrypto.diffieHellmanX25519(
                newLocalPair.privateKey,
                state.remoteRatchetPublicKey
            )
            val (newRoot, sendCk) = kdfRk(state.rootKey, dhSecret)
            state.rootKey = newRoot
            state.sendChainKey = sendCk
            state.localRatchetPrivateKey = newLocalPair.privateKey
            state.localRatchetPublicKey = newLocalPair.publicKey
            state.previousSendCount = state.sendMessageNumber
            state.sendMessageNumber = 0
        }

        val (nextChain, messageKey) = kdfCk(state.sendChainKey!!)
        state.sendChainKey = nextChain

        val header = MessageHeader(
            ratchetPublicKey = state.localRatchetPublicKey.copyOf(),
            previousChainLength = state.previousSendCount,
            messageNumber = state.sendMessageNumber
        )
        state.sendMessageNumber++

        val ciphertext = try {
            SessionCipher.encrypt(messageKey, plaintext, associatedData)
        } finally {
            messageKey.fill(0)
        }

        return EncryptedSessionMessage(header, ciphertext)
    }

    /**
     * Decrypts ciphertext through the Double Ratchet.
     */
    fun ratchetDecrypt(
        state: SessionState,
        message: EncryptedSessionMessage,
        associatedData: ByteArray
    ): ByteArray {
        val pubHex = bytesToHex(message.header.ratchetPublicKey)
        val skippedKeyId = SkippedKeyId(pubHex, message.header.messageNumber)

        // 1. Try skipped keys first (out of order message)
        val skippedMk = state.skippedKeys.remove(skippedKeyId)
        if (skippedMk != null) {
            return try {
                SessionCipher.decrypt(skippedMk, message.ciphertext, associatedData)
            } finally {
                skippedMk.fill(0)
            }
        }

        // 2. If new remote ratchet public key received, advance DH ratchet
        if (!message.header.ratchetPublicKey.contentEquals(state.remoteRatchetPublicKey)) {
            // Skip message keys on current receive chain up to previousChainLength if not first message
            if (state.remoteRatchetPublicKey.isNotEmpty()) {
                skipMessageKeys(state, message.header.previousChainLength)
            }

            // DH step 1: Receive ratchet
            val dhRecv = IdentityCrypto.diffieHellmanX25519(
                state.localRatchetPrivateKey,
                message.header.ratchetPublicKey
            )
            val (newRk1, recvCk) = kdfRk(state.rootKey, dhRecv)
            state.rootKey = newRk1
            state.recvChainKey = recvCk
            state.remoteRatchetPublicKey = message.header.ratchetPublicKey.copyOf()
            state.previousSendCount = state.sendMessageNumber
            state.sendMessageNumber = 0
            state.receiveMessageNumber = 0

            // DH step 2: Send ratchet
            val newLocalPair = IdentityCrypto.generateX25519KeyPair()
            val dhSend = IdentityCrypto.diffieHellmanX25519(
                newLocalPair.privateKey,
                state.remoteRatchetPublicKey
            )
            val (newRk2, sendCk) = kdfRk(state.rootKey, dhSend)
            state.rootKey = newRk2
            state.sendChainKey = sendCk
            state.localRatchetPrivateKey = newLocalPair.privateKey
            state.localRatchetPublicKey = newLocalPair.publicKey
        }

        // 3. Skip message keys up to message.header.messageNumber
        skipMessageKeys(state, message.header.messageNumber)

        val currentRecvCk = state.recvChainKey
            ?: throw IllegalStateException("Receive chain key is null during decryption")

        // 4. Derive message key for current message
        val (nextRecvCk, messageKey) = kdfCk(currentRecvCk)
        state.recvChainKey = nextRecvCk
        state.receiveMessageNumber++

        return try {
            SessionCipher.decrypt(messageKey, message.ciphertext, associatedData)
        } finally {
            messageKey.fill(0)
        }
    }

    private fun skipMessageKeys(state: SessionState, untilNumber: Int) {
        val recvCk = state.recvChainKey ?: return
        if (state.receiveMessageNumber > untilNumber) {
            throw ReplayDetectedException(
                "Message counter $untilNumber already passed (current: ${state.receiveMessageNumber})"
            )
        }

        val gap = untilNumber - state.receiveMessageNumber
        if (gap > MAX_SKIP) {
            throw MaxSkipExceededException("Message gap ($gap) exceeds MAX_SKIP ($MAX_SKIP)")
        }

        var currentCk = recvCk
        val pubHex = bytesToHex(state.remoteRatchetPublicKey)

        while (state.receiveMessageNumber < untilNumber) {
            val (nextCk, skippedMk) = kdfCk(currentCk)
            currentCk = nextCk
            val keyId = SkippedKeyId(pubHex, state.receiveMessageNumber)
            state.skippedKeys[keyId] = skippedMk
            state.receiveMessageNumber++
        }
        state.recvChainKey = currentCk
    }

    /**
     * Initializes session state for Alice and Bob from the shared sessionInitializationSecret.
     */
    fun initializeSession(
        relationshipId: String,
        sessionInitializationSecret: ByteArray,
        isInitiator: Boolean,
        remoteRatchetPublicKey: ByteArray,
        localRatchetPrivateKey: ByteArray,
        localRatchetPublicKey: ByteArray
    ): SessionState {
        val aToBSendChain = IdentityCrypto.hkdf(
            ikm = sessionInitializationSecret,
            info = "torx-ratchet-a2b-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        val bToASendChain = IdentityCrypto.hkdf(
            ikm = sessionInitializationSecret,
            info = "torx-ratchet-b2a-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        val initialRoot = IdentityCrypto.hkdf(
            ikm = sessionInitializationSecret,
            info = "torx-ratchet-root-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )

        return SessionState(
            relationshipId = relationshipId,
            rootKey = initialRoot,
            localRatchetPrivateKey = localRatchetPrivateKey.copyOf(),
            localRatchetPublicKey = localRatchetPublicKey.copyOf(),
            remoteRatchetPublicKey = remoteRatchetPublicKey.copyOf(),
            sendChainKey = if (isInitiator) aToBSendChain else bToASendChain,
            recvChainKey = if (isInitiator) bToASendChain else aToBSendChain,
            sendMessageNumber = 0,
            receiveMessageNumber = 0,
            previousSendCount = 0
        )
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
