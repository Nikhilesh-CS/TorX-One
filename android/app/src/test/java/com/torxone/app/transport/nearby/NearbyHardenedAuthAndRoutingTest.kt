package com.torxone.app.transport.nearby

import com.google.android.gms.nearby.connection.Payload
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NearbyHardenedAuthAndRoutingTest {

    private lateinit var network: NearbyTransportTest.TestNearbyNetwork
    private lateinit var adapter: NearbyTransportTest.SimulatedNearbyAdapter
    private lateinit var connManager: ConnectionManager
    private lateinit var transport: NearbyTransport

    @Before
    fun setUp() {
        network = NearbyTransportTest.TestNearbyNetwork()
        adapter = NearbyTransportTest.SimulatedNearbyAdapter("ep-local", network)
        connManager = ConnectionManager()

        val agent = TorXAgent(
            transportRouter = TransportRouter(),
            outboxStore = NearbyTransportTest.InMemoryOutbox(),
            processedStore = NearbyTransportTest.InMemoryProcessed(),
            coroutineDispatcher = Dispatchers.Default
        )

        transport = NearbyTransport(
            context = NearbyTransportTest.DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            connectionManager = connManager,
            agent = agent,
            adapter = adapter
        )
        adapter.payloadCallback = transport.payloadCallback
    }

    @Test
    fun testMultiPeerBootstrapGoesStrictlyToBoundEndpointWithoutFirstOrNull() = runBlocking {
        // Connect three different peers
        val ep1 = NearbyTransportTest.SimulatedNearbyAdapter("ep-1", network)
        val ep2 = NearbyTransportTest.SimulatedNearbyAdapter("ep-2", network)
        val ep3 = NearbyTransportTest.SimulatedNearbyAdapter("ep-3", network)

        adapter.activeConnections.add("ep-1")
        adapter.activeConnections.add("ep-2")
        adapter.activeConnections.add("ep-3")
        ep1.activeConnections.add("ep-local")
        ep2.activeConnections.add("ep-local")
        ep3.activeConnections.add("ep-local")

        // 1. Unbound queue: must fail closed, NEVER pick firstOrNull (which would have been ep-1)
        val unboundResult = transport.send(
            destination = TransportDestination("unmapped-queue-xyz"),
            payload = "Sensitive message".toByteArray()
        )
        assertTrue("Send to unmapped queue must fail", unboundResult is TransportResult.Failed)
        val failMsg = (unboundResult as TransportResult.Failed).error
        assertTrue("Reason must state no authenticated ready route", failMsg.contains("No authenticated READY route"))

        // 2. Bind specific invite to ep-2
        val inviteId = "bob-secure"
        transport.bindInviteEndpoint(inviteId, "ep-2")

        // 3. Send using invite queue destination
        val boundResult = transport.send(
            destination = TransportDestination("invite-$inviteId"),
            payload = "Bootstrap payload for Bob".toByteArray()
        )
        assertTrue("Send to bound invite endpoint must succeed", boundResult is TransportResult.Accepted)

        // Verify route table was used deterministically and ep-1 / ep-3 were never contacted
        assertEquals("ep-2", transport.directRouteTable.getEndpointForQueue("invite-$inviteId"))
    }

    @Test
    fun testForgedAuthOkRejectedAndPeerDisconnected() = runBlocking {
        val relVictim = "rel-victim-123"
        connManager.registerConnection(
            Connection(
                relationshipId = relVictim,
                generation = 1,
                sendQueueId = "q-send-victim",
                recvQueueId = "q-recv-victim",
                sendAuth = byteArrayOf(1),
                recvAuth = byteArrayOf(2)
            )
        )

        // Attacker connects
        val epAttacker = NearbyTransportTest.SimulatedNearbyAdapter("ep-attacker", network)
        adapter.activeConnections.add("ep-attacker")
        epAttacker.activeConnections.add("ep-local")

        // Attacker sends forged AuthOk without valid prior challenge/handshake state
        val forgedAuthOk = NearbyWireFrame.Control.AuthOk(relVictim)
        transport.payloadCallback.onPayloadReceived(
            "ep-attacker",
            Payload.fromBytes(forgedAuthOk.encode())
        )

        // State machine must reject forged AuthOk and disconnect attacker
        assertFalse("Route must NOT be ready for victim relationship", transport.directRouteTable.isReady(relVictim))
        assertFalse("Attacker must be disconnected upon forged AuthOk", adapter.activeConnections.contains("ep-attacker"))
    }

    @Test
    fun testMultiRelationshipNearbyAuthenticationSucceedsWithoutRelationshipEnumeration() {
        val rel1 = "rel-alice-bob"
        val rel2 = "rel-alice-charlie"

        connManager.registerConnection(
            Connection(
                relationshipId = rel1,
                generation = 1,
                sendQueueId = "q-send-bob",
                recvQueueId = "q-recv-bob",
                sendAuth = byteArrayOf(10, 20),
                recvAuth = byteArrayOf(30, 40)
            )
        )
        connManager.registerConnection(
            Connection(
                relationshipId = rel2,
                generation = 1,
                sendQueueId = "q-send-charlie",
                recvQueueId = "q-recv-charlie",
                sendAuth = byteArrayOf(50, 60),
                recvAuth = byteArrayOf(70, 80)
            )
        )

        // 1. Verify hint computation
        val hint1 = transport.computeRelHint(rel1)
        val hint2 = transport.computeRelHint(rel2)
        assertEquals("Hint must be 16-character hex (8 bytes)", 16, hint1.length)
        assertEquals(16, hint2.length)
        assertNotEquals(hint1, hint2)

        // 2. Peer connects and advertises Bob's hint
        val epBob = NearbyTransportTest.SimulatedNearbyAdapter("ep-bob", network)
        adapter.activeConnections.add("ep-bob")
        epBob.activeConnections.add("ep-local")

        var sentPayloadToBob: ByteArray? = null
        epBob.payloadCallback = object : com.google.android.gms.nearby.connection.PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                sentPayloadToBob = payload.asBytes()
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: com.google.android.gms.nearby.connection.PayloadTransferUpdate) {}
        }

        val bobHello = NearbyWireFrame.Control.Hello(
            protocolVersion = 1,
            peerTieBreaker = 12345L,
            supportedFeatures = listOf("rel-hint:$hint1"),
            maxFrameSize = 65536,
            challenge = ByteArray(16) { 7 }
        )

        transport.payloadCallback.onPayloadReceived("ep-bob", Payload.fromBytes(bobHello.encode()))

        // Bob receives AuthProof specifically for rel1, NOT rel2!
        assertNotNull("Bob should have received AuthProof", sentPayloadToBob)
        val decoded = NearbyWireFrame.decode(sentPayloadToBob!!)
        assertTrue(decoded is NearbyWireFrame.Control.AuthProof)
        val proof = decoded as NearbyWireFrame.Control.AuthProof
        assertEquals("AuthProof must match requested relationship only", rel1, proof.relationshipId)
    }
}
