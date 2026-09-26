package com.torxone.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AtRestKeyProtector — Protects sensitive cryptographic secrets (ratchet root key, DH private keys, chain keys)
 * at rest using AES-256-GCM before writing to SQLite storage (C2).
 */
interface KeyProtector {
    fun wrap(secret: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

class NoOpKeyProtector : KeyProtector {
    override fun wrap(secret: ByteArray): ByteArray = secret
    override fun unwrap(wrapped: ByteArray): ByteArray = wrapped
}

class AesGcmKeyProtector(
    private val masterKey: ByteArray
) : KeyProtector {

    companion object {
        private const val MAGIC: Byte = 0x54 // 'T'
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private val secureRandom = SecureRandom()
    }

    init {
        require(masterKey.size == 32) { "Master key must be exactly 32 bytes for AES-256" }
    }

    override fun wrap(secret: ByteArray): ByteArray {
        if (secret.isEmpty()) return secret
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(masterKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(secret)
        return byteArrayOf(MAGIC) + iv + ciphertext
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.isEmpty()) return wrapped
        if (wrapped.size <= 1 + GCM_IV_LENGTH + 16 || wrapped[0] != MAGIC) {
            // Unwrapped or legacy raw secret
            return wrapped
        }
        return try {
            val iv = wrapped.copyOfRange(1, 1 + GCM_IV_LENGTH)
            val ciphertext = wrapped.copyOfRange(1 + GCM_IV_LENGTH, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(masterKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            wrapped
        }
    }
}
