package com.torxone.app.security.session

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Standard Symmetric & Diffie-Hellman Ratchet implementation.
 *
 * Adheres strictly to RFC 5869 (HKDF) and standard HMAC-based KDF chain mechanics:
 * 1. Symmetric KDF chain: derives ephemeral per-message encryption keys and advances chain key.
 * 2. Asymmetric DH step: calculates X25519 shared secret to advance root key and spawn new chains.
 */
object SessionRatchet {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val RATCHET_INFO = "TorX-Ratchet-v1"
    private val lazySodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    private val MESSAGE_KEY_SEED = byteArrayOf(0x01)
    private val CHAIN_KEY_SEED = byteArrayOf(0x02)

    /**
     * Advances the symmetric KDF chain by one step.
     * Computes message key and the next chain key using HMAC-SHA256.
     *
     * @param currentChainKey The active 32-byte chain key.
     * @return Pair of (MessageKey [32 bytes], NextChainKey [32 bytes]).
     */
    fun stepSymmetricChain(currentChainKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(currentChainKey.size == 32) { "Chain key must be exactly 32 bytes" }
        val messageKey = hmacSha256(currentChainKey, MESSAGE_KEY_SEED)
        val nextChainKey = hmacSha256(currentChainKey, CHAIN_KEY_SEED)
        return Pair(messageKey, nextChainKey)
    }

    /**
     * Performs an asymmetric Diffie-Hellman ratchet step.
     * Combines previous root key with X25519 ECDH shared secret to yield:
     * (NextRootKey [32 bytes], NextChainKey [32 bytes]).
     */
    fun stepDhRatchet(
        currentRootKey: ByteArray,
        localEphemeralSec: ByteArray,
        remoteEphemeralPub: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(currentRootKey.size == 32) { "Root key must be 32 bytes" }
        require(localEphemeralSec.size == 32) { "Local ephemeral secret must be 32 bytes" }
        require(remoteEphemeralPub.size == 32) { "Remote ephemeral public must be 32 bytes" }

        val dhSecret = ByteArray(32)
        val success = lazySodium.cryptoScalarMult(dhSecret, localEphemeralSec, remoteEphemeralPub)
        if (!success) {
            throw IllegalStateException("X25519 ECDH calculation failed during ratchet step")
        }

        try {
            // HKDF-Extract + HKDF-Expand to derive 64 bytes: 32 bytes nextRootKey, 32 bytes nextChainKey
            val derived = hkdf(
                salt = currentRootKey,
                ikm = dhSecret,
                info = RATCHET_INFO.toByteArray(Charsets.UTF_8),
                length = 64
            )
            val nextRootKey = ByteArray(32).also { System.arraycopy(derived, 0, it, 0, 32) }
            val nextChainKey = ByteArray(32).also { System.arraycopy(derived, 32, it, 0, 32) }
            return Pair(nextRootKey, nextChainKey)
        } finally {
            Arrays.fill(dhSecret, 0.toByte())
        }
    }

    /**
     * Computes initial shared root key for Initiator (Alice).
     * Combines X25519 ECDH of ephemeral keypair with remote static identity key:
     * DH1 = X25519(localEphemeralSec, remoteIdentityPub)
     * DH2 = X25519(localIdentitySec, remoteIdentityPub)
     */
    fun computeInitiatorRootKey(
        localEphemeralSec: ByteArray,
        remoteIdentityPub: ByteArray,
        localIdentitySec: ByteArray
    ): ByteArray {
        val dhEphemeral = ByteArray(32)
        val dhStatic = ByteArray(32)

        val ok1 = lazySodium.cryptoScalarMult(dhEphemeral, localEphemeralSec, remoteIdentityPub)
        val ok2 = lazySodium.cryptoScalarMult(dhStatic, localIdentitySec, remoteIdentityPub)

        if (!ok1 || !ok2) {
            Arrays.fill(dhEphemeral, 0.toByte())
            Arrays.fill(dhStatic, 0.toByte())
            throw IllegalStateException("Failed to calculate initial ECDH shared secrets")
        }

        val combinedIkm = ByteArray(64)
        System.arraycopy(dhEphemeral, 0, combinedIkm, 0, 32)
        System.arraycopy(dhStatic, 0, combinedIkm, 32, 32)
        Arrays.fill(dhEphemeral, 0.toByte())
        Arrays.fill(dhStatic, 0.toByte())

        try {
            return hkdf(
                salt = "TorX-Session-Init-Salt".toByteArray(Charsets.UTF_8),
                ikm = combinedIkm,
                info = "TorX-Session-Root-Init".toByteArray(Charsets.UTF_8),
                length = 32
            )
        } finally {
            Arrays.fill(combinedIkm, 0.toByte())
        }
    }

    /**
     * Computes initial shared root key for Responder (Bob).
     * Combines X25519 ECDH of local static identity with remote ephemeral ratchet key,
     * and local static identity with remote static identity key:
     * DH1 = X25519(localIdentitySec, remoteEphemeralPub) [matches Alice's DH1]
     * DH2 = X25519(localIdentitySec, remoteIdentityPub)  [matches Alice's DH2]
     */
    fun computeResponderRootKey(
        localIdentitySec: ByteArray,
        remoteEphemeralPub: ByteArray,
        remoteIdentityPub: ByteArray
    ): ByteArray {
        val dhEphemeral = ByteArray(32)
        val dhStatic = ByteArray(32)

        val ok1 = lazySodium.cryptoScalarMult(dhEphemeral, localIdentitySec, remoteEphemeralPub)
        val ok2 = lazySodium.cryptoScalarMult(dhStatic, localIdentitySec, remoteIdentityPub)

        if (!ok1 || !ok2) {
            Arrays.fill(dhEphemeral, 0.toByte())
            Arrays.fill(dhStatic, 0.toByte())
            throw IllegalStateException("Failed to calculate initial ECDH shared secrets")
        }

        val combinedIkm = ByteArray(64)
        System.arraycopy(dhEphemeral, 0, combinedIkm, 0, 32)
        System.arraycopy(dhStatic, 0, combinedIkm, 32, 32)
        Arrays.fill(dhEphemeral, 0.toByte())
        Arrays.fill(dhStatic, 0.toByte())

        try {
            return hkdf(
                salt = "TorX-Session-Init-Salt".toByteArray(Charsets.UTF_8),
                ikm = combinedIkm,
                info = "TorX-Session-Root-Init".toByteArray(Charsets.UTF_8),
                length = 32
            )
        } finally {
            Arrays.fill(combinedIkm, 0.toByte())
        }
    }

    /**
     * Standard RFC 5869 HKDF implementation.
     */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        // 1. Extract: PRK = HMAC-Hash(salt, IKM)
        val prk = hmacSha256(salt, ikm)

        // 2. Expand: OKM = T(1) || T(2) || ...
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var bytesWritten = 0
        var block = 1

        while (bytesWritten < length) {
            val mac = Mac.getInstance(HMAC_ALGORITHM).apply {
                init(SecretKeySpec(prk, HMAC_ALGORITHM))
                update(t)
                update(info)
                update(block.toByte())
            }
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - bytesWritten)
            System.arraycopy(t, 0, okm, bytesWritten, toCopy)
            bytesWritten += toCopy
            block++
        }

        Arrays.fill(prk, 0.toByte())
        return okm
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}
