package com.torxone.app.incoming

import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.data.dao.ConnectionDao
import com.torxone.app.data.entity.ConnectionDbEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AtomicReceiveSequenceTest {

    private lateinit var connectionManager: ConnectionManager
    private lateinit var connectionDao: FakeConnectionDao

    @Before
    fun setUp() {
        connectionDao = FakeConnectionDao()
        connectionManager = ConnectionManager(connectionDao = connectionDao)
    }

    @Test
    fun testReceiveTransactionFailureFollowedBySameSequenceRetrySucceeds() = runBlocking {
        val relationshipId = "rel-recv-test"
        val initialConnection = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-1",
            recvQueueId = "q-recv-1",
            sendAuth = byteArrayOf(1, 2, 3),
            recvAuth = byteArrayOf(4, 5, 6),
            sendSequence = 0L,
            recvSequence = 10L // Current recvSequence is 10
        )
        connectionManager.registerConnection(initialConnection)
        connectionDao.upsert(
            ConnectionDbEntity(
                connectionId = "conn-1",
                relationshipId = relationshipId,
                generation = 1,
                state = "ACTIVE",
                sendQueueId = "q-send-1",
                recvQueueId = "q-recv-1",
                sendAuth = byteArrayOf(1, 2, 3),
                recvAuth = byteArrayOf(4, 5, 6),
                sendSequence = 0L,
                recvSequence = 10L,
                createdAt = 1000L
            )
        )

        // 1. First attempt: incoming sequence 11 arrives
        val incomingSeq = 11L
        val isValid = connectionManager.validateRecvSequence(relationshipId, incomingSeq)
        assertTrue("Sequence 11 > 10 must be valid", isValid)

        // Simulate database transaction failure (e.g. SQLite disk error or constraint violation)
        var transactionSucceeded = false
        try {
            // Transaction fails before committing
            throw RuntimeException("Simulated SQLite disk full error during message persist")
            @Suppress("UNREACHABLE_CODE")
            transactionSucceeded = true
        } catch (e: Exception) {
            // Exception caught; commitRecvSequence is NOT called!
        }
        assertFalse(transactionSucceeded)

        // Verify in-memory state and database state remained at 10
        val connAfterFail = connectionManager.getConnectionByRelationship(relationshipId)
        assertNotNull(connAfterFail)
        assertEquals("In-memory recvSequence must NOT advance on transaction failure", 10L, connAfterFail!!.recvSequence)
        assertEquals("DB recvSequence must NOT advance on transaction failure", 10L, connectionDao.getByRelationshipId(relationshipId)?.recvSequence)

        // 2. Retry attempt: network layer or peer re-sends envelope with sequence 11
        val isRetryValid = connectionManager.validateRecvSequence(relationshipId, incomingSeq)
        assertTrue("Same sequence 11 must STILL be accepted on retry because it never committed", isRetryValid)

        // Simulate database transaction succeeding on retry
        connectionDao.updateRecvSequence(relationshipId, incomingSeq)
        connectionManager.commitRecvSequence(relationshipId, incomingSeq)

        // Verify both in-memory and DB state are now 11
        val connAfterSuccess = connectionManager.getConnectionByRelationship(relationshipId)
        assertEquals("In-memory recvSequence must now be 11", 11L, connAfterSuccess!!.recvSequence)
        assertEquals("DB recvSequence must now be 11", 11L, connectionDao.getByRelationshipId(relationshipId)?.recvSequence)

        // 3. Duplicate packet or replay of sequence 11 arrives
        val isDuplicateValid = connectionManager.validateRecvSequence(relationshipId, incomingSeq)
        assertFalse("Duplicate sequence 11 must be strictly REJECTED after successful commit", isDuplicateValid)

        // 4. Stale packet with sequence 10 or 9 arrives
        assertFalse("Stale sequence 10 must be rejected", connectionManager.validateRecvSequence(relationshipId, 10L))
        assertFalse("Stale sequence 5 must be rejected", connectionManager.validateRecvSequence(relationshipId, 5L))

        // 5. Subsequent valid sequence 12 arrives
        assertTrue("Subsequent sequence 12 must be accepted", connectionManager.validateRecvSequence(relationshipId, 12L))
    }

    class FakeConnectionDao : ConnectionDao {
        val connections = ConcurrentHashMap<String, ConnectionDbEntity>()

        override suspend fun getByRelationshipId(relationshipId: String): ConnectionDbEntity? =
            connections.values.firstOrNull { it.relationshipId == relationshipId }

        override suspend fun getByRecvQueue(recvQueueId: String): ConnectionDbEntity? =
            connections.values.firstOrNull { it.recvQueueId == recvQueueId }

        override suspend fun getBySendQueue(sendQueueId: String): ConnectionDbEntity? =
            connections.values.firstOrNull { it.sendQueueId == sendQueueId }

        override suspend fun getAllActive(): List<ConnectionDbEntity> =
            connections.values.filter { it.state == "ACTIVE" }

        override suspend fun upsert(connection: ConnectionDbEntity) {
            connections[connection.connectionId] = connection
        }

        override suspend fun updateState(connectionId: String, state: String) {
            connections[connectionId]?.let { connections[connectionId] = it.copy(state = state) }
        }

        override suspend fun updateSendSequence(relationshipId: String, sendSequence: Long) {
            for ((id, c) in connections) {
                if (c.relationshipId == relationshipId) {
                    connections[id] = c.copy(sendSequence = sendSequence)
                }
            }
        }

        override suspend fun updateRecvSequence(relationshipId: String, recvSequence: Long) {
            for ((id, c) in connections) {
                if (c.relationshipId == relationshipId) {
                    connections[id] = c.copy(recvSequence = recvSequence)
                }
            }
        }
    }
}
