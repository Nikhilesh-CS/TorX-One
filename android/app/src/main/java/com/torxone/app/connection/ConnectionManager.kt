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
 * 1. Identity != Connection != Queue != Transport Address
 * 2. Unidirectional isolated queues:
 *    - sendQueueId: A -> B unidirectional delivery queue
 *    - recvQueueId: B -> A unidirectional delivery queue
 * 3. 6-stage queue rotation state machine:
 *    ACTIVE -> ROTATION_PROPOSED -> ROTATION_AUTHENTICATED -> NEW_QUEUE_ACTIVE -> OLD_QUEUE_DRAINING -> CLOSED
 * 4. Safe sequence advancement: validation does NOT mutate SQLite cursor. Sequence commits only
 *    after successful Double Ratchet decryption and Room persistence.
 */
class ConnectionManager(
    private val connectionQueueDao: ConnectionQueueDao
) {
    companion object {
        private const val TAG = "ConnectionManager"

        // Connection states
        const val STATE_INITIATING = "INITIATING"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_SUSPENDED = "SUSPENDED"
        const val STATE_CLOSED = "CLOSED"

        // 6-Stage Queue Rotation States
        const val ROTATION_ACTIVE = "ACTIVE"
        const val ROTATION_PROPOSED = "ROTATION_PROPOSED"
        const val ROTATION_AUTHENTICATED = "ROTATION_AUTHENTICATED"
        const val ROTATION_NEW_QUEUE_ACTIVE = "NEW_QUEUE_ACTIVE"
        const val ROTATION_OLD_QUEUE_DRAINING = "OLD_QUEUE_DRAINING"
        const val ROTATION_CLOSED = "CLOSED"

        // Default grace period for draining old queue (5 minutes)
        const val DEFAULT_DRAIN_GRACE_PERIOD_MS = 300_000L
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
            rotationState = ROTATION_ACTIVE,
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
     * Look up the most recent active connection by remote party signing key.
     */
    suspend fun getConnectionByRemoteKey(remotePartyKey: String): ConnectionQueueEntity? = withContext(Dispatchers.IO) {
        connectionQueueDao.getByRemoteKey(remotePartyKey.trim().lowercase())
    }

    /**
     * Look up all connections by remote party signing key.
     */
    suspend fun getAllConnectionsByRemoteKey(remotePartyKey: String): List<ConnectionQueueEntity> = withContext(Dispatchers.IO) {
        connectionQueueDao.getAllByRemoteKey(remotePartyKey.trim().lowercase())
    }

    /**
     * Look up connection by any associated queue ID (active or pending).
     */
    suspend fun getConnectionByQueueId(queueId: String): ConnectionQueueEntity? = withContext(Dispatchers.IO) {
        connectionQueueDao.getByQueueId(queueId)
    }

    // ─────────────────── 6-Stage Queue Rotation ───────────────────

    /**
     * Stage 2: Propose queue rotation.
     * Generates a new pending send queue ID and sets rotationState to ROTATION_PROPOSED.
     */
    suspend fun proposeQueueRotation(connectionId: String): QueueRotationProposal = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId)
            ?: throw IllegalStateException("Connection $connectionId not found")
        val newSendQueueId = UUID.randomUUID().toString()
        connectionQueueDao.updateRotationState(
            connectionId = connectionId,
            rotationState = ROTATION_PROPOSED,
            pendingSend = newSendQueueId,
            pendingRecv = conn.pendingRecvQueueId,
            gracePeriod = null,
            now = System.currentTimeMillis()
        )
        Log.i(TAG, "[ROTATION] Propose rotation for $connectionId: proposed newSendQueueId=$newSendQueueId")
        QueueRotationProposal(connectionId, newSendQueueId)
    }

    /**
     * Stage 3: Authenticate incoming queue rotation proposal from peer.
     * Staged as pendingRecvQueueId and sets rotationState to ROTATION_AUTHENTICATED.
     */
    suspend fun authenticateQueueRotation(
        connectionId: String,
        peerProposedQueueId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId) ?: return@withContext false
        connectionQueueDao.updateRotationState(
            connectionId = connectionId,
            rotationState = ROTATION_AUTHENTICATED,
            pendingSend = conn.pendingSendQueueId,
            pendingRecv = peerProposedQueueId,
            gracePeriod = null,
            now = System.currentTimeMillis()
        )
        Log.i(TAG, "[ROTATION] Authenticated peer rotation for $connectionId: pendingRecvQueueId=$peerProposedQueueId")
        true
    }

    /**
     * Stage 4: Activate new queue.
     * Marks the state as NEW_QUEUE_ACTIVE so subsequent transmissions start utilizing the new queue IDs.
     */
    suspend fun activateNewQueue(connectionId: String): Boolean = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId) ?: return@withContext false
        connectionQueueDao.updateRotationState(
            connectionId = connectionId,
            rotationState = ROTATION_NEW_QUEUE_ACTIVE,
            pendingSend = conn.pendingSendQueueId,
            pendingRecv = conn.pendingRecvQueueId,
            gracePeriod = null,
            now = System.currentTimeMillis()
        )
        Log.i(TAG, "[ROTATION] Activated new queue for $connectionId")
        true
    }

    /**
     * Stage 5: Transition to draining window for the old queue.
     * Enters OLD_QUEUE_DRAINING with a grace period (e.g., 5 min) where both old and new queues are accepted.
     * The new pending queues become active, while the old queues are preserved as draining queues.
     */
    suspend fun drainOldQueue(
        connectionId: String,
        gracePeriodMs: Long = DEFAULT_DRAIN_GRACE_PERIOD_MS
    ): Boolean = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId) ?: return@withContext false
        val now = System.currentTimeMillis()
        val graceUntil = now + gracePeriodMs

        val newSend = conn.pendingSendQueueId ?: conn.sendQueueId
        val drainingSend = if (conn.pendingSendQueueId != null) conn.sendQueueId else null

        val newRecv = conn.pendingRecvQueueId ?: conn.recvQueueId
        val drainingRecv = if (conn.pendingRecvQueueId != null) conn.recvQueueId else null

        connectionQueueDao.updateDrainingState(
            connectionId = connectionId,
            activeSend = newSend,
            activeRecv = newRecv,
            rotationState = ROTATION_OLD_QUEUE_DRAINING,
            drainingSend = drainingSend,
            drainingRecv = drainingRecv,
            gracePeriod = graceUntil,
            now = now
        )
        Log.i(TAG, "[ROTATION] Draining old queue for $connectionId until $graceUntil")
        true
    }

    /**
     * Stage 6: Finalize rotation.
     * Clears draining queue IDs permanently and resets rotationState to ACTIVE.
     */
    suspend fun finalizeRotation(connectionId: String): Boolean = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId) ?: return@withContext false
        val finalSend = conn.sendQueueId
        val finalRecv = conn.recvQueueId

        connectionQueueDao.finalizeQueueRotation(
            connectionId = connectionId,
            newSendQueueId = finalSend,
            newRecvQueueId = finalRecv,
            now = System.currentTimeMillis()
        )
        Log.i(TAG, "[ROTATION] Finalized rotation for $connectionId: send=$finalSend, recv=$finalRecv")
        true
    }

    /**
     * Check if an inbound envelope queue ID is accepted by this connection.
     * Accepts:
     * - Active recvQueueId
     * - Pending recvQueueId (during authentication/active stages)
     * - Draining old queue if within the draining grace period
     */
    suspend fun isQueueAccepted(
        connectionId: String,
        queueId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        val conn = connectionQueueDao.getById(connectionId) ?: return@withContext false
        if (conn.recvQueueId == queueId) return@withContext true
        if (conn.pendingRecvQueueId == queueId) {
            if (conn.rotationState == ROTATION_OLD_QUEUE_DRAINING) {
                val grace = conn.rotationGracePeriodUntil ?: 0L
                return@withContext now <= grace
            }
            return@withContext true
        }
        false
    }

    /**
     * Rotate the send queue ID for a connection immediately (convenience helper).
     */
    suspend fun rotateSendQueue(connectionId: String): String = withContext(Dispatchers.IO) {
        val newQueueId = UUID.randomUUID().toString()
        connectionQueueDao.updateSendQueueId(connectionId, newQueueId, System.currentTimeMillis())
        Log.i(TAG, "[ROTATE] Send queue for $connectionId rotated to $newQueueId")
        newQueueId
    }

    /**
     * Rotate the receive queue ID for a connection immediately (convenience helper).
     */
    suspend fun rotateRecvQueue(connectionId: String): String = withContext(Dispatchers.IO) {
        val newQueueId = UUID.randomUUID().toString()
        connectionQueueDao.updateRecvQueueId(connectionId, newQueueId, System.currentTimeMillis())
        Log.i(TAG, "[ROTATE] Recv queue for $connectionId rotated to $newQueueId")
        newQueueId
    }

    // ─────────────────── Monotonic Sequence Handling ───────────────────

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
     * READ-ONLY: Does NOT mutate the database cursor!
     * Ensures monotonic sequence progression and flags potential replays or gaps.
     */
    suspend fun validateRecvSequence(
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
        if (hasGap) {
            Log.w(TAG, "[SEQ] Sequence gap detected: previous=${conn.lastRecvSeq}, incoming=$incomingSeq")
            SequenceValidationResult.ValidWithGap(conn.lastRecvSeq, incomingSeq)
        } else {
            SequenceValidationResult.Valid(incomingSeq)
        }
    }

    /**
     * Commit the receive sequence cursor AFTER successful decryption and Room persistence.
     * Advances lastRecvSeq and records the committed envelope hash.
     */
    suspend fun commitRecvSequence(
        connectionId: String,
        committedSeq: Long,
        envelopeHash: String? = null
    ) = withContext(Dispatchers.IO) {
        connectionQueueDao.commitRecvSeq(connectionId, committedSeq, envelopeHash, System.currentTimeMillis())
        Log.d(TAG, "[SEQ] Committed recvSeq=$committedSeq for $connectionId (hash=$envelopeHash)")
    }

    /**
     * Legacy helper: validates and immediately records.
     * @deprecated Use [validateRecvSequence] followed by [commitRecvSequence] upon persistence.
     */
    @Deprecated("Use validateRecvSequence followed by commitRecvSequence after persistence")
    suspend fun validateAndRecordRecvSequence(
        connectionId: String,
        incomingSeq: Long
    ): SequenceValidationResult = withContext(Dispatchers.IO) {
        val res = validateRecvSequence(connectionId, incomingSeq)
        if (res is SequenceValidationResult.Valid || res is SequenceValidationResult.ValidWithGap) {
            connectionQueueDao.updateRecvSeq(connectionId, incomingSeq, System.currentTimeMillis())
        }
        res
    }

    /**
     * Update connection state (ACTIVE, SUSPENDED, CLOSED).
     */
    suspend fun updateConnectionState(connectionId: String, state: String) = withContext(Dispatchers.IO) {
        connectionQueueDao.updateState(connectionId, state)
        Log.d(TAG, "[STATE] Connection $connectionId -> $state")
    }

    /**
     * Get all active connections.
     */
    suspend fun getActiveConnections(): List<ConnectionQueueEntity> = withContext(Dispatchers.IO) {
        connectionQueueDao.getActiveConnections()
    }

    /**
     * Get all currently valid receive queue IDs (including active and draining queues).
     */
    suspend fun getActiveRecvQueueIds(): Set<String> = withContext(Dispatchers.IO) {
        val active = connectionQueueDao.getActiveConnections()
        val queueIds = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        for (conn in active) {
            queueIds.add(conn.recvQueueId)
            if (!conn.pendingRecvQueueId.isNullOrBlank()) {
                if (conn.rotationState != ROTATION_OLD_QUEUE_DRAINING || now <= (conn.rotationGracePeriodUntil ?: 0L)) {
                    queueIds.add(conn.pendingRecvQueueId)
                }
            }
        }
        queueIds
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
            if (role == "send") {
                connectionQueueDao.updateRecvQueueId(conn.connectionId, newQueueId, System.currentTimeMillis())
                Log.i(TAG, "[ROTATE] Peer $fromKey rotated send queue -> our recvQueueId updated to $newQueueId")
            } else if (role == "recv") {
                connectionQueueDao.updateSendQueueId(conn.connectionId, newQueueId, System.currentTimeMillis())
                Log.i(TAG, "[ROTATE] Peer $fromKey rotated recv queue -> our sendQueueId updated to $newQueueId")
            }
        }
    }
}

data class QueueRotationProposal(
    val connectionId: String,
    val proposedSendQueueId: String
)

/** Result of sequence validation */
sealed class SequenceValidationResult {
    data class Valid(val sequence: Long) : SequenceValidationResult()
    data class ValidWithGap(val expected: Long, val received: Long) : SequenceValidationResult()
    data class Replay(val lastReceived: Long, val received: Long) : SequenceValidationResult()
    data class Invalid(val reason: String) : SequenceValidationResult()
}
