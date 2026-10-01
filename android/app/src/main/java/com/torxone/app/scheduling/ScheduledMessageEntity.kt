package com.torxone.app.scheduling

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.torxone.app.data.entity.ConversationEntity

/** Plain text lives only in the application's SQLCipher database, never WorkManager input. */
@Entity(tableName = "scheduled_messages", foreignKeys = [ForeignKey(
    entity = ConversationEntity::class, parentColumns = ["conversationId"],
    childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("conversationId"), Index(value = ["state", "scheduledAt"])])
data class ScheduledMessageEntity(
    @PrimaryKey val scheduleId: String,
    val conversationId: String,
    val ownerIdentityId: String,
    val draftPayload: String,
    val replyToMessageId: String? = null,
    val scheduledAt: Long,
    val generation: Long = 1,
    val state: String = ScheduledMessageState.PENDING,
    val leaseUntil: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

object ScheduledMessageState {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"
    const val CANCELED = "CANCELED"
    const val FAILED = "FAILED"
}

object SchedulingPolicy {
    const val LEASE_MILLIS = 5 * 60 * 1000L
    const val MAX_AHEAD_MILLIS = 365 * 24 * 60 * 60 * 1000L
    fun validate(text: String, scheduledAt: Long, now: Long) {
        require(text.isNotBlank()) { "Enter a message" }
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Message is too long" }
        require(scheduledAt > now && scheduledAt - now <= MAX_AHEAD_MILLIS) {
            "Choose a future time within one year"
        }
    }
    fun messageId(scheduleId: String) = java.util.UUID.nameUUIDFromBytes(
        "scheduled:$scheduleId".toByteArray(Charsets.UTF_8)).toString()
    fun workName(id: String, generation: Long) = "scheduled-message:$id:$generation"
}
