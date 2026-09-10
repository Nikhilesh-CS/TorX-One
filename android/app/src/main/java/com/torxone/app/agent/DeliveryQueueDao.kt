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
 * Inspired by SimpleX's double-queue connection model.
 */
@Entity(
    tableName = "connection_queue",
    indices = [
        Index(value = ["remotePartyKey"], unique = true),
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

    /** Last sequence number we sent. */
    val lastSendSeq: Long = 0,

    /** Last sequence number we received and acknowledged. */
    val lastRecvSeq: Long = 0,

    /** Connection state. */
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

    @Query("UPDATE delivery_queue SET state = 'DELIVERED' WHERE messageId = :messageId AND state IN ('ACCEPTED', 'TRANSMITTING', 'QUEUED')")
    suspend fun markDelivered(messageId: String)

    @Query("UPDATE delivery_queue SET state = 'READ' WHERE messageId = :messageId AND state IN ('DELIVERED', 'ACCEPTED')")
    suspend fun markRead(messageId: String)

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

    @Query("SELECT * FROM connection_queue WHERE remotePartyKey = :remoteKey LIMIT 1")
    suspend fun getByRemoteKey(remoteKey: String): ConnectionQueueEntity?

    @Query("SELECT * FROM connection_queue WHERE state = 'ACTIVE'")
    suspend fun getActiveConnections(): List<ConnectionQueueEntity>

    @Query("UPDATE connection_queue SET lastSendSeq = :seq, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateSendSeq(connectionId: String, seq: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET lastRecvSeq = :seq, lastActiveAt = :now WHERE connectionId = :connectionId")
    suspend fun updateRecvSeq(connectionId: String, seq: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE connection_queue SET state = :state WHERE connectionId = :connectionId")
    suspend fun updateState(connectionId: String, state: String)

    @Query("SELECT * FROM connection_queue")
    fun observeAll(): Flow<List<ConnectionQueueEntity>>

    @Query("DELETE FROM connection_queue WHERE connectionId = :connectionId")
    suspend fun delete(connectionId: String)
}
