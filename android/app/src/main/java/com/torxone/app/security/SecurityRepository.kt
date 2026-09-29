package com.torxone.app.security

import com.torxone.app.crypto.KeyProtector

enum class SecretPurpose {
    GENERIC,
    IDENTITY_SIGNING,
    IDENTITY_ENCRYPTION,
    RELATIONSHIP_ROOT,
    QUEUE_SEND_AUTH,
    QUEUE_RECEIVE_AUTH,
    SESSION_STATE,
    PENDING_INVITE,
    DATABASE_PASSPHRASE,
    LOCAL_SECURITY_MATERIAL
}

data class ProtectedSecret(val cryptoFormatVersion: Int, val bytes: ByteArray)

/** Single authority for versioned protection of cryptographic material at rest. */
interface SecurityRepository : KeyProtector {
    val currentCryptoFormatVersion: Int
    fun protect(secret: ByteArray, purpose: SecretPurpose): ProtectedSecret
    fun reveal(protectedSecret: ProtectedSecret, purpose: SecretPurpose): ByteArray
    fun migrate(bytes: ByteArray, fromVersion: Int, purpose: SecretPurpose): ProtectedSecret
}

class VersionedSecurityRepository(
    private val v2Protector: KeyProtector
) : SecurityRepository {
    companion object {
        const val FORMAT_V1_RAW = 1
        const val FORMAT_V2_KEYSTORE_WRAPPED = 2
        const val FORMAT_V3_CONTEXT_BOUND = 3
        private val MAGIC = byteArrayOf(0x54, 0x58, 0x53) // TXS
        private const val HEADER_SIZE = 5
    }

    override val currentCryptoFormatVersion: Int = FORMAT_V3_CONTEXT_BOUND

    override fun protect(secret: ByteArray, purpose: SecretPurpose): ProtectedSecret {
        require(secret.isNotEmpty()) { "Refusing to persist an empty secret" }
        // The purpose byte is inside authenticated ciphertext as well as the clear header.
        val wrapped = v2Protector.wrap(byteArrayOf(purpose.ordinal.toByte()) + secret)
        val envelope = MAGIC + byteArrayOf(FORMAT_V3_CONTEXT_BOUND.toByte(), purpose.ordinal.toByte()) + wrapped
        return ProtectedSecret(FORMAT_V3_CONTEXT_BOUND, envelope)
    }

    override fun reveal(protectedSecret: ProtectedSecret, purpose: SecretPurpose): ByteArray {
        return when (protectedSecret.cryptoFormatVersion) {
            FORMAT_V1_RAW -> protectedSecret.bytes.copyOf()
            FORMAT_V2_KEYSTORE_WRAPPED -> v2Protector.unwrap(protectedSecret.bytes)
            FORMAT_V3_CONTEXT_BOUND -> revealV3(protectedSecret.bytes, purpose)
            else -> throw SecurityException("Unsupported crypto format ${protectedSecret.cryptoFormatVersion}")
        }
    }

    override fun migrate(bytes: ByteArray, fromVersion: Int, purpose: SecretPurpose): ProtectedSecret =
        if (fromVersion == currentCryptoFormatVersion) {
            reveal(ProtectedSecret(fromVersion, bytes), purpose)
            ProtectedSecret(fromVersion, bytes.copyOf())
        } else {
            protect(reveal(ProtectedSecret(fromVersion, bytes), purpose), purpose)
        }

    override fun wrap(secret: ByteArray): ByteArray = protect(secret, SecretPurpose.GENERIC).bytes

    override fun unwrap(wrapped: ByteArray): ByteArray =
        if (isWrapped(wrapped)) reveal(ProtectedSecret(FORMAT_V3_CONTEXT_BOUND, wrapped), SecretPurpose.GENERIC)
        else v2Protector.unwrap(wrapped)

    override fun isWrapped(bytes: ByteArray): Boolean =
        bytes.size > HEADER_SIZE && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
            bytes[3].toInt() == FORMAT_V3_CONTEXT_BOUND

    private fun revealV3(bytes: ByteArray, purpose: SecretPurpose): ByteArray {
        if (!isWrapped(bytes) || bytes.size <= HEADER_SIZE) throw SecurityException("Malformed V3 protected secret")
        val storedPurpose = bytes[4].toInt() and 0xff
        if (storedPurpose != purpose.ordinal) throw SecurityException("Protected secret purpose mismatch")
        val plaintext = v2Protector.unwrap(bytes.copyOfRange(HEADER_SIZE, bytes.size))
        if (plaintext.isEmpty() || (plaintext[0].toInt() and 0xff) != purpose.ordinal) {
            throw SecurityException("Protected secret authenticated purpose mismatch")
        }
        return plaintext.copyOfRange(1, plaintext.size)
    }
}
