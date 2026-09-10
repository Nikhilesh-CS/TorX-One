package com.torxone.app.connection

import com.torxone.app.agent.ConnectionQueueDao
import com.torxone.app.agent.ConnectionQueueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Phase 1 Unit Tests: Connection & Queue Protocol
 *
 * Verifies:
 * 1. Identity != Connection != Queue separation (non-unique remotePartyKey support)
 * 2. 6-stage queue rotation state machine:
 *    ACTIVE -> ROTATION_PROPOSED -> ROTATION_AUTHENTICATED -> NEW_QUEUE_ACTIVE -> OLD_QUEUE_DRAINING -> CLOSED
 * 3. Draining grace window & in-flight queue acceptance
 * 4. Safe sequence advancement pipeline: read validation does NOT mutate SQLite cursor;
 *    cursor only advances upon commitRecvSequence.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionLifecycleTest {

    private lateinit var fakeDao: FakeConnectionQueueDao
    private lateinit var connectionManager: ConnectionManager

    private val aliceKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bobKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    @Before
    fun setup() {
        fakeDao = FakeConnectionQueueDao()
        connectionManager = ConnectionManager(fakeDao)
    }

    @Test
    fun testGetOrCreateConnection_createsDistinctQueues() = runBlocking {
        val conn = connectionManager.getOrCreateConnection(bobKey, aliceKey)
        assertNotNull(conn)
        assertEquals(bobKey, conn.remotePartyKey)
        assertEquals(aliceKey, conn.localPartyKey)
        assertNotNull(conn.sendQueueId)
        assertNotNull(conn.recvQueueId)
        assertNotEquals(conn.sendQueueId, conn.recvQueueId)
        assertEquals("ACTIVE", conn.state)
        assertEquals("ACTIVE", conn.rotationState)
        assertEquals(0L, conn.lastSendSeq)
        assertEquals(0L, conn.lastRecvSeq)
    }

    @Test
    fun testMultipleConnectionsPerIdentityKey() = runBlocking {
        val conn1 = connectionManager.getOrCreateConnection(bobKey, aliceKey)
        val conn2 = ConnectionQueueEntity(
            connectionId = UUID.randomUUID().toString(),
            localPartyKey = aliceKey,
            remotePartyKey = bobKey,
            sendQueueId = UUID.randomUUID().toString(),
            recvQueueId = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis() + 100,
            lastActiveAt = System.currentTimeMillis() + 100
        )
        fakeDao.upsert(conn2)

        val allConns = connectionManager.getAllConnectionsByRemoteKey(bobKey)
        assertEquals(2, allConns.size)
        assertTrue(allConns.any { it.connectionId == conn1.connectionId })
        assertTrue(allConns.any { it.connectionId == conn2.connectionId })
    }

    @Test
    fun testSixStageQueueRotationLifecycle() = runBlocking {
        val conn = connectionManager.getOrCreateConnection(bobKey, aliceKey)
        val initialSendQueue = conn.sendQueueId
        val initialRecvQueue = conn.recvQueueId

        // Stage 1: ACTIVE
        assertEquals(ConnectionManager.ROTATION_ACTIVE, conn.rotationState)
        assertNull(conn.pendingSendQueueId)
        assertNull(conn.pendingRecvQueueId)

        // Stage 2: ROTATION_PROPOSED
        val proposal = connectionManager.proposeQueueRotation(conn.connectionId)
        assertNotNull(proposal.proposedSendQueueId)
        assertNotEquals(initialSendQueue, proposal.proposedSendQueueId)
        val afterPropose = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(ConnectionManager.ROTATION_PROPOSED, afterPropose.rotationState)
        assertEquals(proposal.proposedSendQueueId, afterPropose.pendingSendQueueId)

        // Stage 3: ROTATION_AUTHENTICATED (Peer proposes new queue for our recv)
        val peerProposedQueueId = UUID.randomUUID().toString()
        val authResult = connectionManager.authenticateQueueRotation(conn.connectionId, peerProposedQueueId)
        assertTrue(authResult)
        val afterAuth = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(ConnectionManager.ROTATION_AUTHENTICATED, afterAuth.rotationState)
        assertEquals(peerProposedQueueId, afterAuth.pendingRecvQueueId)

        // Stage 4: NEW_QUEUE_ACTIVE
        val actResult = connectionManager.activateNewQueue(conn.connectionId)
        assertTrue(actResult)
        val afterActive = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(ConnectionManager.ROTATION_NEW_QUEUE_ACTIVE, afterActive.rotationState)

        // Stage 5: OLD_QUEUE_DRAINING (with 5-min grace period)
        val now = System.currentTimeMillis()
        val drainResult = connectionManager.drainOldQueue(conn.connectionId, 300_000L)
        assertTrue(drainResult)
        val afterDrain = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(ConnectionManager.ROTATION_OLD_QUEUE_DRAINING, afterDrain.rotationState)
        assertTrue((afterDrain.rotationGracePeriodUntil ?: 0L) >= now + 290_000L)

        // During draining grace window, both active and pending queues are accepted
        assertTrue(connectionManager.isQueueAccepted(conn.connectionId, initialRecvQueue, now + 1000L))
        assertTrue(connectionManager.isQueueAccepted(conn.connectionId, peerProposedQueueId, now + 1000L))

        // After grace period expires, old queue is rejected
        assertFalse(connectionManager.isQueueAccepted(conn.connectionId, "unknown-queue-id", now + 1000L))
        assertFalse(connectionManager.isQueueAccepted(conn.connectionId, initialRecvQueue, now + 400_000L))

        // Stage 6: Finalize rotation -> resets to ACTIVE with new queues
        val finalizeResult = connectionManager.finalizeRotation(conn.connectionId)
        assertTrue(finalizeResult)
        val finalized = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(ConnectionManager.ROTATION_ACTIVE, finalized.rotationState)
        assertEquals(proposal.proposedSendQueueId, finalized.sendQueueId)
        assertEquals(peerProposedQueueId, finalized.recvQueueId)
        assertNull(finalized.pendingSendQueueId)
        assertNull(finalized.pendingRecvQueueId)
        assertNull(finalized.rotationGracePeriodUntil)
    }

    @Test
    fun testDeferredSequenceCommitment() = runBlocking {
        val conn = connectionManager.getOrCreateConnection(bobKey, aliceKey)
        assertEquals(0L, conn.lastRecvSeq)

        // 1. validateRecvSequence must NOT advance database cursor
        val result1 = connectionManager.validateRecvSequence(conn.connectionId, 1L)
        assertTrue(result1 is SequenceValidationResult.Valid)
        assertEquals(1L, (result1 as SequenceValidationResult.Valid).sequence)

        // Cursor in DB must still be 0L!
        val dbConnBeforeCommit = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(0L, dbConnBeforeCommit.lastRecvSeq)
        assertNull(dbConnBeforeCommit.lastCommittedHash)

        // 2. Retry before commit should still be valid (not replay)
        val retryResult = connectionManager.validateRecvSequence(conn.connectionId, 1L)
        assertTrue(retryResult is SequenceValidationResult.Valid)

        // 3. Commit sequence after decryption + Room persistence
        connectionManager.commitRecvSequence(conn.connectionId, 1L, "hash_abc_123")
        val dbConnAfterCommit = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(1L, dbConnAfterCommit.lastRecvSeq)
        assertEquals("hash_abc_123", dbConnAfterCommit.lastCommittedHash)

        // 4. Now incoming seq 1 is properly flagged as Replay
        val replayResult = connectionManager.validateRecvSequence(conn.connectionId, 1L)
        assertTrue(replayResult is SequenceValidationResult.Replay)

        // 5. Incoming seq 3 is flagged as ValidWithGap without mutating cursor
        val gapResult = connectionManager.validateRecvSequence(conn.connectionId, 3L)
        assertTrue(gapResult is SequenceValidationResult.ValidWithGap)
        val gapValidation = gapResult as SequenceValidationResult.ValidWithGap
        assertEquals(1L, gapValidation.expected)
        assertEquals(3L, gapValidation.received)

        // Cursor still 1L until committed
        val connStillOne = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(1L, connStillOne.lastRecvSeq)
    }

    @Test
    fun testNextSendSequence_strictlyMonotonic() = runBlocking {
        val conn = connectionManager.getOrCreateConnection(bobKey, aliceKey)
        assertEquals(1L, connectionManager.nextSendSequence(conn.connectionId))
        assertEquals(2L, connectionManager.nextSendSequence(conn.connectionId))
        assertEquals(3L, connectionManager.nextSendSequence(conn.connectionId))

        val updated = connectionManager.getConnectionById(conn.connectionId)!!
        assertEquals(3L, updated.lastSendSeq)
    }
}

/**
 * In-memory test double for ConnectionQueueDao
 */
class FakeConnectionQueueDao : ConnectionQueueDao {
    private val storage = mutableMapOf<String, ConnectionQueueEntity>()

    override suspend fun upsert(entity: ConnectionQueueEntity) {
        storage[entity.connectionId] = entity
    }

    override suspend fun getById(connectionId: String): ConnectionQueueEntity? {
        return storage[connectionId]
    }

    override suspend fun getByRemoteKey(remoteKey: String): ConnectionQueueEntity? {
        return storage.values
            .filter { it.remotePartyKey == remoteKey }
            .sortedWith(compareByDescending<ConnectionQueueEntity> { it.state == "ACTIVE" }.thenByDescending { it.lastActiveAt })
            .firstOrNull()
    }

    override suspend fun getAllByRemoteKey(remoteKey: String): List<ConnectionQueueEntity> {
        return storage.values
            .filter { it.remotePartyKey == remoteKey }
            .sortedByDescending { it.lastActiveAt }
    }

    override suspend fun getByQueueId(queueId: String): ConnectionQueueEntity? {
        return storage.values.firstOrNull {
            it.sendQueueId == queueId || it.recvQueueId == queueId ||
                    it.pendingSendQueueId == queueId || it.pendingRecvQueueId == queueId
        }
    }

    override suspend fun getBySendQueueId(sendQueueId: String): ConnectionQueueEntity? {
        return storage.values.firstOrNull { it.sendQueueId == sendQueueId }
    }

    override suspend fun getByRecvQueueId(recvQueueId: String): ConnectionQueueEntity? {
        return storage.values.firstOrNull { it.recvQueueId == recvQueueId }
    }

    override suspend fun getActiveConnections(): List<ConnectionQueueEntity> {
        return storage.values.filter { it.state == "ACTIVE" }
    }

    override suspend fun updateSendSeq(connectionId: String, seq: Long, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(lastSendSeq = seq, lastActiveAt = now)
        }
    }

    override suspend fun commitRecvSeq(connectionId: String, seq: Long, hash: String?, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(lastRecvSeq = seq, lastCommittedHash = hash, lastActiveAt = now)
        }
    }

    override suspend fun updateRecvSeq(connectionId: String, seq: Long, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(lastRecvSeq = seq, lastActiveAt = now)
        }
    }

    override suspend fun updateState(connectionId: String, state: String) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(state = state)
        }
    }

    override suspend fun updateSendQueueId(connectionId: String, sendQueueId: String, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(sendQueueId = sendQueueId, lastActiveAt = now)
        }
    }

    override suspend fun updateRecvQueueId(connectionId: String, recvQueueId: String, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(recvQueueId = recvQueueId, lastActiveAt = now)
        }
    }

    override suspend fun updateRotationState(
        connectionId: String,
        rotationState: String,
        pendingSend: String?,
        pendingRecv: String?,
        gracePeriod: Long?,
        now: Long
    ) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(
                rotationState = rotationState,
                pendingSendQueueId = pendingSend,
                pendingRecvQueueId = pendingRecv,
                rotationGracePeriodUntil = gracePeriod,
                lastActiveAt = now
            )
        }
    }

    override suspend fun updateDrainingState(
        connectionId: String,
        activeSend: String,
        activeRecv: String,
        rotationState: String,
        drainingSend: String?,
        drainingRecv: String?,
        gracePeriod: Long?,
        now: Long
    ) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(
                sendQueueId = activeSend,
                recvQueueId = activeRecv,
                rotationState = rotationState,
                pendingSendQueueId = drainingSend,
                pendingRecvQueueId = drainingRecv,
                rotationGracePeriodUntil = gracePeriod,
                lastActiveAt = now
            )
        }
    }

    override suspend fun finalizeQueueRotation(
        connectionId: String,
        newSendQueueId: String,
        newRecvQueueId: String,
        now: Long
    ) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(
                sendQueueId = newSendQueueId,
                recvQueueId = newRecvQueueId,
                pendingSendQueueId = null,
                pendingRecvQueueId = null,
                rotationState = "ACTIVE",
                rotationGracePeriodUntil = null,
                lastActiveAt = now
            )
        }
    }

    override suspend fun touchActive(connectionId: String, now: Long) {
        storage[connectionId]?.let {
            storage[connectionId] = it.copy(lastActiveAt = now)
        }
    }

    override fun observeAll(): Flow<List<ConnectionQueueEntity>> {
        return flowOf(storage.values.toList())
    }

    override suspend fun delete(connectionId: String) {
        storage.remove(connectionId)
    }
}
