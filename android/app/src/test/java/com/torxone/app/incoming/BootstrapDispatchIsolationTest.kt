package com.torxone.app.incoming

import com.torxone.app.agent.EndToEndPipelineTest.Node
import com.torxone.app.crypto.NoOpKeyProtector
import com.torxone.app.data.dao.PendingInviteDao
import com.torxone.app.data.entity.PendingInviteEntity
import com.torxone.app.protocol.OpaqueTransportEnvelope
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BootstrapDispatchIsolationTest {
    @Test fun collidingInviteIdsHaveIndependentLanesAndCancellationReleasesThem() = runBlocking {
        assertEquals("invite-Aa".hashCode(), "invite-BB".hashCode())
        val node = Node("receiver")
        val blocked = CompletableDeferred<Unit>()
        val invites = object : PendingInviteDao by node.pendingInviteDao {
            override suspend fun getById(inviteId: String): PendingInviteEntity? {
                if (inviteId == "Aa") { blocked.complete(Unit); awaitCancellation() }
                return null
            }
        }
        val dispatcher = IncomingDispatcher(node.connectionManager, node.sessionCrypto, node.processedDao,
            node.chatReceiver, node.receiptHandler, node.agent, { node.identity.identityId },
            pendingInviteDao = invites, identityRepository = node.identityRepo,
            keyProtector = NoOpKeyProtector())
        fun frame(id: String) = ProtocolCodec.encodeTransportEnvelope(
            OpaqueTransportEnvelope(version = 1, envelopeId = "delivery-$id", queueAddress = "invite-$id",
                opaqueCiphertext = byteArrayOf(1), queueAuthenticator = ByteArray(32)))
        val first = launch { dispatcher.dispatch(frame("Aa"), TransportType.FAKE) }
        try {
            withTimeout(1_000) { blocked.await() }
            assertFalse(withTimeout(1_000) { dispatcher.dispatch(frame("BB"), TransportType.FAKE) })
            assertEquals(1, dispatcher.retainedBootstrapLaneCount())
        } finally { first.cancelAndJoin(); node.stop() }
        assertEquals(0, dispatcher.retainedBootstrapLaneCount())
    }
}
