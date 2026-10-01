package com.torxone.app.data.entity

import androidx.room.*

/**
 * Roles for group members.
 */
enum class GroupMemberRole {
    OWNER,
    ADMIN,
    MEMBER;

    companion object {
        fun fromString(value: String): GroupMemberRole {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEMBER
        }
    }
}

/**
 * Lifecycle states for group members.
 */
enum class GroupMemberState {
    INVITED,
    ACTIVE,
    REMOVED,
    LEFT;

    companion object {
        fun fromString(value: String): GroupMemberState {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INVITED
        }
    }
}

/**
 * Delivery status for individual group members.
 */
enum class GroupDeliveryStatus {
    EXPIRED,
    PENDING,
    QUEUED,
    DELIVERED,
    READ,
    FAILED;

    companion object {
        fun fromString(value: String): GroupDeliveryStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
        }
    }
}

/**
 * Group metadata entity.
 * Represents a secure direct group managed over pairwise relationships.
 * The conversationId links to ConversationEntity (type = GROUP).
 */
@Entity(
    tableName = "groups",
    indices = [
        Index("conversation_id", unique = true),
        Index("creator_identity_id")
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
data class GroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "avatar_hash")
    val avatarHash: String? = null,

    @ColumnInfo(name = "creator_identity_id")
    val creatorIdentityId: String,

    @ColumnInfo(name = "epoch")
    val epoch: Long = 1L,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a member of a group.
 * Identity links to pairwise contact and relationship.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["group_id", "member_identity_id"],
    indices = [
        Index("group_id"),
        Index("member_identity_id"),
        Index("contact_id"),
        Index("relationship_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["group_id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupMemberEntity(
    @ColumnInfo(name = "group_id")
    val groupId: String,

    @ColumnInfo(name = "member_identity_id")
    val memberIdentityId: String,

    @ColumnInfo(name = "contact_id")
    val contactId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "role")
    val role: String = GroupMemberRole.MEMBER.name,

    @ColumnInfo(name = "state")
    val state: String = GroupMemberState.ACTIVE.name,

    @ColumnInfo(name = "joined_epoch")
    val joinedEpoch: Long = 1L,

    @ColumnInfo(name = "removed_epoch")
    val removedEpoch: Long? = null,

    @ColumnInfo(name = "joined_at")
    val joinedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "removed_at")
    val removedAt: Long? = null
)

/**
 * Tracks delivery status of a logical group message to each active recipient.
 * Enables accurate derivation of group message status (SENT, DELIVERED, READ)
 * and message info details (Delivered to X, Read by Y).
 */
@Entity(
    tableName = "group_message_deliveries",
    indices = [
        Index("logical_message_id"),
        Index("recipient_identity_id"),
        Index("outbox_delivery_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["logical_message_id"],
            childColumns = ["logical_message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupMessageDeliveryEntity(
    @PrimaryKey
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String,

    @ColumnInfo(name = "logical_message_id")
    val logicalMessageId: String,

    @ColumnInfo(name = "recipient_identity_id")
    val recipientIdentityId: String,

    @ColumnInfo(name = "relationship_id")
    val relationshipId: String,

    @ColumnInfo(name = "outbox_delivery_id")
    val outboxDeliveryId: String? = null,

    @ColumnInfo(name = "status")
    val status: String = GroupDeliveryStatus.PENDING.name,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "read_at")
    val readAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One locally-authored group epoch transition. The operation is written in the
 * same transaction as the local group mutation so a crash cannot orphan fan-out.
 */
@Entity(
    tableName = "group_control_operations",
    indices = [
        Index(value = ["group_id", "new_epoch"], unique = true),
        Index(value = ["status", "created_at"])
    ]
)
data class GroupControlOperationEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "previous_epoch") val previousEpoch: Long,
    @ColumnInfo(name = "new_epoch") val newEpoch: Long,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

/** A recipient-specific control envelope belonging to an ordered epoch transition. */
@Entity(
    tableName = "group_control_deliveries",
    primaryKeys = ["operation_id", "recipient_identity_id"],
    indices = [Index("relationship_id"), Index(value = ["operation_id", "status"])],
    foreignKeys = [
        ForeignKey(
            entity = GroupControlOperationEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupControlDeliveryEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "recipient_identity_id") val recipientIdentityId: String,
    @ColumnInfo(name = "relationship_id") val relationshipId: String,
    @ColumnInfo(name = "message_type") val messageType: String,
    @ColumnInfo(name = "payload") val payload: ByteArray,
    @ColumnInfo(name = "envelope_epoch") val envelopeEpoch: Long,
    @ColumnInfo(name = "status") val status: String = "PENDING",
    @ColumnInfo(name = "outbox_delivery_id") val outboxDeliveryId: String? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
