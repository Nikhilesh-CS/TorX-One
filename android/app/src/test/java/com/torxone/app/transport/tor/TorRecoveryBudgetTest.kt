package com.torxone.app.transport.tor

import org.junit.Assert.*
import org.junit.Test

class TorRecoveryBudgetTest {
    @Test fun crashLoopHasThreeRepairsAndBriefSuccessDoesNotReplenishIt() {
        var now = 0L
        val recovery = TorRecoveryBudget(clock = { now })
        val ticket = recovery.start()
        assertEquals(1_000L, recovery.nextDelay(ticket))
        recovery.healthy(ticket)
        now = 59_999
        recovery.healthy(ticket)
        recovery.unhealthy(ticket)
        assertEquals(2_000L, recovery.nextDelay(ticket))
        assertEquals(4_000L, recovery.nextDelay(ticket))
        assertNull(recovery.nextDelay(ticket))
    }

    @Test fun sustainedHealthyLifecycleReplenishesBudgetAndStopRejectsOldRecovery() {
        var now = 0L
        val recovery = TorRecoveryBudget(clock = { now })
        val old = recovery.start()
        repeat(3) { assertNotNull(recovery.nextDelay(old)) }
        recovery.healthy(old)
        now = 60_000
        recovery.healthy(old)
        recovery.unhealthy(old)
        assertEquals(1_000L, recovery.nextDelay(old))
        recovery.stop()
        assertFalse(recovery.isCurrent(old))
        assertNull(recovery.nextDelay(old))
        val fresh = recovery.start()
        assertNull(recovery.nextDelay(old))
        assertEquals(1_000L, recovery.nextDelay(fresh))
    }
}
