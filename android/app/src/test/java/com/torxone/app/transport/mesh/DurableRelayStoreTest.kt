package com.torxone.app.transport.mesh

import com.torxone.app.data.dao.RelayQueueDao
import com.torxone.app.data.entity.RelayPacketEntity
import com.torxone.app.data.entity.RelayReceiptEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DurableRelayStoreTest {
    private class FakeDao : RelayQueueDao {
        val packets = ConcurrentHashMap<String, RelayPacketEntity>()
        val receipts = ConcurrentHashMap<String, RelayReceiptEntity>()
        override suspend fun getPacket(packetId: String) = packets[packetId]
        override suspend fun packetCount() = packets.size
        override suspend fun totalBytes() = packets.values.sumOf { it.packetBytes }
        override suspend fun sourcePacketCount(sourceNodeId: String) = packets.values.count { it.sourceNodeId == sourceNodeId }
        override suspend fun sourceBytes(sourceNodeId: String) = packets.values.filter { it.sourceNodeId == sourceNodeId }.sumOf { it.packetBytes }
        override suspend fun insertPacket(packet: RelayPacketEntity): Long = if (packets.putIfAbsent(packet.packetId, packet) == null) 1 else -1
        override suspend fun getDue(now: Long, limit: Int) = packets.values
            .filter { it.status == "QUEUED" && it.nextAttemptAt <= now && it.expiresAt > now }
            .sortedWith(compareByDescending<RelayPacketEntity> { it.priority }.thenBy { it.createdAt }).take(limit)
        override suspend fun updateAttempt(packetId: String, status: String, attemptCount: Int, nextAttemptAt: Long, lastError: String?, lastNextHopNodeId: String?) {
            packets.computeIfPresent(packetId) { _, p -> p.copy(status = status, attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, lastError = lastError, lastNextHopNodeId = lastNextHopNodeId) }
        }
        override suspend fun deletePacket(packetId: String) { packets.remove(packetId) }
        override suspend fun deleteExpiredPackets(now: Long): Int {
            val ids = packets.values.filter { it.expiresAt <= now }.map { it.packetId }
            ids.forEach(packets::remove); return ids.size
        }
        override suspend fun evictLowestPriority(): Int {
            val victim = packets.values.minWithOrNull(compareBy<RelayPacketEntity> { it.priority }.thenBy { it.createdAt }) ?: return 0
            packets.remove(victim.packetId); return 1
        }
        override suspend fun getReceipt(packetId: String, now: Long) = receipts[packetId]?.takeIf { it.expiresAt > now }
        override suspend fun insertReceipt(receipt: RelayReceiptEntity) { receipts[receipt.packetId] = receipt }
        override suspend fun deleteExpiredReceipts(now: Long): Int {
            val ids = receipts.values.filter { it.expiresAt <= now }.map { it.packetId }
            ids.forEach(receipts::remove); return ids.size
        }
    }

    private var now = 10_000L
    private fun packet(source: String = "A", priority: MeshPriority = MeshPriority.NORMAL) = MeshPacket(
        sourceNodeId = source, destinationNodeId = "D", ttl = 8, priority = priority,
        createdAt = now, expiresAt = now + 60_000, opaquePayload = ByteArray(512) { 7 }
    )

    @Test
    fun `packet is stored without route and forwarded after contact`() = runBlocking {
        val dao = FakeDao()
        val policy = MeshRelayPolicy(true, setOf("A"))
        val store = DurableRelayStore(dao, policy, clock = { now })
        val routes = MeshRoutingTable(clock = { now })
        var forwarded: ByteArray? = null
        val layer = MeshNetworkLayer(
            "B", routes, MeshDuplicateCache(clock = { now }), policy, clock = { now },
            deliverLocal = { false }, sendToNextHop = { _, bytes -> forwarded = bytes; true },
            durableRelayStore = store
        )
        val original = packet()
        assertEquals(MeshReceiveResult.StoredForLater, layer.receive("A", MeshPacketCodec.encode(original)))
        assertEquals(1, dao.packets.size)

        routes.installAuthenticated(MeshRoute("D", "C", 2, 1, now + 30_000, "C"))
        assertEquals(1, layer.retryStored())
        assertEquals(1, dao.packets.size)
        assertEquals("C", dao.packets[original.packetId]!!.lastNextHopNodeId)
        val ack = MeshCustodyAckCodec.encode(MeshCustodyAck(original.packetId, original.expiresAt))
        assertTrue(!layer.receiveCustodyAck("X", ack))
        assertTrue(layer.receiveCustodyAck("C", ack))
        assertEquals(0, dao.packets.size)
        assertTrue(dao.receipts.containsKey(original.packetId))
        val relayed = MeshPacketCodec.decode(forwarded!!)
        assertEquals(7, relayed.ttl)
        assertArrayEquals(original.opaquePayload, relayed.opaquePayload)
        assertEquals(RelayEnqueueResult.Duplicate, store.enqueue("A", MeshPacketCodec.encode(original)))
    }

    @Test
    fun `per-source quota blocks abusive storage`() = runBlocking {
        val dao = FakeDao()
        val policy = MeshRelayPolicy(true, setOf("A"))
        val store = DurableRelayStore(
            dao, policy,
            limits = RelayStorageLimits(maxPackets = 10, maxBytes = 1_000_000, maxPacketsPerSource = 1, maxBytesPerSource = 1_000_000),
            clock = { now }
        )
        assertEquals(RelayEnqueueResult.Stored, store.enqueue("A", MeshPacketCodec.encode(packet())))
        val second = packet().copy(packetId = java.util.UUID.randomUUID().toString())
        assertEquals(RelayEnqueueResult.Rejected("source quota exceeded"), store.enqueue("A", MeshPacketCodec.encode(second)))
    }

    @Test
    fun `abuse guard blocks burst until cooldown expires`() {
        val guard = RelayAbuseGuard(maxPacketsPerMinute = 1, maxBytesPerMinute = 1000, blockDurationMs = 5000, clock = { now })
        assertTrue(guard.allow("peer", 100))
        assertTrue(!guard.allow("peer", 100))
        now += 5001
        assertTrue(guard.allow("peer", 100))
    }
}
