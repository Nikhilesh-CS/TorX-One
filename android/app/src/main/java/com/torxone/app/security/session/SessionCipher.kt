package com.torxone.app.security.session

import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance AES-256-GCM cipher for ratcheted session messages.
 * Authenticates message payload and binds session context via Additional Authenticated Data (AAD).
 */
object SessionCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    data class EncryptedResult(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    /**
     * Encrypts plaintext with a 32-byte ephemeral message key.
     * Immediately zeroizes the provided key in memory.
     */
    fun encrypt(
        messageKey: ByteArray,
        plaintext: String,
        aadData: ByteArray
    ): EncryptedResult {
        require(messageKey.size == 32) { "Message key must be 32 bytes for AES-256" }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val keySpec = SecretKeySpec(messageKey, "AES")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
                if (aadData.isNotEmpty()) updateAAD(aadData)
            }
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintextBytes)
            return EncryptedResult(
                ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
                ivBase64 = Base64.getEncoder().encodeToString(iv)
            )
        } finally {
            Arrays.fill(messageKey, 0.toByte())
        }
    }

    /**
     * Decrypts ciphertext with a 32-byte ephemeral message key.
     * Verifies GCM authentication tag and AAD.
     * Immediately zeroizes the provided key in memory.
     */
    fun decrypt(
        messageKey: ByteArray,
        ciphertextBase64: String,
        ivBase64: String,
        aadData: ByteArray
    ): String {
        require(messageKey.size == 32) { "Message key must be 32 bytes for AES-256" }
        val iv = Base64.getDecoder().decode(ivBase64)
        require(iv.size == IV_BYTES) { "Invalid IV size: expected $IV_BYTES, got ${iv.size}" }
        val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
        val keySpec = SecretKeySpec(messageKey, "AES")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
                if (aadData.isNotEmpty()) updateAAD(aadData)
            }
            val decryptedBytes = cipher.doFinal(ciphertext)
            return String(decryptedBytes, Charsets.UTF_8)
        } finally {
            Arrays.fill(messageKey, 0.toByte())
        }
    }

    /**
     * Constructs canonical AAD binding for session messages.
     * Prevents cross-session, re-ordered, or mismatched recipient message injection.
     */
    fun buildAad(
        sessionId: String,
        msgNum: Int,
        senderKey: String,
        recipientKey: String
    ): ByteArray {
        return "$sessionId:$msgNum:$senderKey:$recipientKey".toByteArray(Charsets.UTF_8)
    }
}
