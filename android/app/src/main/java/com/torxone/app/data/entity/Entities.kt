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
    val type: ConversationType = ConversationType.DIRECT,

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

    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "pinned_at")
    val pinnedAt: Long? = null,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    @ColumnInfo(name = "manually_unread")
    val manuallyUnread: Boolean = false,

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
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "remote_identity_id", defaultValue = "''")
    val remoteIdentityId: String = ""
) {
    companion object {
        const val REMOTE_IDENTITY_UNKNOWN = "REMOTE_IDENTITY_UNKNOWN"
    }

    val isRemoteIdentityKnown: Boolean
        get() = remoteIdentityId.isNotBlank() && remoteIdentityId != REMOTE_IDENTITY_UNKNOWN

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

    /**
     * Shared secret key (`sendAuth` capability) used by TorXAgent to compute the
     * HMAC-SHA256 queue authenticator for the transport envelope.
     * Persisted as "queue_authenticator" column for database schema stability.
     */
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
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expects_ack")
    val expectsAck: Boolean = true
) {
    /**
     * Cryptographic semantic accessor: this field holds the shared secret (`sendAuth`)
     * used to compute the HMAC, NOT the derived HMAC itself.
     */
    val queueAuthSecret: ByteArray get() = queueAuthenticator

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

/**
 * Reaction — An emoji reaction to a message by a specific user.
 * Stored in its own table rather than stuffing into MessageEntity.
 */
@Entity(
    tableName = "reactions",
    primaryKeys = ["message_id", "sender_id", "emoji"],
    indices = [
        Index("message_id"),
        Index("conversation_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["logical_message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReactionEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "emoji")
    val emoji: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Local-only message state (e.g. Delete for Me).
 * Kept strictly separate from synced MessageEntity to avoid protocol leakage.
 */
@Entity(
    tableName = "local_message_state",
    indices = [Index("conversation_id")]
)
data class LocalMessageStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "hidden_locally")
    val hiddenLocally: Boolean = true,

    @ColumnInfo(name = "hidden_at")
    val hiddenAt: Long = System.currentTimeMillis()
)

/**
 * Media attachment associated with a logical chat message.
 *
 * Invariant: Never stores raw file binary data in Room.
 * Stores metadata, cryptographic verification hashes, encryption key, and local path.
 */
@Entity(
    tableName = "media",
    indices = [
        Index("message_id", unique = true),
        Index("conversation_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["logical_message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MediaEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "media_type")
    val mediaType: String, // MediaType.IMAGE, VIDEO, AUDIO, DOCUMENT, VOICE_NOTE

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "local_path")
    val localPath: String? = null,

    @ColumnInfo(name = "encrypted_sha256")
    val encryptedSha256: String,

    @ColumnInfo(name = "media_key", typeAffinity = ColumnInfo.BLOB)
    val mediaKey: ByteArray,

    @ColumnInfo(name = "thumbnail_data", typeAffinity = ColumnInfo.BLOB)
    val thumbnailData: ByteArray? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "waveform_data", typeAffinity = ColumnInfo.BLOB)
    val waveformData: ByteArray? = null,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "status")
    val status: String, // MediaStatus: PREPARING, QUEUED, UPLOADING, SENT, DELIVERED, DOWNLOADING, COMPLETE, FAILED, CANCELLED

    @ColumnInfo(name = "transfer_progress")
    val transferProgress: Float = 0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaEntity) return false
        return mediaId == other.mediaId
    }

    override fun hashCode(): Int = mediaId.hashCode()
}

/**
 * Tracks chunked transfer progress, resumability, and chunk bitmask.
 */
@Entity(
    tableName = "media_transfers",
    indices = [Index("media_id"), Index("conversation_id")]
)
data class MediaTransferEntity(
    @PrimaryKey
    @ColumnInfo(name = "transfer_id")
    val transferId: String,

    @ColumnInfo(name = "media_id")
    val mediaId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "direction")
    val direction: String, // TransferDirection: UPLOAD, DOWNLOAD

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    @ColumnInfo(name = "chunk_size")
    val chunkSize: Int,

    @ColumnInfo(name = "completed_chunks")
    val completedChunks: Int = 0,

    @ColumnInfo(name = "chunk_bitmask")
    val chunkBitmask: String = "",

    @ColumnInfo(name = "temp_encrypted_path")
    val tempEncryptedPath: String,

    @ColumnInfo(name = "status")
    val status: String, // TransferStatus: IDLE, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED

    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long = 0L,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "consumed_invites")
data class ConsumedInviteEntity(
    @PrimaryKey
    @ColumnInfo(name = "invite_id")
    val inviteId: String,

    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long = System.currentTimeMillis()
)

enum class BootstrapStatus {
    PENDING,
    LOCAL_ESTABLISHED,
    BOOTSTRAP_QUEUED,
    REMOTE_CONFIRMED,
    ACTIVE,
    FAILED
}

@Entity(tableName = "bootstrap_states")
data class BootstrapStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "invite_id")
    val inviteId: String,

    @ColumnInfo(name = "status")
    val status: BootstrapStatus,

    @ColumnInfo(name = "is_initiator")
    val isInitiator: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)


