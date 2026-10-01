package com.torxone.app.scheduling

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SchedulingDao {
    @Insert suspend fun insert(value: ScheduledMessageEntity)
    @Query("SELECT * FROM scheduled_messages WHERE scheduleId = :id")
    suspend fun get(id: String): ScheduledMessageEntity?
    @Query("SELECT * FROM scheduled_messages WHERE conversationId = :conversationId ORDER BY scheduledAt ASC")
    fun observe(conversationId: String): Flow<List<ScheduledMessageEntity>>
    @Query("SELECT * FROM scheduled_messages WHERE state IN ('PENDING','SENDING')")
    suspend fun recoverable(): List<ScheduledMessageEntity>
    @Query("UPDATE scheduled_messages SET draftPayload = :text, replyToMessageId = :reply, scheduledAt = :at, generation = generation + 1, state = 'PENDING', attempts = 0, lastError = NULL, updatedAt = :now WHERE scheduleId = :id AND generation = :generation AND state IN ('PENDING','FAILED')")
    suspend fun edit(id: String, generation: Long, text: String, reply: String?, at: Long, now: Long): Int
    @Query("UPDATE scheduled_messages SET state = 'CANCELED', draftPayload = '', replyToMessageId = NULL, generation = generation + 1, lastError = NULL, updatedAt = :now WHERE scheduleId = :id AND generation = :generation AND state IN ('PENDING','FAILED')")
    suspend fun cancel(id: String, generation: Long, now: Long): Int
    @Query("UPDATE scheduled_messages SET state = 'SENDING', leaseUntil = :lease, attempts = attempts + 1, lastError = NULL, updatedAt = :now WHERE scheduleId = :id AND generation = :generation AND scheduledAt <= :now AND (state = 'PENDING' OR (state = 'SENDING' AND leaseUntil <= :now))")
    suspend fun claim(id: String, generation: Long, now: Long, lease: Long): Int
    @Query("UPDATE scheduled_messages SET state = 'SENT', draftPayload = '', replyToMessageId = NULL, leaseUntil = 0, lastError = NULL, updatedAt = :now WHERE scheduleId = :id AND generation = :generation AND state IN ('PENDING','SENDING')")
    suspend fun complete(id: String, generation: Long, now: Long): Int
    @Query("UPDATE scheduled_messages SET state = :state, leaseUntil = 0, lastError = :error, updatedAt = :now WHERE scheduleId = :id AND generation = :generation AND state = 'SENDING'")
    suspend fun release(id: String, generation: Long, state: String, error: String, now: Long): Int
}
