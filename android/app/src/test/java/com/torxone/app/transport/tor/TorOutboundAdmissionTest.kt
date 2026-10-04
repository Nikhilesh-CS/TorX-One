package com.torxone.app.transport.tor

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TorOutboundAdmissionTest {
    @Test fun singlePeerCannotConsumeEveryReservationAndReleaseRestoresThatPeersCapacity() = runBlocking {
        val admission = TorOutboundAdmission(activeLimit = 2, callerLimit = 4, perKeyCallerLimit = 2)
        val first = admission.reserve("peer-a")
        val second = admission.reserve("peer-a")
        try {
            try { admission.reserve("peer-a"); fail("Expected exact peer bound") } catch (_: IOException) { }
            val independent = admission.reserve("peer-b")
            withTimeout(1_000) { independent.use { it.activate() } }
            assertEquals(2, admission.reservedCount())
            first.close()
            val replacement = admission.reserve("peer-a")
            replacement.close()
        } finally { first.close(); second.close() }
        assertEquals(0, admission.reservedCount())
    }

    @Test fun fifoWaitersSuspendAndExcessCallersFailImmediately() = runBlocking {
        val admission = TorOutboundAdmission(1, 3)
        val held = admission.reserve().also { it.activate() }
        val order = mutableListOf<Int>()
        val first = admission.reserve()
        val second = admission.reserve()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { first.use { it.activate(); order += 1 } }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { second.use { it.activate(); order += 2 } }
        try {
            assertTrue(firstJob.isActive)
            assertTrue(secondJob.isActive)
            try { admission.reserve(); fail("Expected bounded caller rejection") } catch (_: IOException) {}
            assertTrue(order.isEmpty())
            held.close()
            withTimeout(1_000) { joinAll(firstJob, secondJob) }
            assertEquals(listOf(1, 2), order)
            assertEquals(0, admission.reservedCount())
        } finally { held.close(); firstJob.cancelAndJoin(); secondJob.cancelAndJoin() }
    }

    @Test fun cancellingQueuedCallerReleasesCapacityWithoutLeakingPermit() = runBlocking {
        val admission = TorOutboundAdmission(1, 2)
        val held = admission.reserve().also { it.activate() }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            admission.reserve().use { it.activate(); fail("Queued caller must not run") }
        }
        waiter.cancelAndJoin()
        assertEquals(1, admission.reservedCount())
        val replacement = admission.reserve()
        held.close()
        withTimeout(1_000) { replacement.use { it.activate() } }
        assertEquals(0, admission.reservedCount())
    }
}
