package com.torxone.app.media

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    /**
     * Stream-encrypts plaintext from an InputStream into an OutputStream using AES-256-GCM.
     * Writes [12-byte IV] followed by the AES-GCM ciphertext + 16-byte authentication tag.
     * Returns the SHA-256 hex string computed across the ENTIRE encrypted output (IV + ciphertext).
     *
     * Memory overhead is constant (O(1), 64 KB buffer) regardless of file size.
     */
    fun encryptStream(
        key: ByteArray,
        inputStream: InputStream,
        outputStream: OutputStream
    ): String {
        require(key.size == AES_KEY_SIZE_BYTES) { "Media key must be 32 bytes (256 bits)" }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val digest = MessageDigest.getInstance("SHA-256")
        outputStream.write(iv)
        digest.update(iv)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val chunkCipher = cipher.update(buffer, 0, bytesRead)
            if (chunkCipher != null && chunkCipher.isNotEmpty()) {
                outputStream.write(chunkCipher)
                digest.update(chunkCipher)
            }
        }

        val finalBytes = cipher.doFinal()
        if (finalBytes != null && finalBytes.isNotEmpty()) {
            outputStream.write(finalBytes)
            digest.update(finalBytes)
        }
        outputStream.flush()

        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Stream-decrypts ciphertext from an InputStream into an OutputStream using AES-256-GCM.
     * Reads the leading [12-byte IV], initializes cipher, and writes decrypted plaintext.
     * Throws an exception if authentication fails.
     *
     * Memory overhead is constant (O(1), 64 KB buffer) regardless of file size.
     */
    fun decryptStream(
        key: ByteArray,
        inputStream: InputStream,
        outputStream: OutputStream
    ) {
        require(key.size == AES_KEY_SIZE_BYTES) { "Media key must be 32 bytes (256 bits)" }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        var readIv = 0
        while (readIv < GCM_IV_LENGTH_BYTES) {
            val count = inputStream.read(iv, readIv, GCM_IV_LENGTH_BYTES - readIv)
            if (count == -1) throw IOException("Unexpected EOF while reading GCM IV")
            readIv += count
        }

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val chunkPlain = cipher.update(buffer, 0, bytesRead)
            if (chunkPlain != null && chunkPlain.isNotEmpty()) {
                outputStream.write(chunkPlain)
            }
        }

        val finalPlain = cipher.doFinal()
        if (finalPlain != null && finalPlain.isNotEmpty()) {
            outputStream.write(finalPlain)
        }
        outputStream.flush()
    }

    /**
     * Computes the SHA-256 digest of a file in streaming fashion.
     */
    fun sha256FileHex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Verifies the SHA-256 digest of a file against expected hex.
     */
    fun verifyFileIntegrity(file: File, expectedHex: String): Boolean {
        if (!file.exists()) return false
        val computedHex = sha256FileHex(file)
        return MessageDigest.isEqual(
            computedHex.toByteArray(Charsets.UTF_8),
            expectedHex.lowercase().toByteArray(Charsets.UTF_8)
        )
    }
}
