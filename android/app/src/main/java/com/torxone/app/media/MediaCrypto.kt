package com.torxone.app.media

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance, authenticated symmetric crypto for media payloads.
 *
 * Algorithm:
 * - Cipher: AES-256-GCM (NoPadding)
 * - Key: 256 bits (32 bytes)
 * - Nonce/IV: 96 bits (12 bytes), cryptographically random per encryption
 * - Auth Tag: 128 bits (16 bytes)
 * - Wire format of encrypted media: [12-byte IV] + [AES-GCM Ciphertext + 16-byte Tag]
 * - Integrity: SHA-256 digest computed over the entire ciphertext
 */
object MediaCrypto {

    private const val AES_KEY_SIZE_BYTES = 32
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    /**
     * Generates a fresh, cryptographically secure 256-bit media key.
     */
    fun generateMediaKey(): ByteArray {
        val key = ByteArray(AES_KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM with a random IV.
     * Returns [12-byte IV] + [Ciphertext + 16-byte Tag].
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "Media key must be 32 bytes (256 bits)" }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Decrypts ciphertext (prefixed with 12-byte IV) using AES-256-GCM.
     * Throws an exception if authentication fails or data is tampered.
     */
    fun decrypt(key: ByteArray, encryptedDataWithIv: ByteArray): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "Media key must be 32 bytes (256 bits)" }
        require(encryptedDataWithIv.size > GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            "Ciphertext too short to contain IV and GCM authentication tag"
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(encryptedDataWithIv, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val ciphertextOffset = GCM_IV_LENGTH_BYTES
        val ciphertextSize = encryptedDataWithIv.size - GCM_IV_LENGTH_BYTES

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(encryptedDataWithIv, ciphertextOffset, ciphertextSize)
    }

    /**
     * Computes the SHA-256 digest of data and returns a lowercase hex string.
     */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Computes the raw 32-byte SHA-256 digest.
     */
    fun sha256Bytes(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * Verifies that the computed SHA-256 matches the expected hex string (constant time comparison).
     */
    fun verifyIntegrity(data: ByteArray, expectedHex: String): Boolean {
        val computedHex = sha256Hex(data)
        return MessageDigest.isEqual(
            computedHex.toByteArray(Charsets.UTF_8),
            expectedHex.lowercase().toByteArray(Charsets.UTF_8)
        )
    }
}
