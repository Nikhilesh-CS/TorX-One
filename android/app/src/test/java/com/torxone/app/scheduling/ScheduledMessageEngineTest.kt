package com.torxone.app.scheduling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScheduledMessageEngineTest {
    private class Store : SchedulingDao {
        val rows = linkedMapOf<String, ScheduledMessageEntity>()
        override suspend fun insert(value: ScheduledMessageEntity) { check(rows.putIfAbsent(value.scheduleId, value) == null) }
        override suspend fun get(id: String) = rows[id]
        override fun observe(conversationId: String): Flow<List<ScheduledMessageEntity>> = MutableStateFlow(rows.values.filter { it.conversationId == conversationId })
        override suspend fun recoverable() = rows.values.filter { it.state in listOf("PENDING", "SENDING") }
        override suspend fun edit(id: String, generation: Long, text: String, reply: String?, at: Long, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.generation != generation || row.state !in listOf("PENDING", "FAILED")) return 0
            rows[id] = row.copy(draftPayload = text, replyToMessageId = reply, scheduledAt = at, generation = generation + 1,
                state = "PENDING", attempts = 0, lastError = null, updatedAt = now)
            return 1
        }
        override suspend fun cancel(id: String, generation: Long, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.generation != generation || row.state !in listOf("PENDING", "FAILED")) return 0
            rows[id] = row.copy(state = "CANCELED", draftPayload = "", replyToMessageId = null, generation = generation + 1)
            return 1
        }
        override suspend fun claim(id: String, generation: Long, now: Long, lease: Long): Int {
            val row = rows[id] ?: return 0
            if (row.generation != generation || row.scheduledAt > now ||
                !(row.state == "PENDING" || row.state == "SENDING" && row.leaseUntil <= now)) return 0
            rows[id] = row.copy(state = "SENDING", leaseUntil = lease, attempts = row.attempts + 1)
            return 1
        }
        override suspend fun complete(id: String, generation: Long, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.generation != generation || row.state !in listOf("PENDING", "SENDING")) return 0
            rows[id] = row.copy(state = "SENT", draftPayload = "", replyToMessageId = null, leaseUntil = 0)
            return 1
        }
        override suspend fun release(id: String, generation: Long, state: String, error: String, now: Long): Int {
            val row = rows[id] ?: return 0
            if (row.generation != generation || row.state != "SENDING") return 0
            rows[id] = row.copy(state = state, leaseUntil = 0, lastError = error)
            return 1
        }
    }
    private class Work : ScheduledWorkScheduler {
        val enqueued = mutableListOf<ScheduledMessageEntity>()
        val canceled = mutableListOf<Pair<String, Long>>()
        var failEnqueue = false
        var failCancel = false
        override fun enqueue(value: ScheduledMessageEntity) {
            if (failEnqueue) error("Scheduler unavailable")
            enqueued += value
        }
        override fun cancel(id: String, generation: Long) {
            if (failCancel) error("Scheduler unavailable")
            canceled += id to generation
        }
    }
    private class Fixture {
        var clock = 1000L
        var unlocked = true
        var failure: Exception? = null
        var sent = 0
        val committed = mutableSetOf<String>()
        val store = Store()
        val work = Work()
        val engine = ScheduledMessageEngine(store, work, { unlocked }, { it.scheduleId in committed }, {
            failure?.let { throw it }
            sent++; committed += it.scheduleId
        }, { clock })
        suspend fun create() = engine.create("chat", "owner", "later", 2000, "reply")
    }

    @Test fun onlyEncryptsWhenDueAndCommitsExactlyOnce() = runTest {
        val f = Fixture(); val id = f.create()
        assertEquals(ScheduledRunResult.RETRY, f.engine.run(id, 1)); assertEquals(0, f.sent)
        f.clock = 2000
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1))
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1))
        assertEquals(1, f.sent); assertEquals("SENT", f.store.get(id)?.state)
        assertEquals("", f.store.get(id)?.draftPayload)
    }
    @Test fun lockedAppDefersWithoutClaimOrEncryption() = runTest {
        val f = Fixture(); val id = f.create(); f.clock = 2000; f.unlocked = false
        assertEquals(ScheduledRunResult.RETRY, f.engine.run(id, 1))
        assertEquals(0, f.sent); assertEquals(0, f.store.get(id)?.attempts)
        f.unlocked = true; f.engine.run(id, 1); assertEquals(1, f.sent)
    }
    @Test fun oldWorkerAfterRescheduleCannotSendOldText() = runTest {
        val f = Fixture(); val id = f.create()
        f.engine.edit(id, 1, "edited", 4000, null); f.clock = 4000
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1)); assertEquals(0, f.sent)
        assertEquals("edited", f.store.get(id)?.draftPayload)
        f.engine.run(id, 2); assertEquals(1, f.sent)
    }
    @Test fun canceledWorkerCannotSendAndPayloadClears() = runTest {
        val f = Fixture(); val id = f.create(); f.engine.cancel(id, 1); f.clock = 5000
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1)); assertEquals(0, f.sent)
        assertEquals("", f.store.get(id)?.draftPayload)
    }
    @Test fun crashAfterMessageCommitDoesNotEncryptAgainEvenWithLiveLease() = runTest {
        val f = Fixture(); val id = f.create(); f.clock = 2000
        f.store.claim(id, 1, 2000, 999999); f.committed += id
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1))
        assertEquals(0, f.sent); assertEquals("SENT", f.store.get(id)?.state)
    }
    @Test fun crashBeforeCommitWaitsForLeaseThenRetries() = runTest {
        val f = Fixture(); val id = f.create(); f.clock = 2000
        f.store.claim(id, 1, 2000, 3000)
        assertEquals(ScheduledRunResult.RETRY, f.engine.run(id, 1)); assertEquals(0, f.sent)
        f.clock = 3000; f.engine.run(id, 1); assertEquals(1, f.sent)
    }
    @Test fun transientFailureKeepsPayloadAndRetriesSameLogicalId() = runTest {
        val f = Fixture(); val id = f.create(); f.clock = 2000; f.failure = IllegalStateException("locked")
        assertEquals(ScheduledRunResult.RETRY, f.engine.run(id, 1)); assertEquals("later", f.store.get(id)?.draftPayload)
        f.failure = null; f.engine.run(id, 1); assertEquals(1, f.sent)
        assertEquals(SchedulingPolicy.messageId(id), SchedulingPolicy.messageId(id))
    }
    @Test fun membershipRemovalFailsVisiblyAndCanBeCanceled() = runTest {
        val f = Fixture(); val id = f.create(); f.clock = 2000
        f.failure = PermanentScheduleException("You are no longer a group member")
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1)); assertEquals("FAILED", f.store.get(id)?.state)
        assertEquals("You are no longer a group member", f.store.get(id)?.lastError)
        f.engine.cancel(id, 1); assertEquals("CANCELED", f.store.get(id)?.state)
    }
    @Test fun recoveryRepairsMissingWorkAndPreservesDuePayload() = runTest {
        val f = Fixture(); val id = f.create(); f.work.enqueued.clear(); f.engine.recover()
        assertEquals(id, f.work.enqueued.single().scheduleId)
        assertEquals("later", f.store.get(id)?.draftPayload); assertEquals(0, f.sent)
    }
    @Test fun scheduledMessageIdIsFreshAndStableAcrossRetry() {
        assertNotEquals("schedule", SchedulingPolicy.messageId("schedule"))
        assertEquals(SchedulingPolicy.messageId("schedule"), SchedulingPolicy.messageId("schedule"))
        assertNotEquals(SchedulingPolicy.messageId("schedule"), SchedulingPolicy.messageId("other"))
    }
    @Test fun schedulerFailureAfterCreateStillAcceptsExactlyOneDurableIntent() = runTest {
        val f = Fixture(); f.work.failEnqueue = true
        val id = f.create()
        assertEquals(1, f.store.rows.size)
        assertEquals("later", f.store.get(id)?.draftPayload)
        assertTrue(f.work.enqueued.isEmpty())
        f.work.failEnqueue = false; f.engine.recover()
        assertEquals(id, f.work.enqueued.single().scheduleId)
        assertEquals(1, f.store.rows.size)
    }
    @Test fun schedulerFailuresAfterEditAndCancelDoNotReportDatabaseFailure() = runTest {
        val f = Fixture(); val id = f.create()
        f.work.failCancel = true; f.work.failEnqueue = true
        f.engine.edit(id, 1, "edited", 4000, null)
        assertEquals(2L, f.store.get(id)?.generation)
        assertEquals("edited", f.store.get(id)?.draftPayload)
        f.clock = 4000
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 1))
        assertEquals(0, f.sent)
        f.engine.cancel(id, 2)
        assertEquals("CANCELED", f.store.get(id)?.state)
        assertEquals("", f.store.get(id)?.draftPayload)
        assertEquals(ScheduledRunResult.COMPLETE, f.engine.run(id, 2))
        assertEquals(0, f.sent)
    }
    @Test(expected = IllegalArgumentException::class) fun pastScheduleRejected() { SchedulingPolicy.validate("hi", 1000, 1000) }
    @Test(expected = IllegalArgumentException::class) fun oversizedScheduleRejected() { SchedulingPolicy.validate("a".repeat(65537), 2000, 1000) }
}
