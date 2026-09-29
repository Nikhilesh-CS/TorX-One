package com.torxone.app.transport.mesh

import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouteDiscoveryTest {
    private var now = 5_000_000L

    @Test
    fun `signed announcement propagates without allowing relay forgery`() {
        val dKeys = IdentityCrypto.generateEd25519KeyPair()
        val cKeys = IdentityCrypto.generateEd25519KeyPair()
        val peers = MeshPeerDirectory().apply {
            authenticated(MeshPeer("C", "rel-b-c", "queue-c", cKeys.publicKey, true))
        }
        val table = MeshRoutingTable(clock = { now })
        val discovery = MeshRouteDiscovery(
            table, peers,
            publicKeyForNode = { node -> if (node == "D") dKeys.publicKey else null },
            clock = { now }
        )
        val expires = now + 60_000
        val signature = IdentityCrypto.signEd25519(
            dKeys.privateKey,
            MeshRouteAnnouncementCodec.signedBytes("D", 7, expires)
        )
        val relayed = MeshRouteAnnouncement("D", 7, 1, expires, signature)

        assertTrue(discovery.receive("C", relayed))
        assertEquals(2, table.bestRoute("D")!!.hopCount)
        val tampered = relayed.copy(sequence = 8)
        assertFalse(discovery.receive("C", tampered))
    }

    @Test
    fun `announcement rate limit and expiry are enforced`() {
        val keys = IdentityCrypto.generateEd25519KeyPair()
        val peers = MeshPeerDirectory().apply {
            authenticated(MeshPeer("C", "rel", "queue", keys.publicKey, true))
        }
        val table = MeshRoutingTable(clock = { now })
        val discovery = MeshRouteDiscovery(table, peers, { keys.publicKey }, { now }, maxAnnouncementsPerMinutePerPeer = 1)
        val good = discovery.createLocalAnnouncement("C", 1, now + 1000, keys.privateKey)
        assertTrue(discovery.receive("C", good))
        assertFalse(discovery.receive("C", good.copy(sequence = 2)))
        now += 60_001
        assertFalse(discovery.receive("C", good))
    }

    @Test
    fun `relay transport wraps ciphertext for one authenticated next hop`() = runBlocking {
        val peers = MeshPeerDirectory().apply {
            authenticated(MeshPeer("B", "rel-a-b", "queue-b", ByteArray(32), true))
        }
        val routes = MeshRoutingTable(clock = { now }).apply {
            installAuthenticated(MeshRoute("D", "B", 3, 1, now + 10_000, "B"))
        }
        var packet: MeshPacket? = null
        val transport = MeshTransport(
            localNodeIdProvider = { "A" }, routingTable = routes, peerDirectory = peers,
            peerLink = MeshPeerLink { peer, encoded ->
                assertEquals("B", peer.nodeId)
                packet = MeshPacketCodec.decode(encoded)
                true
            }, clock = { now }
        )
        val destination = TransportDestination(
            "queue-d",
            mapOf(MeshTransport.MESH_NODE_ID_HINT to "D", MeshTransport.MESH_PRIORITY_HINT to "high")
        )

        assertTrue(transport.canRoute(destination))
        assertEquals(TransportResult.Accepted(TransportType.RELAY), transport.send(destination, byteArrayOf(9, 8, 7)))
        assertEquals("D", packet!!.destinationNodeId)
        assertEquals(MeshPriority.HIGH, packet!!.priority)
        assertTrue(packet!!.opaquePayload.contentEquals(byteArrayOf(9, 8, 7)))
    }
}
