package com.torxone.app.identity

import java.util.UUID

/**
 * ContactInviteV1 — Canonical contact invitation model.
 *
 * Exchanged out-of-band via QR code:
 * torx://contact/<Base64url-encoded-binary>
 */
data class ContactInviteV1(
    val protocolVersion: Int = 1,
    val inviteId: String = UUID.randomUUID().toString(),
    val identityId: String = "",
    val displayName: String,
    val identitySigningPublicKey: ByteArray,
    val identityEncryptionPublicKey: ByteArray,
    val bootstrapEphemeralPublicKey: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L), // 7 days default
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactInviteV1) return false
        return inviteId == other.inviteId &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = inviteId.hashCode()
}

/**
 * Validation result when parsing/verifying an invite.
 */
sealed class InviteValidationResult {
    data class Valid(val invite: ContactInviteV1, val fingerprint: String) : InviteValidationResult()
    data class Invalid(val error: InviteValidationError) : InviteValidationResult()
}

enum class InviteValidationError {
    UNSUPPORTED_PROTOCOL,
    INVALID_SIGNATURE,
    EXPIRED,
    INVALID_KEY_LENGTH,
    ALREADY_CONSUMED,
    SELF_INVITE,
    MALFORMED,
    OVERSIZED
}
