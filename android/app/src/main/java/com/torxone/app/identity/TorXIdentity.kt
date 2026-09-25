package com.torxone.app.identity

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.UUID

/**
 * TorX Identity — the user's long-lived cryptographic identity.
 *
 * This is NOT a routing address. It is purely for:
 * - proving identity during contact setup
 * - signing identity changes
 * - verifying safety numbers
 * - authenticating pairwise bootstrap
 */
data class TorXIdentity(
    /** Locally unique identifier for this identity */
    val identityId: String = UUID.randomUUID().toString(),

    /** Ed25519 signing key pair — used for identity proofs and signatures */
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,

    /** X25519 encryption key pair — used for key agreements */
    val encryptionPublicKey: ByteArray,
    val encryptionPrivateKey: ByteArray,

    /** User-facing display name */
    val displayName: String,

    /** Profile metadata version, incremented on changes */
    val profileVersion: Int = 1,

    /** Creation timestamp */
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorXIdentity) return false
        return identityId == other.identityId &&
                signingPublicKey.contentEquals(other.signingPublicKey)
    }

    override fun hashCode(): Int {
        var result = identityId.hashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
}

/**
 * Contact invite shared via QR code.
 * Contains everything needed to bootstrap an authenticated relationship.
 */
data class TorXContactInvite(
    val version: Int = 1,
    val inviteId: String = UUID.randomUUID().toString(),

    /** Inviter's identity signing public key */
    val signingPublicKey: ByteArray,

    /** Inviter's identity encryption public key */
    val encryptionPublicKey: ByteArray,

    /** Ephemeral bootstrap public key for initial key agreement */
    val ephemeralBootstrapKey: ByteArray,

    /** Optional relay descriptor for initial contact bootstrap */
    val bootstrapRelayDescriptor: String? = null,

    /** Invite expiry (Unix millis) */
    val expiresAt: Long,

    /** Signature over all invite fields using the signing key */
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorXContactInvite) return false
        return inviteId == other.inviteId
    }

    override fun hashCode(): Int = inviteId.hashCode()
}

/**
 * The result of two users completing the contact exchange.
 * This is long-lived — it survives connection rotations.
 */
data class PairRelationship(
    val relationshipId: String = UUID.randomUUID().toString(),

    /** Local identity reference */
    val localIdentityId: String,

    /** Remote contact's signing public key */
    val remoteSigningPublicKey: ByteArray,

    /** Remote contact's encryption public key */
    val remoteEncryptionPublicKey: ByteArray,

    /** Shared pair secret — root for all connection bootstrap. Never sent to relay. */
    val pairSecret: ByteArray,

    /** Display name for this contact */
    val remoteDisplayName: String,

    /** Verification state */
    val verificationState: VerificationState = VerificationState.UNVERIFIED,

    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRelationship) return false
        return relationshipId == other.relationshipId
    }

    override fun hashCode(): Int = relationshipId.hashCode()
}

enum class VerificationState {
    UNVERIFIED,
    VERIFIED,
    IDENTITY_CHANGED
}
