package com.torxone.app.security.session

import androidx.room.*

/**
 * Independent Room entity storing active session state for a contact.
 * Kept completely decoupled from ContactEntity to isolate security state.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val contactKey: String, // Contact's signing public key hex
    val sessionId: String,
    val rootKeyHex: String,
    val sendChainKeyHex: String,
    val recvChainKeyHex: String,
    val localRatchetPubHex: String,
    val localRatchetSecHex: String,
    val remoteRatchetPubHex: String,
    val sendMsgCount: Int = 0,
    val recvMsgCount: Int = 0,
    val previousSendCount: Int = 0,
    val lastActiveAt: Long = System.currentTimeMillis(),
    val state: String = "ACTIVE" // ACTIVE, NEGOTIATING, EXPIRED
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey LIMIT 1")
    suspend fun getSession(contactKey: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey LIMIT 1")
    fun getSessionSync(contactKey: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE contactKey = :contactKey")
    suspend fun deleteSession(contactKey: String)

    @Query("SELECT * FROM sessions WHERE state = 'ACTIVE'")
    suspend fun getActiveSessions(): List<SessionEntity>
}

/**
 * Replay protection ledger: tracks processed message sequence numbers per session.
 */
@Entity(
    tableName = "session_replays",
    primaryKeys = ["sessionId", "msgNum"],
    indices = [Index(value = ["receivedAt"])]
)
data class SessionReplayEntity(
    val sessionId: String,
    val msgNum: Int,
    val receivedAt: Long
)

@Dao
interface SessionReplayDao {
    @Query("SELECT COUNT(*) FROM session_replays WHERE sessionId = :sessionId AND msgNum = :msgNum")
    suspend fun isProcessed(sessionId: String, msgNum: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markProcessed(replay: SessionReplayEntity): Long

    @Query("DELETE FROM session_replays WHERE receivedAt < :cutoffMs")
    suspend fun pruneOldRecords(cutoffMs: Long)

    @Query("DELETE FROM session_replays WHERE sessionId = :sessionId")
    suspend fun clearSessionReplays(sessionId: String)
}
