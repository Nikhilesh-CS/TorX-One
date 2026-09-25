package com.torxone.app.data.entity

import androidx.room.*

/**
 * Conversation — either DIRECT (1:1) or GROUP.
 * Chat UI doesn't care about transport.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val conversationId: String,

    @ColumnInfo(name = "type")
    val type: ConversationType,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "avatar_hash")
    val avatarHash: String? = null,

    @ColumnInfo(name = "last_message_id")
    val lastMessageId: String? = null,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,

    @ColumnInfo(name = "last_message_time")
    val lastMessageTime: Long? = null,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    @ColumnInfo(name = "muted_until")
    val mutedUntil: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

enum class ConversationType {
    DIRECT,
    GROUP
}

/**
 * Message — the logical chat message.
 * Completely separate from delivery envelope.
 *
 * Do NOT use transport queue IDs as chat message IDs.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("conversation_id", "created_at"),
        Index("logical_message_id", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "logical_message_id")
    val logicalMessageId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "body")
    val body: String?,

    @ColumnInfo(name = "direction")
    val direction: MessageDirection,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "received_at")
    val receivedAt: Long? = null,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "read_at")
    val readAt: Long? = null,

    @ColumnInfo(name = "reply_to_message_id")
    val replyToMessageId: String? = null,

    @ColumnInfo(name = "edited_at")
    val editedAt: Long? = null,

    @ColumnInfo(name = "edit_version")
    val editVersion: Int = 0,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null
)

enum class MessageDirection {
    OUTGOING,
    INCOMING
}

/**
 * Contact — maps to a PairRelationship.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val contactId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "avatar_hash")
    val avatarHash: String? = null,

    @ColumnInfo(name = "signing_public_key")
    val signingPublicKey: ByteArray,

    @ColumnInfo(name = "verification_state")
    val verificationState: String = "UNVERIFIED",

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return contactId == other.contactId
    }

    override fun hashCode(): Int = contactId.hashCode()
}

/**
 * Durable outbox item — persisted to survive crashes.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("status"), Index("logical_message_id")]
)
data class OutboxEntity(
    @PrimaryKey
    val deliveryId: String,

    @ColumnInfo(name = "logical_message_id")
    val logicalMessageId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "connection_id")
    val connectionId: String,

    @ColumnInfo(name = "queue_address")
    val queueAddress: String,

    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB)
    val ciphertext: ByteArray,

    @ColumnInfo(name = "queue_authenticator", typeAffinity = ColumnInfo.BLOB)
    val queueAuthenticator: ByteArray,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "priority")
    val priority: Int = 10,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return deliveryId == other.deliveryId
    }

    override fun hashCode(): Int = deliveryId.hashCode()
}

/**
 * Tracks processed envelopes for deduplication.
 * Must survive app restart.
 */
@Entity(
    tableName = "processed_envelopes",
    indices = [Index("logical_message_id")]
)
data class ProcessedEnvelopeEntity(
    @PrimaryKey
    @ColumnInfo(name = "envelope_id")
    val envelopeId: String,

    @ColumnInfo(name = "logical_message_id")
    val logicalMessageId: String,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long = System.currentTimeMillis()
)

/**
 * PairRelationship — First-class cryptographic relationship between local user and contact.
 */
@Entity(
    tableName = "pair_relationships",
    indices = [Index("contact_id"), Index("local_identity_id")]
)
data class PairRelationshipEntity(
    @PrimaryKey
    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "local_identity_id")
    val localIdentityId: String,

    @ColumnInfo(name = "contact_id")
    val contactId: String,

    @ColumnInfo(name = "root_secret", typeAffinity = ColumnInfo.BLOB)
    val rootSecret: ByteArray,

    @ColumnInfo(name = "state")
    val state: String = "ACTIVE",

    @ColumnInfo(name = "generation")
    val generation: Int = 1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "verified_at")
    val verifiedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRelationshipEntity) return false
        return relationshipId == other.relationshipId
    }

    override fun hashCode(): Int = relationshipId.hashCode()
}

/**
 * Connection — Replaceable routing generation for a relationship.
 */
@Entity(
    tableName = "connections",
    indices = [
        Index("relationship_id"),
        Index("send_queue_id"),
        Index("recv_queue_id")
    ]
)
data class ConnectionDbEntity(
    @PrimaryKey
    @ColumnInfo(name = "connection_id")
    val connectionId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "generation")
    val generation: Int = 1,

    @ColumnInfo(name = "send_queue_id")
    val sendQueueId: String,

    @ColumnInfo(name = "recv_queue_id")
    val recvQueueId: String,

    @ColumnInfo(name = "send_auth", typeAffinity = ColumnInfo.BLOB)
    val sendAuth: ByteArray,

    @ColumnInfo(name = "recv_auth", typeAffinity = ColumnInfo.BLOB)
    val recvAuth: ByteArray,

    @ColumnInfo(name = "state")
    val state: String = "ACTIVE",

    @ColumnInfo(name = "send_sequence")
    val sendSequence: Long = 0L,

    @ColumnInfo(name = "recv_sequence")
    val recvSequence: Long = 0L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionDbEntity) return false
        return connectionId == other.connectionId
    }

    override fun hashCode(): Int = connectionId.hashCode()
}

/**
 * Double Ratchet Session state persistence.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("relationship_id", unique = true)]
)
data class SessionDbEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "root_key", typeAffinity = ColumnInfo.BLOB)
    val rootKey: ByteArray,

    @ColumnInfo(name = "local_dh_public", typeAffinity = ColumnInfo.BLOB)
    val localDhPublicKey: ByteArray,

    @ColumnInfo(name = "local_dh_private", typeAffinity = ColumnInfo.BLOB)
    val localDhPrivateKey: ByteArray,

    @ColumnInfo(name = "remote_dh_public", typeAffinity = ColumnInfo.BLOB)
    val remoteDhPublicKey: ByteArray,

    @ColumnInfo(name = "send_chain_key", typeAffinity = ColumnInfo.BLOB)
    val sendChainKey: ByteArray?,

    @ColumnInfo(name = "recv_chain_key", typeAffinity = ColumnInfo.BLOB)
    val recvChainKey: ByteArray?,

    @ColumnInfo(name = "send_message_number")
    val sendMessageNumber: Int,

    @ColumnInfo(name = "receive_message_number")
    val receiveMessageNumber: Int,

    @ColumnInfo(name = "previous_send_count")
    val previousSendCount: Int,

    @ColumnInfo(name = "state")
    val state: String = "ACTIVE",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionDbEntity) return false
        return sessionId == other.sessionId && relationshipId == other.relationshipId
    }

    override fun hashCode(): Int = sessionId.hashCode()
}

/**
 * Skipped message keys for out-of-order delivery.
 */
@Entity(
    tableName = "skipped_message_keys",
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "ratchet_public_key_hex", "counter"], unique = true)
    ]
)
data class SkippedKeyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "ratchet_public_key_hex")
    val ratchetPublicKeyHex: String,

    @ColumnInfo(name = "counter")
    val counter: Int,

    @ColumnInfo(name = "message_key", typeAffinity = ColumnInfo.BLOB)
    val messageKey: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkippedKeyEntity) return false
        return sessionId == other.sessionId &&
                ratchetPublicKeyHex == other.ratchetPublicKeyHex &&
                counter == other.counter
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + ratchetPublicKeyHex.hashCode()
        result = 31 * result + counter
        return result
    }
}

/**
 * Pending invite state stored locally by Bob when creating a contact QR invite.
 * Persists the ephemeral bootstrap private key so Bob can compute 3DH responder keys (Section 4).
 */
@Entity(tableName = "pending_invites")
data class PendingInviteEntity(
    @PrimaryKey
    @ColumnInfo(name = "invite_id")
    val inviteId: String,

    @ColumnInfo(name = "ephemeral_public_key", typeAffinity = ColumnInfo.BLOB)
    val ephemeralPublicKey: ByteArray,

    @ColumnInfo(name = "ephemeral_private_key", typeAffinity = ColumnInfo.BLOB)
    val ephemeralPrivateKey: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingInviteEntity) return false
        return inviteId == other.inviteId
    }

    override fun hashCode(): Int = inviteId.hashCode()
}

