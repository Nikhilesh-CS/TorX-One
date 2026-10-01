package com.torxone.app.privacy

import androidx.room.*
import com.torxone.app.data.entity.ConversationEntity

/** This device's outgoing timer. It does not claim to change another member's policy. */
@Entity(tableName = "conversation_security_policy", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["conversationId"],
    childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class ConversationSecurityPolicy(@PrimaryKey val conversationId: String,
    val disappearAfterMs: Long?, val updatedAt: Long)

/** Minimal authenticated route binding, retained after media keys and filenames are erased. */
@Entity(tableName = "expired_media", primaryKeys = ["mediaId", "relationshipId"])
data class ExpiredMediaTombstone(val mediaId: String, val relationshipId: String,
    val conversationId: String, val expiredAt: Long)

/** File deletion is retried after process death; a failed unlink never counts as success. */
@Entity(tableName = "privacy_file_cleanup")
data class PrivacyFileCleanup(@PrimaryKey val path: String, val queuedAt: Long)

/** Committed before private file creation, including files not yet attached to a message. */
@Entity(tableName = "pending_media_files")
data class PendingMediaFile(@PrimaryKey val path: String, val mediaId: String, val createdAt: Long)
