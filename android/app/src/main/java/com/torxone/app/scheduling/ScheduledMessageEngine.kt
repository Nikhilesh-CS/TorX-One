package com.torxone.app.scheduling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

interface ScheduledWorkScheduler {
    fun enqueue(value: ScheduledMessageEntity)
    fun cancel(id: String, generation: Long)
}

class PermanentScheduleException(message: String) : IllegalStateException(message)
enum class ScheduledRunResult { COMPLETE, RETRY }

/** Serializes UI edits and worker sends; SQL generation checks reject obsolete/replaced work. */
class ScheduledMessageEngine(
    private val dao: SchedulingDao,
    private val work: ScheduledWorkScheduler,
    private val ready: suspend () -> Boolean,
    private val committed: suspend (ScheduledMessageEntity) -> Boolean,
    private val send: suspend (ScheduledMessageEntity) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    fun observe(conversation: String) = dao.observe(conversation)

    suspend fun create(conversation: String, owner: String, text: String, at: Long, reply: String?): String = mutex.withLock {
        val time = now()
        SchedulingPolicy.validate(text, at, time)
        require(owner.isNotBlank()) { "Unlock your identity first" }
        val value = ScheduledMessageEntity(UUID.randomUUID().toString(), conversation, owner, text,
            reply, at, createdAt = time, updatedAt = time)
        dao.insert(value)
        // The Room row is the accepted intent. A scheduler failure must not report that the
        // creation failed: a user retry would create a second intent. Recovery reconciles it.
        trySchedule { work.enqueue(value) }
        value.scheduleId
    }

    suspend fun edit(id: String, generation: Long, text: String, at: Long, reply: String?) = mutex.withLock {
        SchedulingPolicy.validate(text, at, now())
        require(dao.edit(id, generation, text, reply, at, now()) == 1) { "Message has already started sending or changed" }
        trySchedule { work.cancel(id, generation) }
        dao.get(id)?.let { value -> trySchedule { work.enqueue(value) } }
    }

    suspend fun cancel(id: String, generation: Long) = mutex.withLock {
        require(dao.cancel(id, generation, now()) == 1) { "Message has already started sending or changed" }
        // Obsolete work is harmless even if cancellation fails: generation/state CAS rejects it.
        trySchedule { work.cancel(id, generation) }
    }

    /** Repairs a crash between the Room write and enqueue. WorkManager persists jobs across reboot. */
    suspend fun recover() = mutex.withLock {
        for (row in dao.recoverable()) trySchedule { work.enqueue(row) }
    }

    private inline fun trySchedule(operation: () -> Unit) {
        try { operation() } catch (_: Exception) {
            // Scheduling is reconciled from durable Room rows on startup/unlock. Preserve the
            // success of the already committed UI operation and continue reconciling other rows.
        }
    }

    suspend fun run(id: String, generation: Long): ScheduledRunResult = mutex.withLock {
        val row = dao.get(id) ?: return@withLock ScheduledRunResult.COMPLETE
        if (row.generation != generation || row.state !in setOf(ScheduledMessageState.PENDING, ScheduledMessageState.SENDING))
            return@withLock ScheduledRunResult.COMPLETE
        if (!ready()) return@withLock ScheduledRunResult.RETRY
        // If send committed before process death, marking complete must not consume another ratchet key.
        if (committed(row)) {
            dao.complete(id, generation, now())
            return@withLock ScheduledRunResult.COMPLETE
        }
        if (row.scheduledAt > now()) return@withLock ScheduledRunResult.RETRY
        if (dao.claim(id, generation, now(), now() + SchedulingPolicy.LEASE_MILLIS) != 1)
            return@withLock ScheduledRunResult.RETRY
        try {
            send(row)
            check(committed(row)) { "Scheduled send did not persist a message" }
            dao.complete(id, generation, now())
            ScheduledRunResult.COMPLETE
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (committed(row)) dao.complete(id, generation, now())
                else dao.release(id, generation, ScheduledMessageState.PENDING, "Waiting to retry", now())
            }
            throw e
        } catch (e: PermanentScheduleException) {
            dao.release(id, generation, ScheduledMessageState.FAILED, e.message ?: "Chat unavailable", now())
            ScheduledRunResult.COMPLETE
        } catch (_: Exception) {
            // A transport error after an atomic commit is still a successful queue operation.
            if (committed(row)) {
                dao.complete(id, generation, now())
                ScheduledRunResult.COMPLETE
            } else {
                dao.release(id, generation, ScheduledMessageState.PENDING, "Could not queue yet; waiting to retry", now())
                ScheduledRunResult.RETRY
            }
        }
    }
}
