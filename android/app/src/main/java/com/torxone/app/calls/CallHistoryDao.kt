package com.torxone.app.calls

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for call history persistence.
 * Call signaling is ephemeral — only the final call record is persisted.
 */
@Dao
interface CallHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallHistoryEntity)

    @Query("SELECT * FROM call_history WHERE conversationId = :conversationId ORDER BY startedAt DESC")
    fun observeByConversation(conversationId: String): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CallHistoryEntity>>

    @Query("SELECT * FROM call_history WHERE callId = :callId")
    suspend fun getById(callId: String): CallHistoryEntity?

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallHistoryEntity>>

    @Query("DELETE FROM call_history WHERE callId = :callId")
    suspend fun delete(callId: String)

    @Query("DELETE FROM call_history WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
