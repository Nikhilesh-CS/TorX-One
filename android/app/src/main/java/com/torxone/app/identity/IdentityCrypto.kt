package com.torxone.app.identity

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Core cryptographic primitives for TorX identities and authenticated key exchange.
 *
 * Implements:
 * - Ed25519 for signing and verification (RFC 8032)
 * - X25519 for Diffie-Hellman key agreement (RFC 7748)
 * - HKDF-SHA256 for key derivation (RFC 5869)
 * - Fingerprint generation for safety numbers
 */
object IdentityCrypto {

    private val secureRandom = SecureRandom()

    data class KeyPairBytes(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPairBytes) return false
            return publicKey.contentEquals(other.publicKey) &&
                    privateKey.contentEquals(other.privateKey)
        }

        override fun hashCode(): Int = 31 * publicKey.contentHashCode() + privateKey.contentHashCode()
    }

    /**
     * Generate an Ed25519 key pair for signing.
     * Public key: 32 bytes
     * Private key: 32 bytes (seed)
     */
    fun generateEd25519KeyPair(): KeyPairBytes {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()
        val pubParams = pair.public as Ed25519PublicKeyParameters
        val privParams = pair.private as Ed25519PrivateKeyParameters

        return KeyPairBytes(
            publicKey = pubParams.encoded,
            privateKey = privParams.encoded
        )
    }

    /**
     * Generate an X25519 key pair for Diffie-Hellman.
     * Public key: 32 bytes
     * Private key: 32 bytes
     */
    fun generateX25519KeyPair(): KeyPairBytes {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val pair = generator.generateKeyPair()
        val pubParams = pair.public as X25519PublicKeyParameters
        val privParams = pair.private as X25519PrivateKeyParameters

        return KeyPairBytes(
            publicKey = pubParams.encoded,
            privateKey = privParams.encoded
        )
    }

    /**
     * Sign data using an Ed25519 private key.
     * Returns 64-byte signature.
     */
    fun signEd25519(privateKeyBytes: ByteArray, data: ByteArray): ByteArray {
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes" }
        val privParams = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verify an Ed25519 signature.
     */
    fun verifyEd25519(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (publicKeyBytes.size != 32 || signature.size != 64) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val signer = Ed25519Signer()
            signer.init(false, pubParams)
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Perform X25519 Diffie-Hellman key exchange.
     * Returns 32-byte shared secret.
     */
    fun diffieHellmanX25519(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        require(ourPrivateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(theirPublicKey.size == 32) { "X25519 public key must be 32 bytes" }

        val privParams = X25519PrivateKeyParameters(ourPrivateKey, 0)
        val pubParams = X25519PublicKeyParameters(theirPublicKey, 0)

        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    /**
     * HKDF-SHA256 Extract and Expand (RFC 5869).
     */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        outputLength: Int = 32
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        val actualSalt = salt ?: ByteArray(32)
        val params = HKDFParameters(ikm, actualSalt, info)
        hkdf.init(params)

        val out = ByteArray(outputLength)
        hkdf.generateBytes(out, 0, outputLength)
        return out
    }

    /**
     * Compute safety number / fingerprint string formatted in 4-character blocks.
     * Example: "AB12 CD34 EF56 7890 ..."
     */
    fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val hex = digest.joinToString("") { "%02X".format(it) }
        return hex.chunked(4).take(8).joinToString(" ")
    }
}
