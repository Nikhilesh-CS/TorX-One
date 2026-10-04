package com.torxone.app.transport.tor

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TorReadinessGateTest {
    @Test fun offOrErrorRejectsOldAndImmediatelyNewSamplesUntilFreshLifecycle() {
        for (loss in listOf("OFF", "ERROR", "STARTING", "STOPPING")) {
            val gate = TorReadinessGate()
            var state = "Starting"
            gate.reset(true)
            val oldSample = requireNotNull(gate.ticket())
            gate.reset(false) { state = loss }
            assertFalse(gate.publish(oldSample) { state = "Ready" })
            assertNull("$loss must forbid a new probe against the same stopped service", gate.ticket())
            assertEquals(loss, state)
            gate.reset(true) // Explicit ON, a new service connection, or user start permits a fresh sample.
            val freshSample = requireNotNull(gate.ticket())
            assertFalse(gate.publish(oldSample) { state = "Ready" })
            assertTrue(gate.publish(freshSample) { state = "Ready" })
            assertEquals("Ready", state)
        }
    }

    @Test fun reconnectRejectsPreviousServiceSampleEvenWhenProbesAreAllowed() {
        val gate = TorReadinessGate()
        gate.reset(true)
        val previousService = requireNotNull(gate.ticket())
        gate.reset(true)
        var publications = 0
        assertFalse(gate.publish(previousService) { publications++ })
        assertTrue(gate.publish(requireNotNull(gate.ticket())) { publications++ })
        assertEquals(1, publications)
    }

    @Test fun lifecycleLossCannotFinishBeforeAnAlreadyPublishingSampleAndThenBeOverwritten() = runBlocking {
        val gate = TorReadinessGate()
        gate.reset(true)
        val ticket = requireNotNull(gate.ticket())
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        var state = "Starting"
        val publishing = async(Dispatchers.IO) {
            gate.publish(ticket) {
                entered.complete(Unit)
                check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                state = "Ready"
            }
        }
        try {
            withTimeout(1_000) { entered.await() }
            val loss = async(Dispatchers.IO) { gate.reset(false) { state = "Stopped" } }
            release.countDown()
            withTimeout(1_000) { publishing.await(); loss.await() }
            assertEquals("Stopped", state)
            assertFalse(gate.publish(ticket) { state = "Ready" })
        } finally { release.countDown(); publishing.cancel() }
    }
}
