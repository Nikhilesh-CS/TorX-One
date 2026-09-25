package com.torxone.app.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Double Ratchet session state.
 *
 * Properties:
 * - Old message keys are deleted after use
 * - Future messages use fresh keys
 * - Out-of-order messages supported via skipped keys
 * - Replay messages rejected
 * - Ratchet recovers after fresh DH step
 */
data class SessionState(
    /** Root key — feeds the KDF chain */
    val rootKey: ByteArray,

    /** Current sending chain key */
    val sendChainKey: ByteArray,

    /** Current receiving chain key */
    val recvChainKey: ByteArray,

    /** Our current ratchet key pair */
    val localRatchetPrivateKey: ByteArray,
    val localRatchetPublicKey: ByteArray,

    /** Remote's current ratchet public key */
    val remoteRatchetPublicKey: ByteArray,

    /** Number of messages sent in current sending chain */
    val sendCounter: Int = 0,

    /** Number of messages received in current receiving chain */
    val receiveCounter: Int = 0,

    /** Number of messages in previous sending chain (for header) */
    val previousSendCount: Int = 0,

    /** Session generation — incremented on full session reset */
    val sessionGeneration: Int = 1,

    /** Skipped message keys for out-of-order delivery */
    val skippedKeys: Map<SkippedKeyId, ByteArray> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionState) return false
        return rootKey.contentEquals(other.rootKey) &&
                sessionGeneration == other.sessionGeneration
    }

    override fun hashCode(): Int {
        var result = rootKey.contentHashCode()
        result = 31 * result + sessionGeneration
        return result
    }
}

/** Identifies a skipped message key by ratchet public key + counter */
data class SkippedKeyId(
    val ratchetPublicKey: ByteArray,
    val counter: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkippedKeyId) return false
        return ratchetPublicKey.contentEquals(other.ratchetPublicKey) &&
                counter == other.counter
    }

    override fun hashCode(): Int {
        var result = ratchetPublicKey.contentHashCode()
        result = 31 * result + counter
        return result
    }
}

/**
 * Result of encrypting a message through the ratchet.
 * Contains both the ciphertext and the updated session state.
 */
data class EncryptResult(
    val ciphertext: ByteArray,
    val updatedState: SessionState,
    val messageHeader: MessageHeader
)

/**
 * Result of decrypting a message through the ratchet.
 */
data class DecryptResult(
    val plaintext: ByteArray,
    val updatedState: SessionState
)

/**
 * Header sent alongside ciphertext for ratchet synchronization.
 */
data class MessageHeader(
    val ratchetPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int
)

/**
 * Core cryptographic operations using well-reviewed primitives.
 * No exotic crypto — AES-256-GCM for symmetric, HKDF for derivation.
 */
object CryptoEngine {
    private const val AES_GCM_TAG_LENGTH = 128
    private const val AES_GCM_IV_LENGTH = 12
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val secureRandom = SecureRandom()

    /**
     * Encrypt plaintext using AES-256-GCM.
     * Returns IV || ciphertext || auth tag.
     */
    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(AES_GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        aad?.let { cipher.updateAAD(it) }
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    /**
     * Decrypt AES-256-GCM ciphertext.
     * Input format: IV || ciphertext || auth tag.
     */
    fun decryptAesGcm(key: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = data.copyOfRange(0, AES_GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(AES_GCM_IV_LENGTH, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }

    /**
     * HMAC-SHA256 based KDF chain step.
     * Returns (new chain key, message key).
     */
    fun kdfChainStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val newChainKey = hmacSha256(chainKey, byteArrayOf(0x01))
        val messageKey = hmacSha256(chainKey, byteArrayOf(0x02))
        return newChainKey to messageKey
    }

    /**
     * HMAC-SHA256.
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * Generate random bytes for keys, IVs, nonces.
     */
    fun randomBytes(length: Int): ByteArray {
        return ByteArray(length).also { secureRandom.nextBytes(it) }
    }
}
