package com.torxone.app.identity

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ContactInviteCodecTest {

    private fun createSampleInvite(
        identitySigningKeyPair: IdentityCrypto.KeyPairBytes = IdentityCrypto.generateEd25519KeyPair(),
        identityEncryptionKeyPair: IdentityCrypto.KeyPairBytes = IdentityCrypto.generateX25519KeyPair(),
        bootstrapEphemeralKeyPair: IdentityCrypto.KeyPairBytes = IdentityCrypto.generateX25519KeyPair(),
        displayName: String = "Alice",
        inviteId: String = UUID.randomUUID().toString(),
        identityId: String = "id-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 86400000L,
        protocolVersion: Int = 1
    ): Pair<ContactInviteV1, IdentityCrypto.KeyPairBytes> {
        val signedData = ContactInviteCodec.serializeForSigning(
            protocolVersion = protocolVersion,
            inviteId = inviteId,
            identityId = identityId,
            displayName = displayName,
            signingPublicKey = identitySigningKeyPair.publicKey,
            encryptionPublicKey = identityEncryptionKeyPair.publicKey,
            bootstrapEphemeralPublicKey = bootstrapEphemeralKeyPair.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        val signature = IdentityCrypto.signEd25519(identitySigningKeyPair.privateKey, signedData)

        val invite = ContactInviteV1(
            protocolVersion = protocolVersion,
            inviteId = inviteId,
            identityId = identityId,
            displayName = displayName,
            identitySigningPublicKey = identitySigningKeyPair.publicKey,
            identityEncryptionPublicKey = identityEncryptionKeyPair.publicKey,
            bootstrapEphemeralPublicKey = bootstrapEphemeralKeyPair.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = signature
        )
        return Pair(invite, identitySigningKeyPair)
    }

    @Test
    fun testBinaryEncodingAndDecodingRoundtrip() {
        val (invite, _) = createSampleInvite()

        val encoded = ContactInviteCodec.encodeToBinary(invite)
        assertTrue(encoded.isNotEmpty())

        val decoded = ContactInviteCodec.decodeFromBinary(encoded)
        assertEquals(invite.protocolVersion, decoded.protocolVersion)
        assertEquals(invite.inviteId, decoded.inviteId)
        assertEquals(invite.identityId, decoded.identityId)
        assertEquals(invite.displayName, decoded.displayName)
        assertArrayEquals(invite.identitySigningPublicKey, decoded.identitySigningPublicKey)
        assertArrayEquals(invite.identityEncryptionPublicKey, decoded.identityEncryptionPublicKey)
        assertArrayEquals(invite.bootstrapEphemeralPublicKey, decoded.bootstrapEphemeralPublicKey)
        assertEquals(invite.createdAt, decoded.createdAt)
        assertEquals(invite.expiresAt, decoded.expiresAt)
        assertArrayEquals(invite.signature, decoded.signature)
    }

    @Test
    fun testQrStringEncodingAndDecodingRoundtrip() {
        val (invite, _) = createSampleInvite()

        val qrString = ContactInviteCodec.encodeToQrString(invite)
        assertTrue("QR string must begin with torx://contact/", qrString.startsWith("torx://contact/"))

        val decoded = ContactInviteCodec.decodeFromQrString(qrString)
        assertNotNull("Decoding valid QR string must succeed", decoded)
        assertEquals(invite.inviteId, decoded!!.inviteId)
        assertEquals(invite.displayName, decoded.displayName)
        assertArrayEquals(invite.identitySigningPublicKey, decoded.identitySigningPublicKey)
    }

    @Test
    fun testDecodeInvalidQrStringReturnsNull() {
        assertNull(ContactInviteCodec.decodeFromQrString("https://example.com/invite"))
        assertNull(ContactInviteCodec.decodeFromQrString("torx://wrong-prefix/123"))
        assertNull(ContactInviteCodec.decodeFromQrString("torx://contact/invalid-base64-&&&"))
    }

    @Test
    fun testValidateSuccessWithValidFingerprint() {
        val (invite, _) = createSampleInvite()

        val result = ContactInviteCodec.validate(invite)
        assertTrue("Validation must succeed for authentic invite", result is InviteValidationResult.Valid)

        val validResult = result as InviteValidationResult.Valid
        assertEquals(invite.inviteId, validResult.invite.inviteId)
        val expectedFingerprint = IdentityCrypto.computeFingerprint(invite.identitySigningPublicKey)
        assertEquals(expectedFingerprint, validResult.fingerprint)
    }

    @Test
    fun testValidateExpiredInviteRejected() {
        val pastTime = System.currentTimeMillis() - 10000L
        val (invite, _) = createSampleInvite(createdAt = pastTime - 100000L, expiresAt = pastTime)

        val result = ContactInviteCodec.validate(invite, now = System.currentTimeMillis())
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.EXPIRED, (result as InviteValidationResult.Invalid).error)
    }

    @Test
    fun testValidateTamperedInviteRejectedWithInvalidSignature() {
        val (invite, _) = createSampleInvite(displayName = "Original")
        val tamperedInvite = invite.copy(displayName = "Hacked")

        val result = ContactInviteCodec.validate(tamperedInvite)
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.INVALID_SIGNATURE, (result as InviteValidationResult.Invalid).error)
    }

    @Test
    fun testValidateSelfInviteRejected() {
        val signingKey = IdentityCrypto.generateEd25519KeyPair()
        val (invite, _) = createSampleInvite(identitySigningKeyPair = signingKey)

        val localIdentity = TorXIdentity(
            identityId = "local-id",
            displayName = "Me",
            signingPublicKey = signingKey.publicKey,
            signingPrivateKey = signingKey.privateKey,
            encryptionPublicKey = ByteArray(32),
            encryptionPrivateKey = ByteArray(32)
        )

        val result = ContactInviteCodec.validate(invite, localIdentity = localIdentity)
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.SELF_INVITE, (result as InviteValidationResult.Invalid).error)
    }

    @Test
    fun testValidateAlreadyConsumedInviteRejected() {
        val (invite, _) = createSampleInvite()
        val consumedSet = setOf(invite.inviteId)

        val result = ContactInviteCodec.validate(invite, consumedInviteIds = consumedSet)
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.ALREADY_CONSUMED, (result as InviteValidationResult.Invalid).error)
    }

    @Test
    fun testValidateInvalidKeyLengthRejected() {
        val (invite, _) = createSampleInvite()
        val badKeyInvite = invite.copy(identitySigningPublicKey = ByteArray(16))

        val result = ContactInviteCodec.validate(badKeyInvite)
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.INVALID_KEY_LENGTH, (result as InviteValidationResult.Invalid).error)
    }

    @Test
    fun testValidateUnsupportedProtocolVersionRejected() {
        val (invite, _) = createSampleInvite(protocolVersion = 2)

        val result = ContactInviteCodec.validate(invite)
        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(InviteValidationError.UNSUPPORTED_PROTOCOL, (result as InviteValidationResult.Invalid).error)
    }
}
