package com.torxone.app.ui.components

import com.torxone.app.chat.MessageUiModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ChatTimelineItem(
    val message: MessageUiModel,
    val dateLabel: String?,
    val startsGroup: Boolean,
    val endsGroup: Boolean,
    val unreadCount: Int = 0
)

/** One presentation item per record: lazy indices remain message indices. */
fun chatTimeline(
    messages: List<MessageUiModel>,
    unreadMessageId: String? = null,
    unreadCount: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    locale: Locale = Locale.getDefault()
): List<ChatTimelineItem> {
    val dates = messages.map { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
    fun grouped(left: Int, right: Int): Boolean {
        if (left < 0 || right >= messages.size) return false
        val a = messages[left]; val b = messages[right]
        return !a.isDeleted && !b.isDeleted && a.senderId == b.senderId && a.direction == b.direction &&
            dates[left] == dates[right] && b.logicalMessageId != unreadMessageId &&
            b.createdAt >= a.createdAt && b.createdAt - a.createdAt <= 120_000
    }
    return messages.mapIndexed { index, message ->
        val date = dates[index]
        val dateLabel = if (index == 0 || dates[index - 1] != date) when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date < today && date >= today.minusDays(6) -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
            else -> date.format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))
        } else null
        ChatTimelineItem(message, dateLabel, !grouped(index - 1, index), !grouped(index, index + 1),
            if (message.logicalMessageId == unreadMessageId) unreadCount.coerceAtLeast(0) else 0)
    }
}
