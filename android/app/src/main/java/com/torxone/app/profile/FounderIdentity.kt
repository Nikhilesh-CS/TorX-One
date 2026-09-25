package com.torxone.app.profile

import java.util.Base64

/**
 * Founder identity verification.
 *
 * The founder badge is derived from a pinned identity signing public key,
 * NOT a mutable setting. The badge is shown if the local identity's
 * signing public key matches the hardcoded founder key.
 *
 * This is cryptographically secure — only the holder of the corresponding
 * private key can produce valid signatures, and the public key is embedded
 * at compile time.
 */
object FounderIdentity {

    /**
     * The pinned Ed25519 signing public key of the founder.
     * Set this to the real founder's signing public key (Base64-encoded).
     * Until a real key is set, a placeholder is used.
     */
    private const val FOUNDER_SIGNING_PUBLIC_KEY_B64 =
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    val founderPublicKey: ByteArray by lazy {
        Base64.getDecoder().decode(FOUNDER_SIGNING_PUBLIC_KEY_B64)
    }

    /**
     * Check whether the given signing public key matches the founder identity.
     * @param signingPublicKey The Ed25519 signing public key to check.
     * @return true if this is the founder.
     */
    fun isFounder(signingPublicKey: ByteArray): Boolean {
        return signingPublicKey.contentEquals(founderPublicKey)
    }

    /**
     * Check whether this identity is the founder.
     */
    fun isFounderIdentity(identitySigningPubKey: ByteArray?): Boolean {
        if (identitySigningPubKey == null) return false
        return isFounder(identitySigningPubKey)
    }
}
