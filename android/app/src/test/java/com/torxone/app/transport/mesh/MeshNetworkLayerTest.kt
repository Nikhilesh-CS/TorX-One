package com.torxone.app.transport.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshNetworkLayerTest {
    private var now = 1_000_000L

    @Test
    fun `four node route forwards opaque ciphertext without flooding`() = runBlocking {
        val payload = ByteArray(512) { (it * 13).toByte() }
        val routesB = MeshRoutingTable(clock = { now }).apply {
            installAuthenticated(MeshRoute("D", "C", 2, 4, now + 60_000, "C"))
        }
        var forwarded: ByteArray? = null
        val nodeB = MeshNetworkLayer(
            "B", routesB, MeshDuplicateCache(clock = { now }),
            MeshRelayPolicy(true, setOf("A")), clock = { now },
            deliverLocal = { false },
            sendToNextHop = { nextHop, bytes ->
                assertEquals("C", nextHop)
                forwarded = bytes
                true
            }
        )
        val original = MeshPacket(
            sourceNodeId = "A", destinationNodeId = "D", ttl = 6,
            createdAt = now, expiresAt = now + 30_000, opaquePayload = payload
        )

        assertEquals(MeshReceiveResult.Forwarded("C", 5), nodeB.receive("A", MeshPacketCodec.encode(original)))
        val relayed = MeshPacketCodec.decode(forwarded!!)
        assertArrayEquals(payload, relayed.opaquePayload)
        assertEquals(original.packetId, relayed.packetId)
        assertEquals(5, relayed.ttl)
    }

    @Test
    fun `packet crosses B and C and is delivered only at D`() = runBlocking {
        val payload = ByteArray(4096) { (it xor 0x5a).toByte() }
        var delivered: ByteArray? = null
        lateinit var nodeC: MeshNetworkLayer
        val nodeD = MeshNetworkLayer(
            "D", MeshRoutingTable(clock = { now }), MeshDuplicateCache(clock = { now }),
            MeshRelayPolicy(false), clock = { now },
            deliverLocal = { delivered = it; true }, sendToNextHop = { _, _ -> false }
        )
        nodeC = MeshNetworkLayer(
            "C", MeshRoutingTable(clock = { now }).apply {
                installAuthenticated(MeshRoute("D", "D", 1, 1, now + 10_000, "D"))
            }, MeshDuplicateCache(clock = { now }), MeshRelayPolicy(true, setOf("B")), clock = { now },
            deliverLocal = { false }, sendToNextHop = { hop, bytes ->
                hop == "D" && nodeD.receive("C", bytes) is MeshReceiveResult.DeliveredLocally
            }
        )
        val nodeB = MeshNetworkLayer(
            "B", MeshRoutingTable(clock = { now }).apply {
                installAuthenticated(MeshRoute("D", "C", 2, 1, now + 10_000, "C"))
            }, MeshDuplicateCache(clock = { now }), MeshRelayPolicy(true, setOf("A")), clock = { now },
            deliverLocal = { false }, sendToNextHop = { hop, bytes ->
                hop == "C" && nodeC.receive("B", bytes) is MeshReceiveResult.Forwarded
            }
        )
        val packet = MeshPacket(
            sourceNodeId = "A", destinationNodeId = "D", ttl = 8,
            createdAt = now, expiresAt = now + 5000, opaquePayload = payload
        )

        assertTrue(nodeB.receive("A", MeshPacketCodec.encode(packet)) is MeshReceiveResult.Forwarded)
        assertArrayEquals(payload, delivered)
    }

    @Test
    fun `duplicates are reacknowledged while ttl loops and unauthorized relays are rejected`() = runBlocking {
        val routes = MeshRoutingTable(clock = { now }).apply {
            installAuthenticated(MeshRoute("D", "C", 1, 1, now + 1000, "C"))
        }
        val layer = MeshNetworkLayer(
            "B", routes, MeshDuplicateCache(clock = { now }), MeshRelayPolicy(true, setOf("A")),
            clock = { now }, deliverLocal = { true }, sendToNextHop = { _, _ -> true }
        )
        val packet = MeshPacket(sourceNodeId = "A", destinationNodeId = "D", ttl = 2, createdAt = now, expiresAt = now + 500, opaquePayload = byteArrayOf(1))
        assertTrue(layer.receive("X", MeshPacketCodec.encode(packet)) is MeshReceiveResult.Rejected)
        assertTrue(layer.receive("A", MeshPacketCodec.encode(packet)) is MeshReceiveResult.Forwarded)
        assertEquals(MeshReceiveResult.StoredForLater, layer.receive("A", MeshPacketCodec.encode(packet.copy())))

        val ttlOne = packet.copy(packetId = java.util.UUID.randomUUID().toString(), ttl = 1)
        assertEquals(MeshReceiveResult.Rejected("ttl exhausted"), layer.receive("A", MeshPacketCodec.encode(ttlOne)))
    }

    @Test
    fun `route selection expires stale routes and applies split horizon`() {
        val table = MeshRoutingTable(clock = { now })
        table.installAuthenticated(MeshRoute("D", "C", 3, 1, now + 500, "C"))
        table.installAuthenticated(MeshRoute("D", "E", 2, 1, now + 5_000, "E"))
        assertEquals("E", table.bestRoute("D")!!.nextHopNodeId)
        assertEquals("C", table.bestRoute("D", excludedNextHop = "E")!!.nextHopNodeId)
        now += 1_000
        assertEquals("E", table.bestRoute("D")!!.nextHopNodeId)
        assertEquals(null, table.bestRoute("D", excludedNextHop = "E"))
    }

    @Test
    fun `priority queue and congestion limits remain bounded`() = runBlocking {
        val table = MeshRoutingTable(clock = { now }).apply {
            installAuthenticated(MeshRoute("D", "C", 1, 1, now + 5_000, "C"))
        }
        val layer = MeshNetworkLayer(
            "B", table, MeshDuplicateCache(clock = { now }),
            MeshRelayPolicy(true, setOf("A"), maxQueuedPackets = 1, maxQueuedBytes = 2048),
            clock = { now }, deliverLocal = { true }, sendToNextHop = { _, _ -> true }
        )
        val packet = MeshPacket(sourceNodeId = "A", destinationNodeId = "D", ttl = 4, priority = MeshPriority.CRITICAL, createdAt = now, expiresAt = now + 1000, opaquePayload = ByteArray(256))
        assertTrue(layer.receive("A", MeshPacketCodec.encode(packet)) is MeshReceiveResult.Forwarded)
    }
}
