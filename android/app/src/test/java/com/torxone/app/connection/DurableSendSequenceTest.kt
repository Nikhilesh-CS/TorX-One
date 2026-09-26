package com.torxone.app.connection

import com.torxone.app.data.entity.ConnectionDbEntity
import com.torxone.app.incoming.AtomicReceiveSequenceTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class DurableSendSequenceTest {

    @Test
    fun testSendSequenceSurvivesRestartAndDbPersistence() = runBlocking {
        val fakeDao = AtomicReceiveSequenceTest.FakeConnectionDao()
        val relationshipId = "rel-durable-seq"

        // Initial connection entity in DB with sendSequence = 5
        val initialDbEntity = ConnectionDbEntity(
            connectionId = "conn-durable-1",
            relationshipId = relationshipId,
            generation = 1,
            state = "ACTIVE",
            sendQueueId = "q-send-durable",
            recvQueueId = "q-recv-durable",
            sendAuth = byteArrayOf(1),
            recvAuth = byteArrayOf(2),
            sendSequence = 5L,
            recvSequence = 0L,
            createdAt = 1000L
        )
        fakeDao.upsert(initialDbEntity)

        // Process 1: ConnectionManager starts up
        val connManager1 = ConnectionManager(connectionDao = fakeDao)
        val initialConnection = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-durable",
            recvQueueId = "q-recv-durable",
            sendAuth = byteArrayOf(1),
            recvAuth = byteArrayOf(2),
            sendSequence = 5L,
            recvSequence = 0L
        )
        connManager1.registerConnection(initialConnection)

        // Allocate sequence 1: should be 6
        val seq1 = connManager1.allocateSendSequence(relationshipId)
        assertEquals(6L, seq1)
        assertEquals(6L, fakeDao.getByRelationshipId(relationshipId)?.sendSequence)

        // Allocate sequence 2: should be 7
        val seq2 = connManager1.allocateSendSequence(relationshipId)
        assertEquals(7L, seq2)
        assertEquals(7L, fakeDao.getByRelationshipId(relationshipId)?.sendSequence)

        // Simulate app kill and process restart
        // Process 2: Fresh ConnectionManager instantiated, loads connections from DB
        val connManager2 = ConnectionManager(connectionDao = fakeDao)
        val persisted = fakeDao.getByRelationshipId(relationshipId)
        assertNotNull(persisted)
        assertEquals(7L, persisted!!.sendSequence)

        // Restore connection using persisted DB state
        val restoredConnection = Connection(
            relationshipId = persisted.relationshipId,
            generation = persisted.generation,
            sendQueueId = persisted.sendQueueId,
            recvQueueId = persisted.recvQueueId,
            sendAuth = persisted.sendAuth,
            recvAuth = persisted.recvAuth,
            sendSequence = persisted.sendSequence,
            recvSequence = persisted.recvSequence
        )
        connManager2.registerConnection(restoredConnection)

        // Next allocation in restored process must continue from 8, NOT reset to 1
        val seq3 = connManager2.allocateSendSequence(relationshipId)
        assertEquals("Allocated sequence after restart must continue from persisted sequence", 8L, seq3)
        assertEquals(8L, fakeDao.getByRelationshipId(relationshipId)?.sendSequence)
    }

    @Test
    fun testConcurrentSendsGetUniqueStrictlyIncreasingSequenceValues() = runBlocking {
        val fakeDao = AtomicReceiveSequenceTest.FakeConnectionDao()
        val relationshipId = "rel-concurrent-seq"

        val connManager = ConnectionManager(connectionDao = fakeDao)
        val conn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-c",
            recvQueueId = "q-recv-c",
            sendAuth = byteArrayOf(1),
            recvAuth = byteArrayOf(2),
            sendSequence = 0L,
            recvSequence = 0L
        )
        connManager.registerConnection(conn)
        fakeDao.upsert(
            ConnectionDbEntity(
                connectionId = "conn-c-1",
                relationshipId = relationshipId,
                generation = 1,
                state = "ACTIVE",
                sendQueueId = "q-send-c",
                recvQueueId = "q-recv-c",
                sendAuth = byteArrayOf(1),
                recvAuth = byteArrayOf(2),
                sendSequence = 0L,
                recvSequence = 0L,
                createdAt = 1000L
            )
        )

        val concurrencyCount = 50
        val allocatedSequences = Collections.synchronizedList(mutableListOf<Long>())

        // Launch 50 concurrent coroutines attempting to allocate sequence numbers simultaneously
        withContext(Dispatchers.Default) {
            val jobs = (1..concurrencyCount).map {
                launch {
                    val seq = connManager.allocateSendSequence(relationshipId)
                    allocatedSequences.add(seq)
                }
            }
            jobs.joinAll()
        }

        // Verify that exactly 50 sequence numbers were allocated
        assertEquals(concurrencyCount, allocatedSequences.size)

        // Verify that every single allocated number is unique (no duplicate sequence)
        val uniqueSequences = allocatedSequences.toSet()
        assertEquals("Every allocated send sequence must be strictly unique under concurrency", concurrencyCount, uniqueSequences.size)

        // Verify that the numbers cover exactly 1..50
        val sorted = allocatedSequences.sorted()
        val expected = (1L..concurrencyCount.toLong()).toList()
        assertEquals(expected, sorted)

        // Verify final DB sequence
        val finalDbSeq = fakeDao.getByRelationshipId(relationshipId)?.sendSequence
        assertEquals(concurrencyCount.toLong(), finalDbSeq)
    }
}
