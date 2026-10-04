package com.torxone.app.agent

import com.torxone.app.connection.*
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.data.entity.ConnectionDbEntity
import com.torxone.app.protocol.*
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingDeliveryGateTest {
    @Test fun incompleteOrSupersededConnectionCannotAllocateSequenceOrRatchet() = runBlocking {
        val dao = EndToEndPipelineTest.InMemoryConnectionDao()
        val connection = Connection("conn", "rel", sendQueueId = "send", recvQueueId = "recv")
        val manager = ConnectionManager(keyProtector = com.torxone.app.crypto.NoOpKeyProtector()).apply { registerConnection(connection) }
        val sessions = EndToEndPipelineTest.InMemorySessionStore()
        val outbox = EndToEndPipelineTest.InMemoryOutboxStore()
        val agent = TorXAgent(TransportRouter(), outbox)
        val coordinator = RelationshipSendCoordinator(connectionManager = manager, sessionStore = sessions,
            sessionCrypto = DoubleRatchetSessionCrypto(sessions), connectionDao = dao, agent = agent)
        var allocated = false
        val envelope = SecureEnvelope(1, "message", "chat", "local", "remote", MessageType.CALL_OFFER, payload = byteArrayOf(1))
        try {
            for (row in listOf<ConnectionDbEntity?>(null,
                ConnectionDbEntity("conn", "rel", 1, "send", "recv", ByteArray(32), ByteArray(32), state = "LOCAL_ESTABLISHED"),
                ConnectionDbEntity("conn", "rel", 2, "send", "recv", ByteArray(32), ByteArray(32), state = "ACTIVE"))) {
                dao.connections.clear()
                if (row != null) dao.connections["conn"] = row
                try {
                    coordinator.sendSequenced("rel", connection, { allocated = true; envelope.copy(directionSequence = it) }) { _, _, _ -> Unit }
                    fail("Unconfirmed or stale connection must not send")
                } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Pairing")) }
                try {
                    coordinator.sendDurableUnsequenced("rel", connection, envelope) { _, _ -> Unit }
                    fail("Unconfirmed call signaling must not send")
                } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Pairing")) }
            }
            assertFalse(allocated)
            assertTrue(sessions.sessions.isEmpty())
            assertTrue(outbox.items.isEmpty())
        } finally { agent.stop() }
    }
}
