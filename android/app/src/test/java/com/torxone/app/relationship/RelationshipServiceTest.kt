package com.torxone.app.relationship

import com.torxone.app.identity.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RelationshipServiceTest {

    private fun generateIdentity(displayName: String): TorXIdentity {
        val signKeyPair = IdentityCrypto.generateEd25519KeyPair()
        val encKeyPair = IdentityCrypto.generateX25519KeyPair()
        val fingerprint = IdentityCrypto.computeFingerprint(signKeyPair.publicKey)
        return TorXIdentity(
            identityId = "id-${UUID.randomUUID()}",
            displayName = displayName,
            signingPublicKey = signKeyPair.publicKey,
            signingPrivateKey = signKeyPair.privateKey,
            encryptionPublicKey = encKeyPair.publicKey,
            encryptionPrivateKey = encKeyPair.privateKey
        )
    }

    @Test
    fun testThreeWayDiffieHellmanHandshakeAndSecretDerivation() {
        // 1. Setup Alice and Bob identities
        val alice = generateIdentity("Alice")
        val bob = generateIdentity("Bob")

        // 2. Bob creates an invite with fresh ephemeral key
        val bobEphemeral = IdentityCrypto.generateX25519KeyPair()
        val inviteId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + 86400000L

        val signedData = ContactInviteCodec.serializeForSigning(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = bob.identityId,
            displayName = bob.displayName,
            signingPublicKey = bob.signingPublicKey,
            encryptionPublicKey = bob.encryptionPublicKey,
            bootstrapEphemeralPublicKey = bobEphemeral.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        val signature = IdentityCrypto.signEd25519(bob.signingPrivateKey, signedData)

        val bobInvite = ContactInviteV1(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = bob.identityId,
            displayName = bob.displayName,
            identitySigningPublicKey = bob.signingPublicKey,
            identityEncryptionPublicKey = bob.encryptionPublicKey,
            bootstrapEphemeralPublicKey = bobEphemeral.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = signature
        )

        // 3. Alice scans Bob's invite and establishes initiator relationship
        val aliceResult = RelationshipService.establishFromInvite(
            localIdentity = alice,
            invite = bobInvite,
            contactId = "bob-contact-id"
        )

        // 4. Bob consumes Alice's response and establishes responder relationship
        val bobResult = RelationshipService.establishResponder(
            localIdentity = bob,
            ephemeralBootstrapPrivateKey = bobEphemeral.privateKey,
            remoteEphemeralPublicKey = aliceResult.aliceEphemeralPublicKey,
            remoteSigningPublicKey = alice.signingPublicKey,
            remoteEncryptionPublicKey = alice.encryptionPublicKey,
            remoteDisplayName = alice.displayName,
            contactId = "alice-contact-id"
        )

        // 5. Cryptographic root and relationship ID convergence
        assertArrayEquals(
            "Alice and Bob must arrive at the exact same pairRootSecret via 3DH",
            aliceResult.relationship.pairRootSecret,
            bobResult.relationship.pairRootSecret
        )
        assertEquals(
            "Alice and Bob must derive the exact same relationshipId",
            aliceResult.relationship.relationshipId,
            bobResult.relationship.relationshipId
        )

        // 6. Purpose-separated key derivations must match byte-for-byte
        assertArrayEquals(
            aliceResult.secrets.relationshipSecret,
            bobResult.secrets.relationshipSecret
        )
        assertArrayEquals(
            aliceResult.secrets.connectionBootstrapSecret,
            bobResult.secrets.connectionBootstrapSecret
        )
        assertArrayEquals(
            aliceResult.secrets.queueBootstrapSecret,
            bobResult.secrets.queueBootstrapSecret
        )
        assertArrayEquals(
            aliceResult.secrets.sessionInitializationSecret,
            bobResult.secrets.sessionInitializationSecret
        )

        // 7. Directional queue IDs and authentication secrets must align perfectly
        assertEquals(
            "Alice's outbound queue must match Bob's inbound queue",
            aliceResult.aliceToBobQueueId,
            bobResult.aliceToBobQueueId
        )
        assertEquals(
            "Bob's outbound queue must match Alice's inbound queue",
            aliceResult.bobToAliceQueueId,
            bobResult.bobToAliceQueueId
        )
        assertArrayEquals(
            "Alice's send authenticator secret must match Bob's receive auth",
            aliceResult.aliceSendAuth,
            bobResult.aliceSendAuth
        )
        assertArrayEquals(
            "Bob's send authenticator secret must match Alice's receive auth",
            aliceResult.bobSendAuth,
            bobResult.bobSendAuth
        )
    }

    @Test
    fun testMismatchedEphemeralKeyFailsAgreement() {
        val alice = generateIdentity("Alice")
        val bob = generateIdentity("Bob")

        val bobEphemeralReal = IdentityCrypto.generateX25519KeyPair()
        val bobEphemeralWrong = IdentityCrypto.generateX25519KeyPair()

        val inviteId = UUID.randomUUID().toString()
        val signedData = ContactInviteCodec.serializeForSigning(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = bob.identityId,
            displayName = bob.displayName,
            signingPublicKey = bob.signingPublicKey,
            encryptionPublicKey = bob.encryptionPublicKey,
            bootstrapEphemeralPublicKey = bobEphemeralReal.publicKey,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L
        )
        val signature = IdentityCrypto.signEd25519(bob.signingPrivateKey, signedData)

        val invite = ContactInviteV1(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = bob.identityId,
            displayName = bob.displayName,
            identitySigningPublicKey = bob.signingPublicKey,
            identityEncryptionPublicKey = bob.encryptionPublicKey,
            bootstrapEphemeralPublicKey = bobEphemeralReal.publicKey,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            signature = signature
        )

        val aliceResult = RelationshipService.establishFromInvite(alice, invite)

        // Bob attempts to respond with the WRONG ephemeral private key
        val bobResult = RelationshipService.establishResponder(
            localIdentity = bob,
            ephemeralBootstrapPrivateKey = bobEphemeralWrong.privateKey,
            remoteEphemeralPublicKey = aliceResult.aliceEphemeralPublicKey,
            remoteSigningPublicKey = alice.signingPublicKey,
            remoteEncryptionPublicKey = alice.encryptionPublicKey,
            remoteDisplayName = alice.displayName
        )

        assertFalse(
            "Mismatched ephemeral private key must NOT produce matching pairRootSecret",
            aliceResult.relationship.pairRootSecret.contentEquals(bobResult.relationship.pairRootSecret)
        )
        assertNotEquals(
            "Mismatched ephemeral private key must NOT produce matching relationshipId",
            aliceResult.relationship.relationshipId,
            bobResult.relationship.relationshipId
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEstablishFromTamperedInviteThrows() {
        val alice = generateIdentity("Alice")
        val bob = generateIdentity("Bob")
        val bobEphemeral = IdentityCrypto.generateX25519KeyPair()

        val invite = ContactInviteV1(
            protocolVersion = 1,
            inviteId = "inv-1",
            identityId = bob.identityId,
            displayName = "Bob",
            identitySigningPublicKey = bob.signingPublicKey,
            identityEncryptionPublicKey = bob.encryptionPublicKey,
            bootstrapEphemeralPublicKey = bobEphemeral.publicKey,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 86400000L,
            signature = ByteArray(64) { 0x00 } // Invalid signature
        )

        RelationshipService.establishFromInvite(alice, invite)
    }
}
