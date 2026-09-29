package com.torxone.app.transport.mesh

import com.torxone.app.data.dao.RelayQueueDao
import com.torxone.app.data.entity.RelayPacketEntity
import com.torxone.app.data.entity.RelayReceiptEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RelayStorageLimits(
    val maxPackets: Int = 2048,
    val maxBytes: Long = 128L * 1024 * 1024,
    val maxPacketsPerSource: Int = 256,
    val maxBytesPerSource: Long = 16L * 1024 * 1024,
    val maxAttempts: Int = 12,
    val receiptLifetimeMs: Long = 24 * 60 * 60 * 1000L
)

sealed interface RelayEnqueueResult {
    data object Stored : RelayEnqueueResult
    data object Duplicate : RelayEnqueueResult
    data class Rejected(val reason: String) : RelayEnqueueResult
}

class RelayAbuseGuard(
    private val maxPacketsPerMinute: Int = 120,
    private val maxBytesPerMinute: Long = 8L * 1024 * 1024,
    private val blockDurationMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Window(var startedAt: Long, var packets: Int, var bytes: Long, var blockedUntil: Long = 0)
    private val windows = java.util.concurrent.ConcurrentHashMap<String, Window>()

    fun allow(peerNodeId: String, packetBytes: Int): Boolean {
        val now = clock()
        val window = windows.compute(peerNodeId) { _, current ->
            val value = if (current == null || now - current.startedAt >= 60_000 ||
                (current.blockedUntil > 0 && now >= current.blockedUntil)
            ) Window(now, 0, 0) else current
            if (now < value.blockedUntil) return@compute value
            value.packets++
            value.bytes += packetBytes
            if (value.packets > maxPacketsPerMinute || value.bytes > maxBytesPerMinute) {
                value.blockedUntil = now + blockDurationMs
            }
            value
        }!!
        return now >= window.blockedUntil
    }
}

/** SQLCipher-backed store-carry-forward queue for opaque mesh packets. */
class DurableRelayStore(
    private val dao: RelayQueueDao,
    private val relayPolicy: MeshRelayPolicy,
    private val abuseGuard: RelayAbuseGuard = RelayAbuseGuard(),
    private val limits: RelayStorageLimits = RelayStorageLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun enqueue(fromPeerNodeId: String, encodedPacket: ByteArray): RelayEnqueueResult = mutex.withLock {
        val packet = try { MeshPacketCodec.decode(encodedPacket) }
        catch (error: Exception) { return@withLock RelayEnqueueResult.Rejected("malformed") }
        val now = clock()
        if (!relayPolicy.permits(fromPeerNodeId)) return@withLock RelayEnqueueResult.Rejected("relay not permitted")
        if (!abuseGuard.allow(fromPeerNodeId, encodedPacket.size)) return@withLock RelayEnqueueResult.Rejected("peer rate limited")
        if (packet.expiresAt <= now) return@withLock RelayEnqueueResult.Rejected("expired")
        if (packet.ttl <= 1) return@withLock RelayEnqueueResult.Rejected("ttl exhausted")
        if (dao.getReceipt(packet.packetId, now) != null || dao.getPacket(packet.packetId) != null) {
            return@withLock RelayEnqueueResult.Duplicate
        }
        dao.deleteExpiredPackets(now)
        dao.deleteExpiredReceipts(now)
        if (dao.sourcePacketCount(packet.sourceNodeId) >= limits.maxPacketsPerSource ||
            dao.sourceBytes(packet.sourceNodeId) + encodedPacket.size > limits.maxBytesPerSource
        ) return@withLock RelayEnqueueResult.Rejected("source quota exceeded")

        while (dao.packetCount() >= limits.maxPackets || dao.totalBytes() + encodedPacket.size > limits.maxBytes) {
            if (dao.evictLowestPriority() == 0) return@withLock RelayEnqueueResult.Rejected("relay storage full")
        }
        val storedPacket = packet.copy(ttl = packet.ttl - 1, opaquePayload = packet.opaquePayload.copyOf())
        val storedBytes = MeshPacketCodec.encode(storedPacket)
        val entity = RelayPacketEntity(
            packetId = storedPacket.packetId,
            sourceNodeId = storedPacket.sourceNodeId,
            destinationNodeId = storedPacket.destinationNodeId,
            ingressPeerNodeId = fromPeerNodeId,
            encodedPacket = storedBytes,
            packetBytes = storedBytes.size.toLong(),
            priority = storedPacket.priority.wireValue,
            remainingTtl = storedPacket.ttl,
            nextAttemptAt = now,
            createdAt = storedPacket.createdAt,
            expiresAt = storedPacket.expiresAt
        )
        if (dao.insertPacket(entity) == -1L) RelayEnqueueResult.Duplicate else RelayEnqueueResult.Stored
    }

    suspend fun due(limit: Int = 32): List<RelayPacketEntity> {
        require(limit in 1..128)
        val now = clock()
        dao.deleteExpiredPackets(now)
        return dao.getDue(now, limit)
    }

    suspend fun recordForwardFailure(packet: RelayPacketEntity, reason: String) {
        val attempts = packet.attemptCount + 1
        if (attempts >= limits.maxAttempts || packet.expiresAt <= clock()) {
            dao.deletePacket(packet.packetId)
            return
        }
        val backoff = (1_000L shl attempts.coerceAtMost(10)).coerceAtMost(15 * 60_000L)
        dao.updateAttempt(packet.packetId, "QUEUED", attempts, clock() + backoff, reason.take(256), null)
    }

    suspend fun recordForwardAccepted(packet: RelayPacketEntity, nextHopNodeId: String) {
        val attempts = packet.attemptCount + 1
        if (attempts >= limits.maxAttempts) {
            dao.deletePacket(packet.packetId)
            return
        }
        dao.updateAttempt(
            packet.packetId,
            "QUEUED",
            attempts,
            clock() + CUSTODY_ACK_TIMEOUT_MS,
            "awaiting custody acknowledgement",
            nextHopNodeId
        )
    }

    suspend fun acknowledge(packetId: String, expiresAt: Long) = mutex.withLock {
        val now = clock()
        dao.deletePacket(packetId)
        dao.insertReceipt(
            RelayReceiptEntity(packetId, now, minOf(expiresAt, now + limits.receiptLifetimeMs))
        )
    }

    suspend fun acknowledgeFromPeer(packetId: String, peerNodeId: String, expiresAt: Long): Boolean = mutex.withLock {
        val packet = dao.getPacket(packetId) ?: return@withLock false
        if (packet.lastNextHopNodeId != peerNodeId || packet.expiresAt != expiresAt) return@withLock false
        val now = clock()
        dao.deletePacket(packetId)
        dao.insertReceipt(RelayReceiptEntity(packetId, now, minOf(packet.expiresAt, now + limits.receiptLifetimeMs)))
        true
    }

    suspend fun prune() {
        val now = clock()
        dao.deleteExpiredPackets(now)
        dao.deleteExpiredReceipts(now)
    }

    companion object { const val CUSTODY_ACK_TIMEOUT_MS = 30_000L }
}
