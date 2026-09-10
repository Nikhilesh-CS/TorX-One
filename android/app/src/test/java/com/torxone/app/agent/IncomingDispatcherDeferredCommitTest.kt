package com.torxone.app.agent

import com.torxone.app.connection.ConnectionManager
import com.torxone.app.connection.FakeConnectionQueueDao
import com.torxone.app.protocol.ProtocolEnvelope
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IncomingDispatcherDeferredCommitTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeDao: FakeConnectionQueueDao
    private lateinit var connectionManager: ConnectionManager
    private lateinit var mockAgent: TorXAgent
    private lateinit var dispatcher: IncomingDispatcher

    private val connId = "conn-deferred-123"
    private val queueId = "queue-rx-456"
    private val localKey = "localKeyHex"
    private val remoteKey = "remoteKeyHex"

    private val processedInAgent = mutableSetOf<String>()

    @Before
    fun setup() {
        fakeDao = FakeConnectionQueueDao()
        connectionManager = ConnectionManager(fakeDao)

        mockAgent = mock()
        whenever(mockAgent.connectionManager).thenReturn(connectionManager)
        whenever(mockAgent.isAlreadyProcessed(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            processedInAgent.contains(key)
        }
        whenever(mockAgent.markProcessed(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            processedInAgent.add(key)
            Unit
        }

        dispatcher = IncomingDispatcher(mockAgent, testScope)

        // Seed an active connection in fake DB with lastRecvSeq = 10
        val entity = ConnectionQueueEntity(
            connectionId = connId,
            localPartyKey = localKey,
            remotePartyKey = remoteKey,
            sendQueueId = "queue-tx-789",
            recvQueueId = queueId,
            lastRecvSeq = 10L,
            lastCommittedHash = null,
            state = "ACTIVE",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis()
        )
        kotlinx.coroutines.runBlocking {
            fakeDao.upsert(entity)
        }
    }

    private fun createEnvelope(seq: Long, prevHash: String? = null): ProtocolEnvelope {
        return ProtocolEnvelope(
            version = 2,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = seq,
            previousHash = prevHash,
            timestamp = System.currentTimeMillis(),
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "cipher_data_for_seq_$seq"
        )
    }

    @Test
    fun testDeferredSequenceCommit_notCommittedOnDecryptFailure() = runTest(testDispatcher) {
        val env11 = createEnvelope(11L)

        // Simulate decryption failure: onSessionMessage returns false
        var attempts = 0
        dispatcher.onSessionMessage = { _, _, _ ->
            attempts++
            false // Decrypt failed!
        }

        dispatcher.dispatchProtocolEnvelope(env11, "endpoint-1", TransportType.NEARBY_DIRECT)

        assertEquals("Attempted dispatch once", 1, attempts)

        // Sequence cursor MUST NOT be advanced!
        val connAfterFail = connectionManager.getConnectionById(connId)!!
        assertEquals("lastRecvSeq must remain 10 after decrypt failure", 10L, connAfterFail.lastRecvSeq)

        // Dedupe key must NOT be marked processed
        val dedupeKey = "$connId:11"
        assertFalse("Dedupe key must not be marked on failure", processedInAgent.contains(dedupeKey))

        // Now simulate sender retrying sequence 11, which now succeeds
        dispatcher.onSessionMessage = { _, _, _ ->
            attempts++
            true // Decrypt succeeds!
        }

        dispatcher.dispatchProtocolEnvelope(env11, "endpoint-1", TransportType.NEARBY_DIRECT)

        assertEquals("Attempted dispatch twice (retry accepted)", 2, attempts)

        // Sequence cursor MUST NOW be committed to 11
        val connAfterSuccess = connectionManager.getConnectionById(connId)!!
        assertEquals("lastRecvSeq must be committed to 11 on success", 11L, connAfterSuccess.lastRecvSeq)
        assertTrue("Dedupe key must now be marked processed", processedInAgent.contains(dedupeKey))
    }

    @Test
    fun testSequenceGapBuffering_buffersAndDrainsInOrder() = runTest(testDispatcher) {
        // Initial lastRecvSeq = 10
        val env12 = createEnvelope(12L) // Gap! (11 missing)

        val processedSequences = mutableListOf<Long>()
        dispatcher.onSessionMessage = { _, json, _ ->
            val ciphertext = json.optString("ciphertext", "")
            if (ciphertext.contains("seq_11")) processedSequences.add(11L)
            if (ciphertext.contains("seq_12")) processedSequences.add(12L)
            true
        }

        // Deliver 12 first
        dispatcher.dispatchProtocolEnvelope(env12, "endpoint-1", TransportType.NEARBY_DIRECT)

        // Verify 12 was BUFFERED, cursor is NOT advanced
        val connAfter12 = connectionManager.getConnectionById(connId)!!
        assertEquals("Cursor must remain at 10 when gap is detected", 10L, connAfter12.lastRecvSeq)
        assertTrue("Sequence 12 must be buffered in gap buffer", dispatcher.sequenceGapBuffers[connId]?.containsKey(12L) == true)
        assertTrue("Sequence 12 must not have been dispatched to app yet", processedSequences.isEmpty())

        // Now deliver missing packet 11
        val env11 = createEnvelope(11L)
        dispatcher.dispatchProtocolEnvelope(env11, "endpoint-1", TransportType.NEARBY_DIRECT)

        // Both 11 and 12 must now be processed in strict sequential order!
        assertEquals(listOf(11L, 12L), processedSequences)

        // Cursor must be committed to 12
        val connFinal = connectionManager.getConnectionById(connId)!!
        assertEquals("Cursor must be committed to 12 after draining buffer", 12L, connFinal.lastRecvSeq)

        // Buffer should now be empty
        assertEquals(0, dispatcher.sequenceGapBuffers[connId]?.size ?: 0)
    }
}
