package com.torxone.app.privacy

/** Timers start at sender commit, never at receipt; offline delivery cannot extend the timer. */
object DisappearingPolicy {
    const val MIN_TIMER_MS = 60_000L
    const val MAX_TIMER_MS = 30L * 24 * 60 * 60 * 1000
    val presets = listOf(60_000L, 3_600_000L, 86_400_000L, 7L * 86_400_000L)
    fun expiry(now: Long, duration: Long?): Long? {
        if (duration == null) return null
        require(duration in MIN_TIMER_MS..MAX_TIMER_MS) { "Choose a timer between 1 minute and 30 days" }
        require(now > 0 && now <= Long.MAX_VALUE - duration)
        return now + duration
    }
    fun validate(timestamp: Long, expiresAt: Long?) {
        if (expiresAt == null) return
        require(expiresAt > timestamp && expiresAt - timestamp in 1..MAX_TIMER_MS) { "Invalid message expiry" }
    }
    fun expired(expiresAt: Long?, now: Long): Boolean = expiresAt != null && expiresAt <= now
}
