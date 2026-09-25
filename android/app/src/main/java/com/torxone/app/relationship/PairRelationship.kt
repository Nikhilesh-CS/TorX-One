package com.torxone.app.relationship

import java.util.UUID

/**
 * PairRelationship — First-class cryptographic relationship between Alice and Bob.
 *
 * This is long-lived and survives connection and queue rotations.
 *
 * Established through authenticated key agreement (3DH/Noise style with invite verification).
 */
data class PairRelationship(
    val relationshipId: String = UUID.randomUUID().toString(),
    val localIdentityId: String,
    val contactId: String,
    val remoteDisplayName: String,
    val remoteSigningPublicKey: ByteArray,
    val remoteEncryptionPublicKey: ByteArray,
    val pairRootSecret: ByteArray,
    val state: RelationshipState = RelationshipState.ACTIVE,
    val generation: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val verifiedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRelationship) return false
        return relationshipId == other.relationshipId
    }

    override fun hashCode(): Int = relationshipId.hashCode()
}

enum class RelationshipState {
    PENDING,
    ACTIVE,
    REVOKED
}

/**
 * Secrets derived from pairRootSecret via HKDF with distinct domain separation info tags.
 */
data class DerivedRelationshipSecrets(
    val relationshipSecret: ByteArray,
    val connectionBootstrapSecret: ByteArray,
    val queueBootstrapSecret: ByteArray,
    val sessionInitializationSecret: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedRelationshipSecrets) return false
        return relationshipSecret.contentEquals(other.relationshipSecret) &&
                sessionInitializationSecret.contentEquals(other.sessionInitializationSecret)
    }

    override fun hashCode(): Int = relationshipSecret.contentHashCode()
}
