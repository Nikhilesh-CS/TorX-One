package com.torxone.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportingUiTest {
    @Test fun temporaryMuteUsesAbsoluteFutureExpiration() {
        val now = 1_791_158_400_000L
        val eightHours = 8 * 60 * 60 * 1_000L
        val sevenDays = 7 * 24 * 60 * 60 * 1_000L
        assertEquals(now + eightHours, temporaryMuteUntil(now, eightHours))
        assertEquals(now + sevenDays, temporaryMuteUntil(now, sevenDays))
        assertTrue(temporaryMuteUntil(now, eightHours) > now)
    }
    @Test fun muteExpiryCannotOverflowAndBecomeAlreadyExpired() {
        assertEquals(Long.MAX_VALUE, temporaryMuteUntil(Long.MAX_VALUE - 1, 60_000))
    }
}
