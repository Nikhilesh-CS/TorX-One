package com.torxone.app.group

import com.torxone.app.data.GroupKeyEntity
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Application-level group encryption. Pairwise MessageRouter encryption remains unchanged. */
object GroupCryptoManager {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    data class EncryptedGroupPayload(val ciphertextBase64: String, val ivBase64: String)

    fun newKeyBase64(): String = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))

    fun encrypt(key: GroupKeyEntity, plaintext: String): EncryptedGroupPayload {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyMaterial(key), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(key.groupId, key.keyVersion))
        }
        return EncryptedGroupPayload(
            Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))),
            Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decrypt(key: GroupKeyEntity, ciphertextBase64: String, ivBase64: String): String {
        val iv = Base64.getDecoder().decode(ivBase64)
        require(iv.size == IV_BYTES) { "Invalid group message IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyMaterial(key), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(key.groupId, key.keyVersion))
        }
        return String(cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64)), Charsets.UTF_8)
    }

    private fun keyMaterial(key: GroupKeyEntity) = SecretKeySpec(Base64.getDecoder().decode(key.aesKeyBase64), "AES")
    private fun aad(groupId: String, version: Int) = "$groupId:$version".toByteArray(Charsets.UTF_8)
}
