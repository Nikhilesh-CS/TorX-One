package com.torxone.app.identity.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

object BackupCrypto {
    private const val ALGORITHM = "AES"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BIT = 128
    
    private const val ITERATION_COUNT = 100_000
    private const val KEY_LENGTH_BIT = 256
    
    private val secureRandom = SecureRandom()

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH_BIT)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val secretKey = factory.generateSecret(spec)
        spec.clearPassword() // Wipe password from PBEKeySpec
        return SecretKeySpec(secretKey.encoded, ALGORITHM)
    }

    /**
     * Encrypts the payload with AES-256-GCM.
     * Output format: [SALT (16 bytes)] [IV (12 bytes)] [CIPHERTEXT + AUTH TAG]
     */
    fun encryptBackup(payload: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)
        
        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val ciphertext = cipher.doFinal(payload)
        
        val buffer = ByteBuffer.allocate(salt.size + iv.size + ciphertext.size)
        buffer.put(salt)
        buffer.put(iv)
        buffer.put(ciphertext)
        
        return buffer.array()
    }

    /**
     * Decrypts the backup. Throws an exception if authentication fails or data is corrupted.
     */
    fun decryptBackup(encryptedData: ByteArray, password: CharArray): ByteArray {
        require(encryptedData.size > SALT_LENGTH + IV_LENGTH) { "Invalid backup format: data too short" }
        
        val buffer = ByteBuffer.wrap(encryptedData)
        val salt = ByteArray(SALT_LENGTH)
        buffer.get(salt)
        
        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)
        
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        
        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        return cipher.doFinal(ciphertext)
    }
}
