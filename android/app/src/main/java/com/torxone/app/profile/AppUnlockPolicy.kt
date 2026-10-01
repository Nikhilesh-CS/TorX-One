package com.torxone.app.profile

/** Shared screen/background gate. Hardware key authorization is still checked by the vault. */
object AppUnlockPolicy {
    fun isAuthorized(unlocked: Boolean, backgroundAt: Long, timeoutMs: Long, now: Long): Boolean {
        if (!unlocked) return false
        if (backgroundAt == 0L) return true
        val elapsed = now - backgroundAt
        return elapsed >= 0L && elapsed < timeoutMs.coerceAtLeast(0L)
    }
}
