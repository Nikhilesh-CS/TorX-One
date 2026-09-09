package com.torxone.app.security.session

import androidx.room.*

/**
 * Independent Room entity storing active session state for a contact.
 * Kept completely decoupled from ContactEntity to isolate security state.
 */
@Entity(
    tableName = "sessions",
    primaryKeys = ["contactKey", "sessionId"],
    indices = [Index(value = ["contactKey", "lastActiveAt"])]
)
data class SessionEntity(
    val contactKey: String, // Contact's signing public key hex
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
    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey AND sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(contactKey: String, sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey ORDER BY CASE WHEN state = 'ACTIVE' THEN 0 ELSE 1 END, lastActiveAt DESC LIMIT 1")
    suspend fun getSession(contactKey: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey ORDER BY CASE WHEN state = 'ACTIVE' THEN 0 ELSE 1 END, lastActiveAt DESC LIMIT 1")
    fun getSessionSync(contactKey: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE contactKey = :contactKey")
    suspend fun getAllSessionsForContact(contactKey: String): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE contactKey = :contactKey")
    suspend fun deleteSession(contactKey: String)

    @Query("DELETE FROM sessions WHERE contactKey = :contactKey AND sessionId != :keepSessionId")
    suspend fun pruneOldSessions(contactKey: String, keepSessionId: String)

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

    @Query("DELETE FROM session_replays WHERE sessionId NOT IN (SELECT sessionId FROM sessions)")
    suspend fun clearOrphanedReplays()
}

/**
 * Skipped message key store for Double Ratchet out-of-order message delivery.
 * When msgNum > expected, skipped message keys are stored temporarily until late
 * packets arrive, or pruned after expiry/threshold.
 */
@Entity(
    tableName = "session_skipped_keys",
    primaryKeys = ["sessionId", "ratchetPubHex", "msgNum"],
    indices = [Index(value = ["createdAt"])]
)
data class SkippedMessageKeyEntity(
    val sessionId: String,
    val ratchetPubHex: String,
    val msgNum: Int,
    val messageKeyHex: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SkippedMessageKeyDao {
    @Query("SELECT * FROM session_skipped_keys WHERE sessionId = :sessionId AND ratchetPubHex = :ratchetPubHex AND msgNum = :msgNum LIMIT 1")
    suspend fun getSkippedKey(sessionId: String, ratchetPubHex: String, msgNum: Int): SkippedMessageKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkippedKey(entity: SkippedMessageKeyEntity)

    @Query("DELETE FROM session_skipped_keys WHERE sessionId = :sessionId AND ratchetPubHex = :ratchetPubHex AND msgNum = :msgNum")
    suspend fun deleteSkippedKey(sessionId: String, ratchetPubHex: String, msgNum: Int)

    @Query("SELECT COUNT(*) FROM session_skipped_keys WHERE sessionId = :sessionId")
    suspend fun getSkippedKeyCount(sessionId: String): Int

    @Query("DELETE FROM session_skipped_keys WHERE createdAt < :cutoffMs")
    suspend fun pruneExpiredKeys(cutoffMs: Long)

    @Query("DELETE FROM session_skipped_keys WHERE sessionId = :sessionId")
    suspend fun clearSessionSkippedKeys(sessionId: String)
}
