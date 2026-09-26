package com.torxone.app.connection

import android.util.Log
import com.torxone.app.agent.TorXAgent
import com.torxone.app.crypto.RoomSessionStore
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.dao.ConnectionDao
import com.torxone.app.data.dao.OutboxDao
import androidx.room.withTransaction
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single per-relationship sequenced-send authority (Phase 3 & Phase 5).
 *
 * Guarantees that:
 * 1. Read current durable sequence
 * 2. Allocate sequence N
 * 3. Construct envelope with sequence N
 * 4. Double Ratchet ratchet encrypt
 * 5. Atomic Room transaction committing:
 *    - Monotonic send sequence N
 *    - Exact ratchet state for step N
 *    - Domain entities (message, edit, reaction, group control, media)
 *    - Exact OutboxEntity delivery item
 * 6. Update in-memory connection sequence ONLY after commit
 * 7. Wake TorXAgent
 *
 * All occur in a single, strictly sequential critical section per relationship.
 * Sequence N+1 can NEVER be allocated, encrypted, or persisted before sequence N commits.
 */
class RelationshipSendCoordinator(
    private val database: TorXDatabase,
    private val connectionManager: ConnectionManager,
    private val sessionStore: RoomSessionStore,
    private val sessionCrypto: SessionCrypto,
    private val outboxDao: OutboxDao,
    private val connectionDao: ConnectionDao,
    private val agent: TorXAgent,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> database.withTransaction { block() } }
) {
    companion object {
        private const val TAG = "RelationshipSendCoordinator"
    }

    private val relationshipLocks = ConcurrentHashMap<String, Mutex>()

    private fun getLock(relationshipId: String): Mutex =
        relationshipLocks.computeIfAbsent(relationshipId) { Mutex() }

    suspend fun <T> sendSequenced(
        relationshipId: String,
        connection: Connection,
        buildEnvelope: (sequence: Long) -> SecureEnvelope,
        persistDomain: suspend (sequence: Long, envelope: SecureEnvelope, ciphertext: ByteArray) -> T
    ): SendResult<T> {
        val mutex = getLock(relationshipId)
        return mutex.withLock {
            // 1. Read current durable sequence from database or connection
            val dbConn = connectionDao.getByRelationshipId(relationshipId)
            val currentSeq = dbConn?.sendSequence ?: connection.sendSequence
            val nextSeq = currentSeq + 1L

            // 2. Build authenticated SecureEnvelope containing sequence nextSeq
            val envelope = buildEnvelope(nextSeq)
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)

            // 3. Cryptographically bind AAD
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            // 4. Double Ratchet mutate & atomic Room transaction
            var domainResult: T? = null

            try {
                sessionCrypto.encryptAndCommit(relationshipId, envelopeBytes, aad) { encrypted, updatedState ->
                    val ciphertext = encrypted.serialize()

                    // Atomic transaction: sequence update + ratchet state + domain entities + outbox
                    transactionRunner {
                        connectionDao.updateSendSequence(relationshipId, nextSeq)
                        sessionStore.saveSession(updatedState)
                        val res = persistDomain(nextSeq, envelope, ciphertext)
                        domainResult = res
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed sequenced send for relationship $relationshipId at seq $nextSeq: ${e.message}", e)
                throw e
            }

            // 5. Update in-memory connection sequence ONLY after Room transaction commits
            connectionManager.commitSendSequence(relationshipId, nextSeq)

            // 6. Wake agent for outbox delivery
            agent.wake(envelope.logicalMessageId)

            SendResult(nextSeq, envelope, domainResult!!)
        }
    }

    data class SendResult<T>(
        val sequence: Long,
        val envelope: SecureEnvelope,
        val domainResult: T
    )
}
