package com.torxone.app.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Deterministic serializer, parser, and validator for ContactInviteV1.
 *
 * Enforces canonical binary representation so that signatures are deterministic
 * and independent of JSON key ordering.
 */
object ContactInviteCodec {

    private const val URI_PREFIX = "torx://contact/"
    private const val MAGIC_HEADER = 0x54584931 // "TXI1" (TorX Invite 1)
    private const val MAX_INVITE_BYTES = 4096
    private const val MAX_DISPLAY_NAME_LENGTH = 128

    /**
     * Produces deterministic bytes for signing or verification.
     */
    fun serializeForSigning(
        protocolVersion: Int,
        inviteId: String,
        displayName: String,
        signingPublicKey: ByteArray,
        encryptionPublicKey: ByteArray,
        bootstrapEphemeralPublicKey: ByteArray,
        createdAt: Long,
        expiresAt: Long
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(MAGIC_HEADER)
        dos.writeShort(protocolVersion)
        dos.writeUTF(inviteId)
        dos.writeUTF(displayName.take(MAX_DISPLAY_NAME_LENGTH))
        
        dos.writeShort(signingPublicKey.size)
        dos.write(signingPublicKey)

        dos.writeShort(encryptionPublicKey.size)
        dos.write(encryptionPublicKey)

        dos.writeShort(bootstrapEphemeralPublicKey.size)
        dos.write(bootstrapEphemeralPublicKey)

        dos.writeLong(createdAt)
        dos.writeLong(expiresAt)
        dos.flush()

        return baos.toByteArray()
    }

    /**
     * Encodes a full ContactInviteV1 (including signature) to canonical binary.
     */
    fun encodeToBinary(invite: ContactInviteV1): ByteArray {
        val signedData = serializeForSigning(
            protocolVersion = invite.protocolVersion,
            inviteId = invite.inviteId,
            displayName = invite.displayName,
            signingPublicKey = invite.identitySigningPublicKey,
            encryptionPublicKey = invite.identityEncryptionPublicKey,
            bootstrapEphemeralPublicKey = invite.bootstrapEphemeralPublicKey,
            createdAt = invite.createdAt,
            expiresAt = invite.expiresAt
        )

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(signedData)
        dos.writeShort(invite.signature.size)
        dos.write(invite.signature)
        dos.flush()

        return baos.toByteArray()
    }

    /**
     * Decodes canonical binary into ContactInviteV1.
     */
    fun decodeFromBinary(bytes: ByteArray): ContactInviteV1 {
        if (bytes.size > MAX_INVITE_BYTES) {
            throw IllegalArgumentException("Invite payload exceeds maximum size")
        }

        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        if (magic != MAGIC_HEADER) {
            throw IllegalArgumentException("Invalid magic header")
        }

        val protocolVersion = dis.readShort().toInt()
        val inviteId = dis.readUTF()
        val displayName = dis.readUTF()

        val signKeyLen = dis.readShort().toInt()
        val signingKey = ByteArray(signKeyLen)
        dis.readFully(signingKey)

        val encKeyLen = dis.readShort().toInt()
        val encryptionKey = ByteArray(encKeyLen)
        dis.readFully(encryptionKey)

        val ephKeyLen = dis.readShort().toInt()
        val ephemeralKey = ByteArray(ephKeyLen)
        dis.readFully(ephemeralKey)

        val createdAt = dis.readLong()
        val expiresAt = dis.readLong()

        val sigLen = dis.readShort().toInt()
        val signature = ByteArray(sigLen)
        dis.readFully(signature)

        return ContactInviteV1(
            protocolVersion = protocolVersion,
            inviteId = inviteId,
            displayName = displayName,
            identitySigningPublicKey = signingKey,
            identityEncryptionPublicKey = encryptionKey,
            bootstrapEphemeralPublicKey = ephemeralKey,
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = signature
        )
    }

    /**
     * Creates a QR string: "torx://contact/<base64url>"
     */
    fun encodeToQrString(invite: ContactInviteV1): String {
        val binary = encodeToBinary(invite)
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(binary)
        return "$URI_PREFIX$base64"
    }

    /**
     * Parses a QR string into a ContactInviteV1.
     */
    fun decodeFromQrString(qrString: String): ContactInviteV1? {
        if (!qrString.startsWith(URI_PREFIX)) return null
        val base64Part = qrString.removePrefix(URI_PREFIX)
        return try {
            val binary = Base64.getUrlDecoder().decode(base64Part)
            decodeFromBinary(binary)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Complete validation pipeline:
     * 1. Protocol version supported?
     * 2. Key lengths valid?
     * 3. Expiry valid?
     * 4. Signature valid?
     * 5. Self-contact?
     * 6. Already consumed?
     */
    fun validate(
        invite: ContactInviteV1,
        localIdentity: TorXIdentity? = null,
        consumedInviteIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis()
    ): InviteValidationResult {
        if (invite.protocolVersion != 1) {
            return InviteValidationResult.Invalid(InviteValidationError.UNSUPPORTED_PROTOCOL)
        }

        if (invite.identitySigningPublicKey.size != 32 ||
            invite.identityEncryptionPublicKey.size != 32 ||
            invite.bootstrapEphemeralPublicKey.size != 32 ||
            invite.signature.size != 64) {
            return InviteValidationResult.Invalid(InviteValidationError.INVALID_KEY_LENGTH)
        }

        if (invite.expiresAt < now) {
            return InviteValidationResult.Invalid(InviteValidationError.EXPIRED)
        }

        if (consumedInviteIds.contains(invite.inviteId)) {
            return InviteValidationResult.Invalid(InviteValidationError.ALREADY_CONSUMED)
        }

        if (localIdentity != null && invite.identitySigningPublicKey.contentEquals(localIdentity.signingPublicKey)) {
            return InviteValidationResult.Invalid(InviteValidationError.SELF_INVITE)
        }

        // Verify signature over canonical bytes
        val signedData = serializeForSigning(
            protocolVersion = invite.protocolVersion,
            inviteId = invite.inviteId,
            displayName = invite.displayName,
            signingPublicKey = invite.identitySigningPublicKey,
            encryptionPublicKey = invite.identityEncryptionPublicKey,
            bootstrapEphemeralPublicKey = invite.bootstrapEphemeralPublicKey,
            createdAt = invite.createdAt,
            expiresAt = invite.expiresAt
        )

        val isValidSignature = IdentityCrypto.verifyEd25519(
            publicKeyBytes = invite.identitySigningPublicKey,
            data = signedData,
            signature = invite.signature
        )

        if (!isValidSignature) {
            return InviteValidationResult.Invalid(InviteValidationError.INVALID_SIGNATURE)
        }

        val fingerprint = IdentityCrypto.computeFingerprint(invite.identitySigningPublicKey)
        return InviteValidationResult.Valid(invite, fingerprint)
    }
}
