package com.torxone.app.contacts

import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.connection.Connection
import com.torxone.app.data.entity.OutboxEntity
import java.util.UUID
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.identity.TorXIdentity
import com.torxone.app.relationship.ContactBootstrapPayload

/** Re-sign confirmation with persisted public session material; never initializes a session. */
internal fun signedBootstrapPayload(identity: TorXIdentity, inviteId: String,
                                    localRatchetPublicKey: ByteArray, onionAddress: String): ContactBootstrapPayload {
    com.torxone.app.transport.tor.TorRoute(onionAddress)
    require(localRatchetPublicKey.size == 32)
    val signed = ContactBootstrapPayload.serializeForSigning(inviteId, identity.identityId,
        identity.displayName, identity.signingPublicKey, identity.encryptionPublicKey,
        localRatchetPublicKey, onionAddress)
    return ContactBootstrapPayload(inviteId, identity.identityId, identity.displayName,
        identity.signingPublicKey, identity.encryptionPublicKey, localRatchetPublicKey,
        IdentityCrypto.signEd25519(identity.signingPrivateKey, signed), onionAddress)
}

/** Confirmation ACKs identify the signed invite and the exact transport delivery. */
internal fun bootstrapOutboxItem(inviteId: String, connection: Connection,
                                 conversationId: String, payload: ByteArray,
                                 deliveryId: String = UUID.randomUUID().toString()) = OutboxEntity(
    deliveryId = deliveryId, logicalMessageId = inviteId,
    conversationId = conversationId, connectionId = connection.connectionId,
    relationshipId = connection.relationshipId, queueAddress = "invite-$inviteId",
    ciphertext = payload, queueAuthenticator = ByteArray(0),
    status = DeliveryStatus.QUEUED.name, priority = DeliveryPriority.HIGH, expectsAck = true
)
