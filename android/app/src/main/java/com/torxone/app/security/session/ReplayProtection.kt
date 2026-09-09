package com.torxone.app.security.session

import android.util.Log

/**
 * First-class Replay Protection for TorX One Sessions.
 * Rejects duplicate, replayed, or out-of-order packets based on monotonic session sequence numbers.
 */
class ReplayProtection(private val replayDao: SessionReplayDao) {
    companion object {
        private const val TAG = "ReplayProtection"
        private const val RETENTION_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    /**
     * Checks if a message with [msgNum] in [sessionId] was already processed.
     * If not, atomically records it as processed.
     *
     * @return true if message is fresh and accepted, false if it is a replay (reject).
     */
    suspend fun checkAndMark(sessionId: String, msgNum: Int): Boolean {
        if (msgNum < 0) {
            Log.w(TAG, "[$sessionId] Rejected negative message sequence counter: $msgNum")
            return false
        }

        val alreadySeen = replayDao.isProcessed(sessionId, msgNum) > 0
        if (alreadySeen) {
            Log.w(TAG, "[$sessionId] REPLAY DETECTED: Counter #$msgNum was already processed. Dropping packet.")
            return false
        }

        val inserted = replayDao.markProcessed(
            SessionReplayEntity(
                sessionId = sessionId,
                msgNum = msgNum,
                receivedAt = System.currentTimeMillis()
            )
        )
        return inserted > 0
    }

    /**
     * Prunes expired replay records older than the retention window.
     */
    suspend fun pruneOldRecords() {
        val cutoff = System.currentTimeMillis() - RETENTION_WINDOW_MS
        replayDao.pruneOldRecords(cutoff)
    }

    /**
     * Clears all replay tracking records for a specific session ID.
     */
    suspend fun clearSession(sessionId: String) {
        replayDao.clearSessionReplays(sessionId)
    }

    /**
     * Purges orphaned replay records that belong to sessions no longer in the active sessions table.
     */
    suspend fun clearOrphanedReplays() {
        replayDao.clearOrphanedReplays()
    }
}

