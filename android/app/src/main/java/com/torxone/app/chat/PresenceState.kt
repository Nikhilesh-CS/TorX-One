package com.torxone.app.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pairwise presence statuses.
 */
enum class PresenceStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

/**
 * Pairwise peer presence state.
 * Maintained per relationship.
 */
data class PeerPresenceState(
    val relationshipId: String,
    val status: PresenceStatus = PresenceStatus.UNKNOWN,
    val lastSeenAt: Long? = null,
    val isTyping: Boolean = false,
    val typingExpiresAt: Long? = null
)

/**
 * WhatsApp-style presence and last-seen formatter.
 */
object PresenceFormatter {
    fun formatLastSeen(lastSeenAt: Long?, now: Long = System.currentTimeMillis()): String {
        if (lastSeenAt == null || lastSeenAt <= 0L) return "offline"

        val calNow = Calendar.getInstance().apply { timeInMillis = now }
        val calSeen = Calendar.getInstance().apply { timeInMillis = lastSeenAt }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(lastSeenAt))

        val isSameYear = calNow.get(Calendar.YEAR) == calSeen.get(Calendar.YEAR)
        val dayDiff = calNow.get(Calendar.DAY_OF_YEAR) - calSeen.get(Calendar.DAY_OF_YEAR)

        return when {
            isSameYear && dayDiff == 0 -> "last seen today at $timeFormat"
            isSameYear && dayDiff == 1 -> "last seen yesterday at $timeFormat"
            else -> {
                val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(lastSeenAt))
                "last seen $dateFormat at $timeFormat"
            }
        }
    }
}
