package com.torxone.app.connection

import android.util.Log
import com.torxone.app.agent.ConnectionQueueDao
import com.torxone.app.agent.ConnectionQueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * TorX One 2.0 — Connection Layer
 *
 * Implements the SimpleX-inspired pairwise connection model:
 * 1. Pairwise only: No global user addresses or phone numbers.
 * 2. Unidirectional isolated queues:
 *    - sendQueueId: A -> B unidirectional delivery queue
 *    - recvQueueId: B -> A unidirectional delivery queue
 * 3. Queue rotation: Periodically or on-demand rotate queue IDs for forward privacy
 *    and untraceability across transports.
 * 4. Monotonic sequence tracking for anti-replay and message ordering.
 */
class ConnectionManager(
    private val connectionQueueDao: ConnectionQueueDao
) {
    companion object {
        private const val TAG = "ConnectionManager"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_SUSPENDED = "SUSPENDED"
        const val STATE_CLOSED = "CLOSED"
    }

    /**
     * Get an existing connection for the given remote party key, or create a new one.
     */
    suspend fun getOrCreateConnection(
        remotePartyKey: String,
        localPartyKey: String = ""
    ): ConnectionQueueEntity = withContext(Dispatchers.IO) {
        val normalizedRemote = remotePartyKey.trim().lowercase()
        val existing = connectionQueueDao.getByRemoteKey(normalizedRemote)
        if (existing != null) {
            connectionQueueDao.touchActive(existing.connectionId, System.currentTimeMillis())
            return@withContext existing
        }

        val now = System.currentTimeMillis()
        val connectionId = UUID.randomUUID().toString()
        val newConnection = ConnectionQueueEntity(
            connectionId = connectionId,
            localPartyKey = localPartyKey.trim().lowercase(),
            remotePartyKey = normalizedRemote,
            sendQueueId = UUID.randomUUID().toString(),
            recvQueueId = UUID.randomUUID().toString(),
            lastSendSeq = 0,
            lastRecvSeq = 0,
            state = STATE_ACTIVE,
            createdAt = now,
            lastActiveAt = now
        )
        connectionQueueDao.upsert(newConnection)
        Log.i(TAG, "[CONNECTION] Created new connection $connectionId for $normalizedRemote")
        newConnection
    }

    /**
     * Look up a connection by its unique connection ID.
     */
    suspend fun getConnectionById(connectionId: String): ConnectionQueueEntity? = withContext(Dispatchers.IO) {
        connectionQueueDao.getById(connectionId)
    }

    /**
     * Look up a connection by remote party signing key.
     */
    suspend fun getConnectionByRemoteKey(remotePartyKey: String): ConnectionQueueEntity? = withContext(Dispatchers.IO) {
        connectionQueueDao.getByRemoteKey(remotePartyKey.trim().lowercase())
    }

    /**
     * Rotate the send queue ID for a connection.
     * Generates a fresh queue identifier to prevent traffic correlation over time.
     * Returns the new sendQueueId.
     */
    suspend fun rotateSendQueue(connectionId: String): String = withContext(Dispatchers.IO) {
        val newQueueId = UUID.randomUUID().toString()
        connectionQueueDao.updateSendQueueId(connectionId, newQueueId, System.currentTimeMillis())
        Log.i(TAG, "[ROTATE] Send queue for $connectionId rotated to $newQueueId")
        newQueueId
    }

    /**
     * Rotate the receive queue ID for a connection.
     * Returns the new recvQueueId.
     */
    suspend fun rotateRecvQueue(connectionId: String): String = withContext(Dispatchers.IO) {
        val newQueueId = UUID.randomUUID().toString()
        connectionQueueDao.updateRecvQueueId(connectionId, newQueueId, System.currentTimeMillis())
        Log.i(TAG, "[ROTATE] Recv queue for $connectionId rotated to $newQueueId")
        newQueueId
    }

    /**
     * Advance the next send sequence number for an outgoing envelope.
     * Returns the incremented sequence number.
     */
    suspend fun nextSendSequence(connectionId: String): Long = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId)
            ?: throw IllegalStateException("Connection $connectionId not found")
        val nextSeq = conn.lastSendSeq + 1
        connectionQueueDao.updateSendSeq(connectionId, nextSeq, System.currentTimeMillis())
        nextSeq
    }

    /**
     * Validate an incoming sequence number against the last received sequence.
     * Ensures monotonic sequence progression and flags potential replays or gaps.
     */
    suspend fun validateAndRecordRecvSequence(
        connectionId: String,
        incomingSeq: Long
    ): SequenceValidationResult = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId)
            ?: return@withContext SequenceValidationResult.Invalid("Connection not found")

        if (incomingSeq <= conn.lastRecvSeq) {
            Log.w(TAG, "[SEQ] Potential replay or out-of-order: incoming=$incomingSeq, last=${conn.lastRecvSeq}")
            return@withContext SequenceValidationResult.Replay(conn.lastRecvSeq, incomingSeq)
        }

        val hasGap = (incomingSeq - conn.lastRecvSeq) > 1
        connectionQueueDao.updateRecvSeq(connectionId, incomingSeq, System.currentTimeMillis())

        if (hasGap) {
            Log.w(TAG, "[SEQ] Sequence gap detected: previous=${conn.lastRecvSeq}, incoming=$incomingSeq")
            SequenceValidationResult.ValidWithGap(conn.lastRecvSeq, incomingSeq)
        } else {
            SequenceValidationResult.Valid(incomingSeq)
        }
    }

    /**
     * Update connection state (ACTIVE, SUSPENDED, CLOSED).
     */
    suspend fun updateConnectionState(connectionId: String, state: String) = withContext(Dispatchers.IO) {
        connectionQueueDao.updateState(connectionId, state)
        Log.d(TAG, "[STATE] Connection $connectionId → $state")
    }

    /**
     * Get all active connections.
     */
    suspend fun getActiveConnections(): List<ConnectionQueueEntity> = withContext(Dispatchers.IO) {
        connectionQueueDao.getActiveConnections()
    }

    /**
     * Observe all connections in Room.
     */
    fun observeAll(): Flow<List<ConnectionQueueEntity>> {
        return connectionQueueDao.observeAll()
    }

    /**
     * Delete a connection.
     */
    suspend fun deleteConnection(connectionId: String) = withContext(Dispatchers.IO) {
        connectionQueueDao.delete(connectionId)
        Log.i(TAG, "[DELETE] Connection $connectionId deleted")
    }

    /**
     * Encode a queue rotation notification frame to inform the remote peer of a new queue ID.
     */
    fun encodeQueueRotationNotice(
        connectionId: String,
        isSendQueue: Boolean,
        newQueueId: String,
        fromKey: String,
        toKey: String
    ): String {
        return JSONObject().apply {
            put("type", "queue_rotate")
            put("connId", connectionId)
            put("queueRole", if (isSendQueue) "send" else "recv")
            put("newQueueId", newQueueId)
            put("from", fromKey)
            put("to", toKey)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    /**
     * Handle a queue rotation notice received from the remote peer.
     * When remote rotates their send queue, it becomes our receive queue, and vice-versa.
     */
    suspend fun handleQueueRotationNotice(json: JSONObject) = withContext(Dispatchers.IO) {
        val connId = json.optString("connId", "")
        val role = json.optString("queueRole", "")
        val newQueueId = json.optString("newQueueId", "")
        val fromKey = json.optString("from", "").trim().lowercase()

        val conn = if (connId.isNotBlank()) {
            connectionQueueDao.getById(connId)
        } else if (fromKey.isNotBlank()) {
            connectionQueueDao.getByRemoteKey(fromKey)
        } else null

        if (conn != null && newQueueId.isNotBlank()) {
            // If the peer rotated their send queue, that maps to our receive queue
            if (role == "send") {
                connectionQueueDao.updateRecvQueueId(conn.connectionId, newQueueId, System.currentTimeMillis())
                Log.i(TAG, "[ROTATE] Peer $fromKey rotated send queue → our recvQueueId updated to $newQueueId")
            } else if (role == "recv") {
                connectionQueueDao.updateSendQueueId(conn.connectionId, newQueueId, System.currentTimeMillis())
                Log.i(TAG, "[ROTATE] Peer $fromKey rotated recv queue → our sendQueueId updated to $newQueueId")
            }
        }
    }
}

/** Result of sequence validation */
sealed class SequenceValidationResult {
    data class Valid(val sequence: Long) : SequenceValidationResult()
    data class ValidWithGap(val expected: Long, val received: Long) : SequenceValidationResult()
    data class Replay(val lastReceived: Long, val received: Long) : SequenceValidationResult()
    data class Invalid(val reason: String) : SequenceValidationResult()
}
