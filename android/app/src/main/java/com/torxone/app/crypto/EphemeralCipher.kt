package com.torxone.app.crypto

import com.torxone.app.identity.IdentityCrypto
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent, directional channel for disposable presence events. Never advances the ratchet. */
object EphemeralCipher {
    private val magic = byteArrayOf(0x54, 0x58, 0x45, 0x32)
    private val random = SecureRandom()
    fun isFrame(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(magic)

    private fun cipher(mode: Int, secret: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(secret.size == 32)
        val key = IdentityCrypto.hmacSha256(secret, "torx-ephemeral-aes-gcm-v2".toByteArray())
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic + aad)
        }
    }

    fun encrypt(secret: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(plaintext.size <= 4096)
        val nonce = ByteArray(12).also(random::nextBytes)
        return magic + nonce + cipher(Cipher.ENCRYPT_MODE, secret, nonce, aad).doFinal(plaintext)
    }

    fun decrypt(secret: ByteArray, bytes: ByteArray, aad: ByteArray): ByteArray {
        require(isFrame(bytes) && bytes.size in 32..4128)
        return cipher(Cipher.DECRYPT_MODE, secret, bytes.copyOfRange(4, 16), aad)
            .doFinal(bytes.copyOfRange(16, bytes.size))
    }
}
