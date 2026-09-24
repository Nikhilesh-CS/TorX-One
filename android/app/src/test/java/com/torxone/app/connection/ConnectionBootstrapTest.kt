package com.torxone.app.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionBootstrapTest {

    @Test
    fun simultaneousCreationConvergesToCrossedQueues() = runTest {
        val aliceManager = ConnectionManager(FakeConnectionQueueDao())
        val bobManager = ConnectionManager(FakeConnectionQueueDao())
        val aliceKey = "aa".repeat(32)
        val bobKey = "bb".repeat(32)

        val alice = aliceManager.getOrCreateConnection(bobKey, aliceKey)
        val bob = bobManager.getOrCreateConnection(aliceKey, bobKey)

        assertEquals(alice.connectionId, bob.connectionId)
        assertEquals(alice.sendQueueId, bob.recvQueueId)
        assertEquals(alice.recvQueueId, bob.sendQueueId)
        assertEquals(
            aliceManager.deriveDirectionalQueueCapability(aliceKey, bobKey),
            bobManager.deriveDirectionalQueueCapability(aliceKey, bobKey)
        )
    }

    @Test
    fun authenticatedBootstrapRejectsNonCanonicalQueuePair() = runTest {
        val manager = ConnectionManager(FakeConnectionQueueDao())
        val aliceKey = "aa".repeat(32)
        val bobKey = "bb".repeat(32)
        val connectionId = manager.deriveConnectionId(aliceKey, bobKey)

        val rejected = manager.acceptAuthenticatedConnection(
            connectionId = connectionId,
            localPartyKey = bobKey,
            remotePartyKey = aliceKey,
            remoteSendQueueId = "attacker-queue",
            remoteReplyQueueId = manager.deriveDirectionalQueueId(bobKey, aliceKey)
        )
        assertNull(rejected)

        val accepted = manager.acceptAuthenticatedConnection(
            connectionId = connectionId,
            localPartyKey = bobKey,
            remotePartyKey = aliceKey,
            remoteSendQueueId = manager.deriveDirectionalQueueId(aliceKey, bobKey),
            remoteReplyQueueId = manager.deriveDirectionalQueueId(bobKey, aliceKey)
        )
        assertNotNull(accepted)
    }
}
