package com.torxone.app.agent

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * TorX One 2.0 — Delivery Queue Entity
 *
 * Room-backed persistent delivery queue. Every outgoing envelope
 * is persisted here BEFORE any transport attempt. This guarantees
 * no message loss on crash, transport failure, or app restart.
 *
 * The state machine is:
 *   CREATED → ENCRYPTED → QUEUED → TRANSMITTING → ACCEPTED → DELIVERED → READ
 *   Any state can transition to FAILED or EXPIRED.
 */
@Entity(
    tableName = "delivery_queue",
    indices = [
        Index(value = ["state", "nextRetryAt"]),
        Index(value = ["connectionId"]),
        Index(value = ["messageId"]),
        Index(value = ["expiresAt"])
    ]
)
data class DeliveryQueueEntity(
    @PrimaryKey val envelopeId: String,

    /** Pairwise connection identifier (links to connection_queue). */
    val connectionId: String,

    /** Application-level message ID. */
    val messageId: String,

    /** Queue identifier (send/recv). */
    val queueId: String,

    /** Monotonic sequence number within the queue. */
    val sequenceNumber: Long,

    /** SHA-256 hash of the previous envelope. */
    val previousMessageHash: String? = null,

    /** SHA-256 hash of this envelope. */
    val envelopeHash: String? = null,

    /** Message type enum name (stored as String). */
    val messageType: String,

    /** Already-encrypted payload. Opaque to transport. */
    val encryptedPayload: String,

    /** Current delivery state. */
    val state: String = "CREATED",

    /** Creation timestamp. */
    val createdAt: Long,

    /** Last delivery attempt timestamp. */
    val lastAttemptAt: Long? = null,

    /** When to next attempt delivery. */
    val nextRetryAt: Long? = null,

    /** How many delivery attempts so far. */
    val retryCount: Int = 0,

    /** When this envelope expires. */
    val expiresAt: Long,

    /** Which transport delivered this (filled after success). */
    val transportUsed: String? = null,

    /** Delivery receipt timestamp (when peer ACKed). */
    val deliveredAt: Long? = null,

    /** Read receipt timestamp (when peer emitted READ). */
    val readAt: Long? = null,

    /** Target peer's signing key (for address resolution). */
    val recipientKey: String
)

/**
 * TorX One 2.0 — Connection Queue Entity
 *
 * Represents a pairwise connection between two parties.
 * Each connection has two unidirectional queues (A→B and B→A)
 * with independent sequence counters.
 *
 * Identity != Connection != Queue != Transport Address
 */
@Entity(
    tableName = "connection_queue",
    indices = [
        Index(value = ["connectionId"], unique = true),
        Index(value = ["remotePartyKey"]),
        Index(value = ["sendQueueId"]),
        Index(value = ["recvQueueId"]),
        Index(value = ["state"])
    ]
)
data class ConnectionQueueEntity(
    @PrimaryKey val connectionId: String,

    /** Our signing public key hex. */
    val localPartyKey: String,

    /** Remote peer's signing public key hex. */
    val remotePartyKey: String,

    /** Identifier for our send queue (A→B). */
    val sendQueueId: String,

    /** Identifier for our receive queue (B→A). */
    val recvQueueId: String,

    /** Proposed new outbound queue identifier during rotation. */
    val pendingSendQueueId: String? = null,

    /** Proposed new inbound queue identifier during rotation. */
    val pendingRecvQueueId: String? = null,

    /** 6-stage queue rotation state: ACTIVE, ROTATION_PROPOSED, ROTATION_AUTHENTICATED, NEW_QUEUE_ACTIVE, OLD_QUEUE_DRAINING, CLOSED */
    val rotationState: String = "ACTIVE",

    /** Timestamp until which messages on old queues are accepted during draining window. */
    val rotationGracePeriodUntil: Long? = null,

    /** Last sequence number we sent. */
    val lastSendSeq: Long = 0,

    /** Last sequence number we received, decrypted, persisted, and acknowledged. */
    val lastRecvSeq: Long = 0,

    /** Hash of the last successfully committed envelope in this connection. */
    val lastCommittedHash: String? = null,

    /** Connection state (ACTIVE, SUSPENDED, CLOSED). */
    val state: String = "ACTIVE",

    /** When this connection was established. */
    val createdAt: Long,

    /** When this connection was last active. */
    val lastActiveAt: Long
)

// ─────────────────── DAOs ───────────────────

@Dao
interface DeliveryQueueDao {

    // ── Insert / Update ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeliveryQueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DeliveryQueueEntity>)

    @Update
    suspend fun update(entity: DeliveryQueueEntity)

    // ── Queries for delivery processing ──

    /** Get envelopes ready for delivery (QUEUED state, retry time passed). */
    @Query("""
        SELECT * FROM delivery_queue 
        WHERE state = 'QUEUED' 
        AND (nextRetryAt IS NULL OR nextRetryAt <= :now) 
        AND expiresAt > :now
        ORDER BY sequenceNumber ASC 
        LIMIT :limit
    """)
    suspend fun getPendingDeliveries(now: Long, limit: Int = 50): List<DeliveryQueueEntity>

    /** Get all envelopes currently being transmitted. */
    @Query("SELECT * FROM delivery_queue WHERE state = 'TRANSMITTING'")
    suspend fun getTransmitting(): List<DeliveryQueueEntity>

    /** Get a specific envelope by ID. */
    @Query("SELECT * FROM delivery_queue WHERE envelopeId = :envelopeId LIMIT 1")
    suspend fun getByEnvelopeId(envelopeId: String): DeliveryQueueEntity?

    /** Get envelopes by message ID (may be multiple for group fan-out). */
    @Query("SELECT * FROM delivery_queue WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): List<DeliveryQueueEntity>

    // ── State transitions ──

    @Query("UPDATE delivery_queue SET state = :state, lastAttemptAt = :now WHERE envelopeId = :envelopeId")
    suspend fun updateState(envelopeId: String, state: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE delivery_queue 
        SET state = 'QUEUED', retryCount = retryCount + 1, 
            nextRetryAt = :nextRetryAt, lastAttemptAt = :now 
        WHERE envelopeId = :envelopeId
    """)
    suspend fun scheduleRetry(envelopeId: String, nextRetryAt: Long, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE delivery_queue 
        SET state = 'ACCEPTED', transportUsed = :transport, lastAttemptAt = :now 
        WHERE envelopeId = :envelopeId
    """)
    suspend fun markAccepted(envelopeId: String, transport: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE delivery_queue SET state = 'DELIVERED', deliveredAt = :now WHERE messageId = :messageId AND state IN ('ACCEPTED', 'TRANSMITTING', 'QUEUED')")
    suspend fun markDelivered(messageId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE delivery_queue SET state = 'READ', readAt = :now WHERE messageId = :messageId AND state IN ('DELIVERED', 'ACCEPTED')")
    suspend fun markRead(messageId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE delivery_queue SET state = 'FAILED' WHERE envelopeId = :envelopeId")
    suspend fun markFailed(envelopeId: String)

    // ── Cleanup ──

    @Query("DELETE FROM delivery_queue WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long)

    @Query("DELETE FROM delivery_queue WHERE state IN ('DELIVERED', 'READ', 'FAILED', 'EXPIRED') AND lastAttemptAt < :cutoff")
    suspend fun pruneCompleted(cutoff: Long)

    @Query("DELETE FROM delivery_queue WHERE envelopeId = :envelopeId")
    suspend fun delete(envelopeId: String)

    // ── Observables ──

    @Query("SELECT COUNT(*) FROM delivery_queue WHERE state = 'QUEUED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM delivery_queue WHERE recipientKey = :recipientKey AND state NOT IN ('DELIVERED', 'READ', 'FAILED', 'EXPIRED') ORDER BY sequenceNumber ASC")
    fun observePendingForPeer(recipientKey: String): Flow<List<DeliveryQueueEntity>>
}

@Dao
interface ConnectionQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConnectionQueueEntity)

    @Query("SELECT * FROM connection_queue WHERE connectionId = :connectionId LIMIT 1")
    suspend fun getById(connectionId: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE remotePartyKey = :remoteKey ORDER BY CASE WHEN state = 'ACTIVE' THEN 0 ELSE 1 END, lastActiveAt DESC LIMIT 1")
    suspend fun getByRemoteKey(remoteKey: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE remotePartyKey = :remoteKey ORDER BY lastActiveAt DESC")
    suspend fun getAllByRemoteKey(remoteKey: String): List<ConnectionQueueEntity>

    @Query("SELECT * FROM connection_queue WHERE sendQueueId = :queueId OR recvQueueId = :queueId OR pendingSendQueueId = :queueId OR pendingRecvQueueId = :queueId LIMIT 1")
    suspend fun getByQueueId(queueId: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE sendQueueId = :sendQueueId LIMIT 1")
    suspend fun getBySendQueueId(sendQueueId: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE recvQueueId = :recvQueueId LIMIT 1")
    suspend fun getByRecvQueueId(recvQueueId: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE state = 'ACTIVE'")
    suspend fun getActiveConnections(): List<ConnectionQueueEntity>

    @Query("UPDATE connection_queue SET lastSendSeq = :seq, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateSendSeq(connectionId: String, seq: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET lastRecvSeq = :seq, lastCommittedHash = :hash, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun commitRecvSeq(connectionId: String, seq: Long, hash: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET lastRecvSeq = :seq, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateRecvSeq(connectionId: String, seq: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET state = :state WHERE connectionId = :connectionId")
    suspend fun updateState(connectionId: String, state: String)

    @Query("UPDATE connection_queue SET sendQueueId = :sendQueueId, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateSendQueueId(connectionId: String, sendQueueId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET recvQueueId = :recvQueueId, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateRecvQueueId(connectionId: String, recvQueueId: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE connection_queue 
        SET rotationState = :rotationState, 
            pendingSendQueueId = :pendingSend, 
            pendingRecvQueueId = :pendingRecv, 
            rotationGracePeriodUntil = :gracePeriod, 
            lastActiveAt = :now 
        WHERE connectionId = :connectionId
    """)
    suspend fun updateRotationState(
        connectionId: String,
        rotationState: String,
        pendingSend: String?,
        pendingRecv: String?,
        gracePeriod: Long?,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE connection_queue 
        SET sendQueueId = :activeSend,
            recvQueueId = :activeRecv,
            rotationState = :rotationState, 
            pendingSendQueueId = :drainingSend, 
            pendingRecvQueueId = :drainingRecv, 
            rotationGracePeriodUntil = :gracePeriod, 
            lastActiveAt = :now 
        WHERE connectionId = :connectionId
    """)
    suspend fun updateDrainingState(
        connectionId: String,
        activeSend: String,
        activeRecv: String,
        rotationState: String,
        drainingSend: String?,
        drainingRecv: String?,
        gracePeriod: Long?,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE connection_queue 
        SET sendQueueId = :newSendQueueId, 
            recvQueueId = :newRecvQueueId, 
            pendingSendQueueId = NULL, 
            pendingRecvQueueId = NULL, 
            rotationState = 'ACTIVE', 
            rotationGracePeriodUntil = NULL, 
            lastActiveAt = :now 
        WHERE connectionId = :connectionId
    """)
    suspend fun finalizeQueueRotation(
        connectionId: String,
        newSendQueueId: String,
        newRecvQueueId: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE connection_queue SET lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun touchActive(connectionId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM connection_queue")
    fun observeAll(): Flow<List<ConnectionQueueEntity>>

    @Query("DELETE FROM connection_queue WHERE connectionId = :connectionId")
    suspend fun delete(connectionId: String)
}
