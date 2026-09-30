package com.torxone.app.relationship

import com.torxone.app.identity.IdentityCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactBootstrapPayloadTest {
    @Test
    fun `signed initiator onion survives bootstrap encoding and is authenticated`() {
        val signing = IdentityCrypto.generateEd25519KeyPair()
        val onion = "b".repeat(56) + ".onion"
        val encryption = ByteArray(32) { 2 }
        val ephemeral = ByteArray(32) { 3 }
        val signed = ContactBootstrapPayload.serializeForSigning(
            inviteId = "invite",
            initiatorIdentityId = "identity",
            displayName = "Alice",
            signingPub = signing.publicKey,
            encryptionPub = encryption,
            ephemeralPub = ephemeral,
            initiatorTorOnionAddress = onion
        )
        val payload = ContactBootstrapPayload(
            inviteId = "invite",
            initiatorIdentityId = "identity",
            initiatorDisplayName = "Alice",
            initiatorSigningPublicKey = signing.publicKey,
            initiatorEncryptionPublicKey = encryption,
            initiatorEphemeralPublicKey = ephemeral,
            signature = IdentityCrypto.signEd25519(signing.privateKey, signed),
            initiatorTorOnionAddress = onion
        )

        val decoded = ContactBootstrapPayload.fromByteArray(payload.toByteArray())
        val decodedSigned = ContactBootstrapPayload.serializeForSigning(
            decoded.inviteId,
            decoded.initiatorIdentityId,
            decoded.initiatorDisplayName,
            decoded.initiatorSigningPublicKey,
            decoded.initiatorEncryptionPublicKey,
            decoded.initiatorEphemeralPublicKey,
            decoded.initiatorTorOnionAddress
        )

        assertEquals(onion, decoded.initiatorTorOnionAddress)
        assertTrue(IdentityCrypto.verifyEd25519(decoded.initiatorSigningPublicKey, decodedSigned, decoded.signature))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid appended onion is rejected`() {
        val payload = ContactBootstrapPayload(
            "invite", "identity", "Alice", ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(64), "invalid.onion"
        )
        ContactBootstrapPayload.fromByteArray(payload.toByteArray())
    }
}
