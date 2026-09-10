package com.torxone.app.connection

import android.util.Log
import com.torxone.app.agent.ConnectionQueueDao
import com.torxone.app.agent.ConnectionQueueEntity
import com.torxone.app.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    private val sendSeqLocks = ConcurrentHashMap<String, Mutex>()

    // ─────────────────── Monotonic Sequence Handling ───────────────────

    /**
     * Advance the next send sequence number for an outgoing envelope.
     * Concurrency-safe: Protected by per-connection atomic Mutex to ensure sequence monotonicity without duplicates.
     * Returns the incremented sequence number.
     */
    suspend fun nextSendSequence(connectionId: String): Long = withContext(Dispatchers.IO) {
        val lock = sendSeqLocks.computeIfAbsent(connectionId) { Mutex() }
        lock.withLock {
            val conn = connectionQueueDao.getById(connectionId)
                ?: throw IllegalStateException("Connection $connectionId not found")
            val nextSeq = conn.lastSendSeq + 1
            connectionQueueDao.updateSendSeq(connectionId, nextSeq, System.currentTimeMillis())
            nextSeq
        }
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

    // ─────────────────── Cryptographically Authenticated Rotation ───────────────────

    /**
     * Create a cryptographically signed ROTATE_PROPOSE payload.
     * Signs: "ROTATE_PROPOSE:$connectionId:$oldQueueId:$newQueueId:$generation:$nonce:$timestamp"
     */
    fun createSignedRotationProposal(
        connectionId: String,
        oldQueueId: String,
        newQueueId: String,
        generation: Long,
        nonce: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        signingSecretKey: ByteArray
    ): String {
        val messageToSign = "ROTATE_PROPOSE:$connectionId:$oldQueueId:$newQueueId:$generation:$nonce:$timestamp"
        val signatureBytes = CryptoManager.sign(messageToSign.toByteArray(Charsets.UTF_8), signingSecretKey)
        val signatureHex = CryptoManager.toHex(signatureBytes)

        return JSONObject().apply {
            put("type", "QUEUE_ROTATE_PROPOSE")
            put("connectionId", connectionId)
            put("oldQueueId", oldQueueId)
            put("newQueueId", newQueueId)
            put("generation", generation)
            put("nonce", nonce)
            put("timestamp", timestamp)
            put("signature", signatureHex)
        }.toString()
    }

    /**
     * Create a cryptographically signed ROTATE_ACK payload.
     * Signs: "ROTATE_ACK:$connectionId:$oldQueueId:$newQueueId:$nonce:$timestamp"
     */
    fun createSignedRotationAck(
        connectionId: String,
        oldQueueId: String,
        newQueueId: String,
        nonce: String,
        timestamp: Long = System.currentTimeMillis(),
        signingSecretKey: ByteArray
    ): String {
        val messageToSign = "ROTATE_ACK:$connectionId:$oldQueueId:$newQueueId:$nonce:$timestamp"
        val signatureBytes = CryptoManager.sign(messageToSign.toByteArray(Charsets.UTF_8), signingSecretKey)
        val signatureHex = CryptoManager.toHex(signatureBytes)

        return JSONObject().apply {
            put("type", "QUEUE_ROTATE_ACK")
            put("connectionId", connectionId)
            put("oldQueueId", oldQueueId)
            put("newQueueId", newQueueId)
            put("nonce", nonce)
            put("timestamp", timestamp)
            put("signature", signatureHex)
        }.toString()
    }

    /**
     * Result of handling an incoming signed queue rotation proposal.
     */
    data class RotationProposalHandlingResult(
        val success: Boolean,
        val connectionId: String? = null,
        val remotePartyKey: String? = null,
        val ackWireJson: String? = null,
        val error: String? = null
    )

    /**
     * Handle incoming cryptographically authenticated ROTATE_PROPOSE.
     * Verifies signature against the connection's remotePartyKey.
     * On success:
     * - Staged in pendingRecvQueueId
     * - Activates new queue for receiving
     * - Drains old queue with grace period
     * - If mySigningSecretKey provided, returns signed ROTATE_ACK payload
     */
    suspend fun handleSignedRotationProposal(
        json: JSONObject,
        mySigningSecretKey: ByteArray? = null
    ): RotationProposalHandlingResult = withContext(Dispatchers.IO) {
        val connId = json.optString("connectionId", "")
        val oldQueueId = json.optString("oldQueueId", "")
        val newQueueId = json.optString("newQueueId", "")
        val generation = json.optLong("generation", 0L)
        val nonce = json.optString("nonce", "")
        val timestamp = json.optLong("timestamp", 0L)
        val signatureHex = json.optString("signature", "")

        if (newQueueId.isBlank() || signatureHex.isBlank()) {
            return@withContext RotationProposalHandlingResult(false, error = "Missing required rotation fields")
        }

        val conn = (if (oldQueueId.isNotBlank()) connectionQueueDao.getByRecvQueueId(oldQueueId) else null)
            ?: (if (connId.isNotBlank()) connectionQueueDao.getById(connId) else null)
            ?: return@withContext RotationProposalHandlingResult(false, error = "Connection not found for proposal")

        // Cryptographic signature verification using peer's signing public key
        val remoteKeyBytes = CryptoManager.fromHexOrNull(conn.remotePartyKey, 32)
            ?: return@withContext RotationProposalHandlingResult(false, error = "Invalid remote party key on connection")

        val signatureBytes = CryptoManager.fromHexOrNull(signatureHex, 64)
            ?: return@withContext RotationProposalHandlingResult(false, error = "Invalid signature format")

        val messageToVerify = "ROTATE_PROPOSE:$connId:$oldQueueId:$newQueueId:$generation:$nonce:$timestamp"
        val verified = CryptoManager.verify(messageToVerify.toByteArray(Charsets.UTF_8), signatureBytes, remoteKeyBytes)
        if (!verified) {
            Log.w(TAG, "[ROTATION] Signature verification FAILED for proposal on conn=${conn.connectionId}")
            return@withContext RotationProposalHandlingResult(false, error = "Cryptographic signature verification failed")
        }

        // Validate oldQueueId matches active recvQueueId or draining queue
        if (oldQueueId.isNotBlank() && conn.recvQueueId != oldQueueId && conn.pendingRecvQueueId != oldQueueId) {
            Log.w(TAG, "[ROTATION] Proposal oldQueueId ($oldQueueId) != active recvQueueId (${conn.recvQueueId})")
        }

        // Authenticate, activate new receive queue, and drain old queue
        authenticateQueueRotation(conn.connectionId, newQueueId)
        activateNewQueue(conn.connectionId)
        drainOldQueue(conn.connectionId)

        Log.i(TAG, "[ROTATION] Successfully authenticated proposal from ${conn.remotePartyKey}: new recvQueueId=$newQueueId (draining old queue)")

        val ackWire = if (mySigningSecretKey != null) {
            createSignedRotationAck(
                connectionId = connId.ifBlank { conn.connectionId },
                oldQueueId = oldQueueId,
                newQueueId = newQueueId,
                nonce = nonce,
                signingSecretKey = mySigningSecretKey
            )
        } else null

        RotationProposalHandlingResult(
            success = true,
            connectionId = conn.connectionId,
            remotePartyKey = conn.remotePartyKey,
            ackWireJson = ackWire
        )
    }

    /**
     * Handle incoming cryptographically authenticated ROTATE_ACK.
     * Verifies signature against the connection's remotePartyKey.
     * On success:
     * - Validates newQueueId matches pendingSendQueueId
     * - Activates new queue for sending
     * - Drains old queue with grace period
     */
    suspend fun handleSignedRotationAck(
        json: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        val connId = json.optString("connectionId", "")
        val oldQueueId = json.optString("oldQueueId", "")
        val newQueueId = json.optString("newQueueId", "")
        val nonce = json.optString("nonce", "")
        val timestamp = json.optLong("timestamp", 0L)
        val signatureHex = json.optString("signature", "")

        if (newQueueId.isBlank() || signatureHex.isBlank()) {
            Log.w(TAG, "[ROTATION] Invalid ROTATE_ACK payload: missing fields")
            return@withContext false
        }

        val conn = (if (oldQueueId.isNotBlank()) connectionQueueDao.getBySendQueueId(oldQueueId) else null)
            ?: (if (connId.isNotBlank()) connectionQueueDao.getById(connId) else null)
            ?: run {
                Log.w(TAG, "[ROTATION] Connection not found for ROTATE_ACK (connId=$connId, oldQueueId=$oldQueueId)")
                return@withContext false
            }

        val remoteKeyBytes = CryptoManager.fromHexOrNull(conn.remotePartyKey, 32)
            ?: run {
                Log.w(TAG, "[ROTATION] Invalid remotePartyKey on conn=${conn.connectionId}")
                return@withContext false
            }

        val signatureBytes = CryptoManager.fromHexOrNull(signatureHex, 64)
            ?: run {
                Log.w(TAG, "[ROTATION] Invalid signature format on ROTATE_ACK")
                return@withContext false
            }

        val messageToVerify = "ROTATE_ACK:$connId:$oldQueueId:$newQueueId:$nonce:$timestamp"
        val verified = CryptoManager.verify(messageToVerify.toByteArray(Charsets.UTF_8), signatureBytes, remoteKeyBytes)
        if (!verified) {
            Log.w(TAG, "[ROTATION] Signature verification FAILED for ROTATE_ACK on conn=${conn.connectionId}")
            return@withContext false
        }

        if (conn.pendingSendQueueId != null && conn.pendingSendQueueId != newQueueId) {
            Log.w(TAG, "[ROTATION] ROTATE_ACK newQueueId ($newQueueId) does not match pendingSendQueueId (${conn.pendingSendQueueId})")
            return@withContext false
        }

        // Activate new send queue and drain old queue
        activateNewQueue(conn.connectionId)
        drainOldQueue(conn.connectionId)

        Log.i(TAG, "[ROTATION] ROTATE_ACK verified: sendQueueId transitioned to $newQueueId for conn=${conn.connectionId}")
        true
    }

    /**
     * Encode a queue rotation notification frame (legacy fallback).
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
     * Handle a queue rotation notice received from the remote peer (legacy fallback).
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
                authenticateQueueRotation(conn.connectionId, newQueueId)
                activateNewQueue(conn.connectionId)
                drainOldQueue(conn.connectionId)
                Log.i(TAG, "[ROTATE] Peer $fromKey rotated send queue -> our recvQueueId transitioned to $newQueueId (draining)")
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
