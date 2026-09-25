package com.torxone.app.relationship

import com.torxone.app.identity.*
import java.security.MessageDigest
import java.util.UUID

/**
 * Handles authenticated pairwise relationship establishment and key derivation.
 */
object RelationshipService {

    private const val INFO_RELATIONSHIP = "torx-relationship-secret-v1"
    private const val INFO_CONNECTION_BOOTSTRAP = "torx-connection-bootstrap-v1"
    private const val INFO_QUEUE_BOOTSTRAP = "torx-queue-bootstrap-v1"
    private const val INFO_SESSION_INIT = "torx-session-init-v1"

    data class BootstrapResult(
        val relationship: PairRelationship,
        val secrets: DerivedRelationshipSecrets,
        val aliceToBobQueueId: String,
        val bobToAliceQueueId: String,
        val aliceSendAuth: ByteArray,
        val bobSendAuth: ByteArray
    )

    /**
     * Alice establishes relationship by consuming Bob's scanned invite.
     * Computes 3DH key agreement and purpose-separated keys.
     */
    fun establishFromInvite(
        localIdentity: TorXIdentity,
        invite: ContactInviteV1,
        contactId: String = UUID.randomUUID().toString()
    ): BootstrapResult {
        // Validation check
        val validation = ContactInviteCodec.validate(invite, localIdentity)
        require(validation is InviteValidationResult.Valid) {
            "Cannot establish relationship from invalid invite: ${(validation as InviteValidationResult.Invalid).error}"
        }

        // Alice generates fresh ephemeral key pair
        val aliceEphemeral = IdentityCrypto.generateX25519KeyPair()

        // 3DH:
        // DH1: Alice Ephemeral + Bob Ephemeral (from invite)
        val dh1 = IdentityCrypto.diffieHellmanX25519(
            aliceEphemeral.privateKey,
            invite.bootstrapEphemeralPublicKey
        )

        // DH2: Alice Identity Enc + Bob Ephemeral
        val dh2 = IdentityCrypto.diffieHellmanX25519(
            localIdentity.encryptionPrivateKey,
            invite.bootstrapEphemeralPublicKey
        )

        // DH3: Alice Ephemeral + Bob Identity Enc
        val dh3 = IdentityCrypto.diffieHellmanX25519(
            aliceEphemeral.privateKey,
            invite.identityEncryptionPublicKey
        )

        val ikm = dh1 + dh2 + dh3

        // Salt bound to both signing keys (sorted deterministically)
        val salt = computeSalt(localIdentity.signingPublicKey, invite.identitySigningPublicKey)

        val pairRootSecret = IdentityCrypto.hkdf(
            ikm = ikm,
            salt = salt,
            info = "torx-pair-root-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )

        val secrets = deriveSecrets(pairRootSecret)

        // Derive directional queues
        val (aToBQueue, bToAQueue, aSendAuth, bSendAuth) = deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            localIdentity.signingPublicKey,
            invite.identitySigningPublicKey
        )

        val relationship = PairRelationship(
            relationshipId = UUID.randomUUID().toString(),
            localIdentityId = localIdentity.identityId,
            contactId = contactId,
            remoteDisplayName = invite.displayName,
            remoteSigningPublicKey = invite.identitySigningPublicKey,
            remoteEncryptionPublicKey = invite.identityEncryptionPublicKey,
            pairRootSecret = pairRootSecret,
            state = RelationshipState.ACTIVE,
            generation = 1,
            createdAt = System.currentTimeMillis(),
            verifiedAt = System.currentTimeMillis()
        )

        return BootstrapResult(
            relationship = relationship,
            secrets = secrets,
            aliceToBobQueueId = aToBQueue,
            bobToAliceQueueId = bToAQueue,
            aliceSendAuth = aSendAuth,
            bobSendAuth = bSendAuth
        )
    }

    /**
     * Bob establishes relationship as responder using his stored invite ephemeral private key
     * and Alice's ephemeral and identity public keys.
     * Computes the exact matching 3DH key agreement and derived keys.
     */
    fun establishResponder(
        localIdentity: TorXIdentity,
        ephemeralBootstrapPrivateKey: ByteArray,
        remoteEphemeralPublicKey: ByteArray,
        remoteSigningPublicKey: ByteArray,
        remoteEncryptionPublicKey: ByteArray,
        remoteDisplayName: String,
        contactId: String = UUID.randomUUID().toString()
    ): BootstrapResult {
        // DH1: Bob Ephemeral + Alice Ephemeral
        val dh1 = IdentityCrypto.diffieHellmanX25519(
            ephemeralBootstrapPrivateKey,
            remoteEphemeralPublicKey
        )

        // DH2: Bob Ephemeral + Alice Identity Enc
        val dh2 = IdentityCrypto.diffieHellmanX25519(
            ephemeralBootstrapPrivateKey,
            remoteEncryptionPublicKey
        )

        // DH3: Bob Identity Enc + Alice Ephemeral
        val dh3 = IdentityCrypto.diffieHellmanX25519(
            localIdentity.encryptionPrivateKey,
            remoteEphemeralPublicKey
        )

        val ikm = dh1 + dh2 + dh3

        val salt = computeSalt(localIdentity.signingPublicKey, remoteSigningPublicKey)

        val pairRootSecret = IdentityCrypto.hkdf(
            ikm = ikm,
            salt = salt,
            info = "torx-pair-root-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )

        val secrets = deriveSecrets(pairRootSecret)

        val (aToBQueue, bToAQueue, aSendAuth, bSendAuth) = deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            remoteSigningPublicKey, // P1 (Alice)
            localIdentity.signingPublicKey // P2 (Bob)
        )

        val relationship = PairRelationship(
            relationshipId = UUID.randomUUID().toString(),
            localIdentityId = localIdentity.identityId,
            contactId = contactId,
            remoteDisplayName = remoteDisplayName,
            remoteSigningPublicKey = remoteSigningPublicKey,
            remoteEncryptionPublicKey = remoteEncryptionPublicKey,
            pairRootSecret = pairRootSecret,
            state = RelationshipState.ACTIVE,
            generation = 1,
            createdAt = System.currentTimeMillis(),
            verifiedAt = System.currentTimeMillis()
        )

        return BootstrapResult(
            relationship = relationship,
            secrets = secrets,
            aliceToBobQueueId = aToBQueue,
            bobToAliceQueueId = bToAQueue,
            aliceSendAuth = aSendAuth,
            bobSendAuth = bSendAuth
        )
    }

    /**
     * Derives purpose-separated keys via HKDF.
     */
    fun deriveSecrets(pairRootSecret: ByteArray): DerivedRelationshipSecrets {
        return DerivedRelationshipSecrets(
            relationshipSecret = IdentityCrypto.hkdf(
                ikm = pairRootSecret,
                info = INFO_RELATIONSHIP.toByteArray(Charsets.UTF_8),
                outputLength = 32
            ),
            connectionBootstrapSecret = IdentityCrypto.hkdf(
                ikm = pairRootSecret,
                info = INFO_CONNECTION_BOOTSTRAP.toByteArray(Charsets.UTF_8),
                outputLength = 32
            ),
            queueBootstrapSecret = IdentityCrypto.hkdf(
                ikm = pairRootSecret,
                info = INFO_QUEUE_BOOTSTRAP.toByteArray(Charsets.UTF_8),
                outputLength = 32
            ),
            sessionInitializationSecret = IdentityCrypto.hkdf(
                ikm = pairRootSecret,
                info = INFO_SESSION_INIT.toByteArray(Charsets.UTF_8),
                outputLength = 32
            )
        )
    }

    /**
     * Derives directional queues and authenticators.
     * Deterministically orders endpoints based on public keys so both peers reach identical queue mapping.
     */
    fun deriveDirectionalQueues(
        queueBootstrapSecret: ByteArray,
        localSignPub: ByteArray,
        remoteSignPub: ByteArray
    ): QueueTuple {
        val isLocalFirst = compareByteArrays(localSignPub, remoteSignPub) < 0
        val p1ToP2Queue = bytesToHex(IdentityCrypto.hkdf(queueBootstrapSecret, info = "p1->p2-queue".toByteArray(), outputLength = 16))
        val p2ToP1Queue = bytesToHex(IdentityCrypto.hkdf(queueBootstrapSecret, info = "p2->p1-queue".toByteArray(), outputLength = 16))

        val p1Auth = IdentityCrypto.hkdf(queueBootstrapSecret, info = "p1-auth".toByteArray(), outputLength = 32)
        val p2Auth = IdentityCrypto.hkdf(queueBootstrapSecret, info = "p2-auth".toByteArray(), outputLength = 32)

        return if (isLocalFirst) {
            QueueTuple(
                sendQueueId = p1ToP2Queue,
                recvQueueId = p2ToP1Queue,
                sendAuth = p1Auth,
                recvAuth = p2Auth
            )
        } else {
            QueueTuple(
                sendQueueId = p2ToP1Queue,
                recvQueueId = p1ToP2Queue,
                sendAuth = p2Auth,
                recvAuth = p1Auth
            )
        }
    }

    data class QueueTuple(
        val sendQueueId: String,
        val recvQueueId: String,
        val sendAuth: ByteArray,
        val recvAuth: ByteArray
    )

    private fun computeSalt(keyA: ByteArray, keyB: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        if (compareByteArrays(keyA, keyB) < 0) {
            md.update(keyA)
            md.update(keyB)
        } else {
            md.update(keyB)
            md.update(keyA)
        }
        return md.digest()
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
