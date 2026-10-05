package com.torxone.app.ui.components

import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.MessageUiModel
import com.torxone.app.data.entity.MessageDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ChatTimelinePresentationTest {
    private val zone = ZoneId.of("UTC")
    private val now = LocalDate.of(2026, 10, 4)
    private val base = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
    private fun message(id: String, at: Long = base, sender: String = "peer") =
        MessageUiModel(id, "chat", sender, "Body", MessageDirection.INCOMING, DeliveryStatus.DELIVERED, at)
    private fun timeline(vararg messages: MessageUiModel) = chatTimeline(messages.toList(), zone = zone, today = now, locale = Locale.US)

    @Test fun groupingIsVisualAndPreservesEveryRecordAndStatus() {
        val messages = listOf(message("a"), message("b", base + 120_000).copy(status = DeliveryStatus.READ))
        val result = chatTimeline(messages, zone = zone, today = now)
        assertEquals(messages, result.map { it.message })
        assertTrue(result.first().startsGroup)
        assertFalse(result.first().endsGroup)
        assertFalse(result.last().startsGroup)
        assertTrue(result.last().endsGroup)
        assertEquals(DeliveryStatus.READ, result.last().message.status)
    }

    @Test fun senderDirectionTimeAndDeletedBoundariesBreakGroups() {
        assertTrue(timeline(message("a"), message("b", base + 120_001)).first().endsGroup)
        assertTrue(timeline(message("a"), message("b", sender = "other")).last().startsGroup)
        assertTrue(timeline(message("a"), message("b").copy(direction = MessageDirection.OUTGOING)).last().startsGroup)
        assertTrue(timeline(message("a"), message("b").copy(isDeleted = true)).first().endsGroup)
        assertTrue(timeline(message("a"), message("b", base - 1)).first().endsGroup)
    }

    @Test fun localMidnightBreaksGroupingEvenInsideTimeWindow() {
        val a = message("a", Instant.parse("2026-10-03T23:59:30Z").toEpochMilli())
        val b = message("b", Instant.parse("2026-10-04T00:00:30Z").toEpochMilli())
        val result = timeline(a, b)
        assertEquals("Yesterday", result.first().dateLabel)
        assertEquals("Today", result.last().dateLabel)
        assertTrue(result.first().endsGroup)
        assertTrue(result.last().startsGroup)
    }

    @Test fun timezoneChangesOnlyCalendarPresentation() {
        val messages = listOf(message("a", Instant.parse("2026-10-03T22:00:00Z").toEpochMilli()))
        val utc = chatTimeline(messages, zone = zone, today = now)
        val india = chatTimeline(messages, zone = ZoneId.of("Asia/Kolkata"), today = now)
        assertEquals("Yesterday", utc.single().dateLabel)
        assertEquals("Today", india.single().dateLabel)
        assertEquals(utc.single().message, india.single().message)
    }

    @Test fun entryUnreadSnapshotSurvivesReadUpdatesButNeverInventsBoundary() {
        val messages = listOf(message("a"), message("b").copy(status = DeliveryStatus.READ))
        val result = chatTimeline(messages, "b", 5, zone, now)
        assertEquals(listOf(0, 5), result.map { it.unreadCount })
        assertTrue(result.last().startsGroup)
        assertTrue(chatTimeline(messages, "removed", 5, zone, now).all { it.unreadCount == 0 })
    }

    @Test fun dateLabelsAppearOnlyAtCalendarBoundaries() {
        val result = timeline(message("a"), message("b", base + 60_000), message("c", base + 86_400_000))
        assertEquals("Today", result[0].dateLabel)
        assertNull(result[1].dateLabel)
        assertEquals("5 Oct 2026", result[2].dateLabel)
    }
}
