package com.torxone.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM AEAD encryption engine for Double Ratchet message keys.
 */
object SessionCipher {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private val secureRandom = SecureRandom()

    /**
     * Encrypts plaintext using AES-256-GCM with associated data (AAD).
     * Output format: IV (12 bytes) || Ciphertext (includes 16-byte auth tag)
     */
    fun encrypt(
        messageKey: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(messageKey.size == 32) { "Message key must be 32 bytes for AES-256" }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    /**
     * Decrypts AES-256-GCM ciphertext verifying authentication tag and AAD.
     * Throws javax.crypto.AEADBadTagException if ciphertext or AAD is modified.
     */
    fun decrypt(
        messageKey: ByteArray,
        data: ByteArray,
        associatedData: ByteArray
    ): ByteArray {
        require(messageKey.size == 32) { "Message key must be 32 bytes for AES-256" }
        require(data.size >= GCM_IV_LENGTH + 16) { "Data too short for AES-GCM" }

        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = data.copyOfRange(GCM_IV_LENGTH, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertext)
    }
}
