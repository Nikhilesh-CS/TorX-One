package com.torxone.app.transport.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap
import java.util.PriorityQueue
import java.util.UUID

enum class MeshPriority(val wireValue: Int) { BULK(0), NORMAL(1), HIGH(2), CRITICAL(3) }

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val destinationNodeId: String,
    val ttl: Int,
    val priority: MeshPriority = MeshPriority.NORMAL,
    val createdAt: Long,
    val expiresAt: Long,
    val opaquePayload: ByteArray
)

object MeshPacketCodec {
    private const val MAGIC = 0x54584E31 // TXN1
    const val MAX_TTL = 16
    const val MAX_PACKET_BYTES = 1024 * 1024
    const val MAX_PAYLOAD_BYTES = MAX_PACKET_BYTES - 1024
    private val NODE_ID = Regex("[A-Za-z0-9._:-]{1,128}")

    fun isMeshPacket(bytes: ByteArray): Boolean =
        bytes.size >= 4 && java.nio.ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    fun encode(packet: MeshPacket): ByteArray {
        validate(packet)
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeUTF(packet.packetId)
                data.writeUTF(packet.sourceNodeId)
                data.writeUTF(packet.destinationNodeId)
                data.writeByte(packet.ttl)
                data.writeByte(packet.priority.wireValue)
                data.writeLong(packet.createdAt)
                data.writeLong(packet.expiresAt)
                data.writeInt(packet.opaquePayload.size)
                data.write(packet.opaquePayload)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_PACKET_BYTES) { "Mesh packet exceeds wire limit" }
        return bytes
    }

    fun decode(bytes: ByteArray): MeshPacket {
        require(bytes.size in 1..MAX_PACKET_BYTES) { "Invalid mesh packet size" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid mesh packet magic" }
            val packetId = data.readUTF()
            val source = data.readUTF()
            val destination = data.readUTF()
            val ttl = data.readUnsignedByte()
            val priorityValue = data.readUnsignedByte()
            val createdAt = data.readLong()
            val expiresAt = data.readLong()
            val payloadSize = data.readInt()
            require(payloadSize in 1..MAX_PAYLOAD_BYTES && payloadSize == data.available()) { "Invalid mesh payload size" }
            val payload = ByteArray(payloadSize)
            data.readFully(payload)
            require(data.available() == 0)
            val packet = MeshPacket(
                packetId, source, destination, ttl,
                MeshPriority.entries.firstOrNull { it.wireValue == priorityValue }
                    ?: throw IllegalArgumentException("Invalid mesh priority"),
                createdAt, expiresAt, payload
            )
            validate(packet)
            packet
        }
    }

    private fun validate(packet: MeshPacket) {
        require(runCatching { UUID.fromString(packet.packetId) }.isSuccess) { "Invalid packet ID" }
        require(packet.sourceNodeId.matches(NODE_ID) && packet.destinationNodeId.matches(NODE_ID)) { "Invalid node ID" }
        require(packet.sourceNodeId != packet.destinationNodeId) { "Source and destination must differ" }
        require(packet.ttl in 1..MAX_TTL) { "Invalid mesh TTL" }
        require(packet.createdAt > 0 && packet.expiresAt > packet.createdAt) { "Invalid packet lifetime" }
        require(packet.opaquePayload.size in 1..MAX_PAYLOAD_BYTES) { "Invalid opaque payload size" }
    }
}

data class MeshRoute(
    val destinationNodeId: String,
    val nextHopNodeId: String,
    val hopCount: Int,
    val sequence: Long,
    val expiresAt: Long,
    val learnedFromNodeId: String
)

/** Bounded route table. It selects one deterministic next hop and never floods. */
class MeshRoutingTable(
    private val maxRoutes: Int = 4096,
    private val maxHops: Int = MeshPacketCodec.MAX_TTL,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val routes = LinkedHashMap<String, MutableList<MeshRoute>>()

    @Synchronized
    fun installAuthenticated(route: MeshRoute): Boolean {
        val now = clock()
        if (route.hopCount !in 1..maxHops || route.expiresAt <= now ||
            route.destinationNodeId == route.nextHopNodeId && route.hopCount != 1
        ) return false
        pruneExpired(now)
        val candidates = routes.getOrPut(route.destinationNodeId) { mutableListOf() }
        val existing = candidates.indexOfFirst { it.nextHopNodeId == route.nextHopNodeId }
        if (existing >= 0) {
            val previous = candidates[existing]
            if (route.sequence < previous.sequence) return false
            candidates[existing] = route
        } else {
            candidates += route
        }
        trim()
        return true
    }

    @Synchronized
    fun bestRoute(destinationNodeId: String, excludedNextHop: String? = null): MeshRoute? {
        pruneExpired(clock())
        return routes[destinationNodeId]
            ?.asSequence()
            ?.filter { it.nextHopNodeId != excludedNextHop }
            ?.sortedWith(compareBy<MeshRoute> { it.hopCount }.thenByDescending { it.sequence }.thenBy { it.nextHopNodeId })
            ?.firstOrNull()
    }

    @Synchronized
    fun removePeer(peerNodeId: String) {
        routes.values.forEach { candidates -> candidates.removeAll { it.nextHopNodeId == peerNodeId || it.learnedFromNodeId == peerNodeId } }
        routes.entries.removeAll { it.value.isEmpty() }
    }

    @Synchronized
    fun snapshot(): List<MeshRoute> {
        pruneExpired(clock())
        return routes.values.flatten()
    }

    private fun pruneExpired(now: Long) {
        routes.values.forEach { it.removeAll { route -> route.expiresAt <= now } }
        routes.entries.removeAll { it.value.isEmpty() }
    }

    private fun trim() {
        while (routes.values.sumOf { it.size } > maxRoutes) {
            val victim = routes.values.flatten().minWithOrNull(compareBy<MeshRoute> { it.sequence }.thenBy { it.expiresAt }) ?: return
            routes[victim.destinationNodeId]?.remove(victim)
            if (routes[victim.destinationNodeId].isNullOrEmpty()) routes.remove(victim.destinationNodeId)
        }
    }
}

class MeshDuplicateCache(
    private val capacity: Int = 8192,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    @Synchronized
    fun markIfNew(packetId: String, expiresAt: Long): Boolean {
        val now = clock()
        seen.entries.removeAll { it.value <= now }
        if (seen.containsKey(packetId)) return false
        seen[packetId] = expiresAt
        return true
    }
}

class MeshRelayPolicy(
    val relayEnabled: Boolean = false,
    allowedPeerNodeIds: Set<String> = emptySet(),
    val maxQueuedPackets: Int = 256,
    val maxQueuedBytes: Long = 16L * 1024 * 1024
) {
    private val allowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(allowedPeerNodeIds) }
    fun allow(peerNodeId: String) { if (peerNodeId.isNotBlank()) allowed += peerNodeId }
    fun revoke(peerNodeId: String) { allowed -= peerNodeId }
    fun permits(peerNodeId: String): Boolean = relayEnabled && peerNodeId in allowed
}

sealed interface MeshReceiveResult {
    data object DeliveredLocally : MeshReceiveResult
    data class Forwarded(val nextHopNodeId: String, val remainingTtl: Int) : MeshReceiveResult
    data object StoredForLater : MeshReceiveResult
    data class Rejected(val reason: String) : MeshReceiveResult
}

/**
 * Offline mesh forwarding authority. Payloads remain opaque, forwarding uses a
 * single route, and queues are bounded by packet count and byte size.
 */
class MeshNetworkLayer(
    private val localNodeId: String,
    private val routingTable: MeshRoutingTable,
    private val duplicateCache: MeshDuplicateCache,
    private val relayPolicy: MeshRelayPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deliverLocal: suspend (ByteArray) -> Boolean,
    private val sendToNextHop: suspend (String, ByteArray) -> Boolean,
    private val localNodeIdProvider: () -> String = { localNodeId },
    private val durableRelayStore: DurableRelayStore? = null,
    private val sendCustodyAck: suspend (String, ByteArray) -> Boolean = { _, _ -> false }
) {
    private data class Queued(val packet: MeshPacket, val encoded: ByteArray)
    private val queue = PriorityQueue<Queued>(
        compareByDescending<Queued> { it.packet.priority.wireValue }.thenBy { it.packet.createdAt }
    )
    private var queuedBytes = 0L

    suspend fun receive(fromPeerNodeId: String, encodedPacket: ByteArray): MeshReceiveResult {
        val packet = try { MeshPacketCodec.decode(encodedPacket) }
        catch (error: Exception) { return MeshReceiveResult.Rejected("malformed: ${error.message}") }
        val now = clock()
        if (packet.expiresAt <= now) return MeshReceiveResult.Rejected("expired")
        val currentLocalNodeId = localNodeIdProvider()
        if (packet.destinationNodeId != currentLocalNodeId && !relayPolicy.permits(fromPeerNodeId)) {
            return MeshReceiveResult.Rejected("relay not permitted")
        }
        if (!duplicateCache.markIfNew(packet.packetId, packet.expiresAt)) {
            acknowledgeCustody(fromPeerNodeId, packet)
            return MeshReceiveResult.StoredForLater
        }
        if (packet.destinationNodeId == currentLocalNodeId) {
            return if (deliverLocal(packet.opaquePayload.copyOf())) {
                acknowledgeCustody(fromPeerNodeId, packet)
                MeshReceiveResult.DeliveredLocally
            } else MeshReceiveResult.Rejected("local delivery rejected")
        }
        if (packet.ttl <= 1) return MeshReceiveResult.Rejected("ttl exhausted")
        val route = routingTable.bestRoute(packet.destinationNodeId, excludedNextHop = fromPeerNodeId)
            ?: return when (durableRelayStore?.enqueue(fromPeerNodeId, encodedPacket)) {
                RelayEnqueueResult.Stored, RelayEnqueueResult.Duplicate -> {
                    acknowledgeCustody(fromPeerNodeId, packet)
                    MeshReceiveResult.StoredForLater
                }
                is RelayEnqueueResult.Rejected -> MeshReceiveResult.Rejected("no route")
                null -> MeshReceiveResult.Rejected("no route")
            }
        val forwarded = packet.copy(ttl = packet.ttl - 1, opaquePayload = packet.opaquePayload.copyOf())
        val encodedForward = MeshPacketCodec.encode(forwarded)
        if (!enqueue(forwarded, encodedForward)) return MeshReceiveResult.Rejected("congestion limit")
        val next = dequeue() ?: return MeshReceiveResult.Rejected("queue unavailable")
        return if (sendToNextHop(route.nextHopNodeId, next.encoded)) {
            acknowledgeCustody(fromPeerNodeId, packet)
            MeshReceiveResult.Forwarded(route.nextHopNodeId, forwarded.ttl)
        } else {
            when (durableRelayStore?.enqueue(fromPeerNodeId, encodedPacket)) {
                RelayEnqueueResult.Stored, RelayEnqueueResult.Duplicate -> {
                    acknowledgeCustody(fromPeerNodeId, packet)
                    MeshReceiveResult.StoredForLater
                }
                else -> MeshReceiveResult.Rejected("next hop rejected")
            }
        }
    }

    suspend fun retryStored(limit: Int = 32): Int {
        val store = durableRelayStore ?: return 0
        var forwarded = 0
        for (entity in store.due(limit)) {
            val packet = try { MeshPacketCodec.decode(entity.encodedPacket) }
            catch (_: Exception) {
                store.acknowledge(entity.packetId, entity.expiresAt)
                continue
            }
            val route = routingTable.bestRoute(packet.destinationNodeId, entity.ingressPeerNodeId)
            if (route == null) {
                store.recordForwardFailure(entity, "no route")
                continue
            }
            if (sendToNextHop(route.nextHopNodeId, entity.encodedPacket)) {
                store.recordForwardAccepted(entity, route.nextHopNodeId)
                forwarded++
            } else {
                store.recordForwardFailure(entity, "next hop rejected")
            }
        }
        return forwarded
    }

    suspend fun receiveCustodyAck(fromPeerNodeId: String, bytes: ByteArray): Boolean {
        val ack = try { MeshCustodyAckCodec.decode(bytes) } catch (_: Exception) { return false }
        return durableRelayStore?.acknowledgeFromPeer(ack.packetId, fromPeerNodeId, ack.expiresAt) ?: false
    }

    private suspend fun acknowledgeCustody(peerNodeId: String, packet: MeshPacket) {
        sendCustodyAck(peerNodeId, MeshCustodyAckCodec.encode(MeshCustodyAck(packet.packetId, packet.expiresAt)))
    }

    @Synchronized
    private fun enqueue(packet: MeshPacket, encoded: ByteArray): Boolean {
        if (queue.size >= relayPolicy.maxQueuedPackets || queuedBytes + encoded.size > relayPolicy.maxQueuedBytes) return false
        queue += Queued(packet, encoded)
        queuedBytes += encoded.size
        return true
    }

    @Synchronized
    private fun dequeue(): Queued? = queue.poll()?.also { queuedBytes -= it.encoded.size }
}
