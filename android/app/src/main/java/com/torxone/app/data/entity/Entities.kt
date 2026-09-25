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
