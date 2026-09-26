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
        const val MAGIC: Byte = 0x54 // 'T'
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
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
            throw SecurityException("Invalid wrapped key payload: missing magic byte or truncated payload")
        }
        return try {
            val iv = wrapped.copyOfRange(1, 1 + GCM_IV_LENGTH)
            val ciphertext = wrapped.copyOfRange(1 + GCM_IV_LENGTH, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(masterKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecurityException("Cryptographic unwrapping failed: integrity or authentication tag mismatch", e)
        }
    }
}

/**
 * Android Keystore-backed AES-256-GCM KeyProtector for production storage.
 * Master key is generated and stored securely inside the Android hardware-backed KeyStore.
 */
class AndroidKeystoreKeyProtector(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val fallbackMasterKey: ByteArray? = null
) : KeyProtector {

    companion object {
        const val DEFAULT_KEY_ALIAS = "torx_room_session_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAGIC: Byte = 0x54 // 'T'
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        private val secureRandom = SecureRandom()
    }

    private val secretKey: java.security.Key by lazy {
        getOrCreateKey(keyAlias)
    }

    private fun getOrCreateKey(alias: String): java.security.Key {
        try {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            return keyStore.getKey(alias, null)
                ?: throw IllegalStateException("KeyStore key could not be retrieved for alias: $alias")
        } catch (e: Exception) {
            if (fallbackMasterKey != null && fallbackMasterKey.size == 32) {
                return SecretKeySpec(fallbackMasterKey, "AES")
            }
            throw SecurityException("Failed to access or generate Android KeyStore master key for alias '$alias'", e)
        }
    }

    override fun wrap(secret: ByteArray): ByteArray {
        if (secret.isEmpty()) return secret
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(secret)
        return byteArrayOf(MAGIC) + iv + ciphertext
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.isEmpty()) return wrapped
        if (wrapped.size <= 1 + GCM_IV_LENGTH + 16 || wrapped[0] != MAGIC) {
            throw SecurityException("Invalid wrapped key format: missing magic byte or truncated payload")
        }
        return try {
            val iv = wrapped.copyOfRange(1, 1 + GCM_IV_LENGTH)
            val ciphertext = wrapped.copyOfRange(1 + GCM_IV_LENGTH, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecurityException("Cryptographic unwrapping failed: integrity or authentication tag mismatch", e)
        }
    }
}
