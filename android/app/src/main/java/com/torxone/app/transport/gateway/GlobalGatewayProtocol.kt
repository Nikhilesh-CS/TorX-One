package com.torxone.app.transport.gateway

import com.torxone.app.transport.mesh.MeshPriority
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class GatewaySubmission(
    val packetId: String = UUID.randomUUID().toString(),
    val destinationGatewayOnion: String,
    val destinationNodeId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val maxGatewayHops: Int = 2,
    val priority: MeshPriority = MeshPriority.NORMAL,
    val opaquePayload: ByteArray
)

/** Phone-to-local-gateway request. The local gateway adds authenticated transit metadata before Tor forwarding. */
object GatewaySubmissionCodec {
    private const val MAGIC = 0x54584731 // TXG1
    const val MAX_PACKET_BYTES = 1024 * 1024
    const val MAX_PAYLOAD_BYTES = MAX_PACKET_BYTES - 512
    const val MAX_GATEWAY_HOPS = 4
    private val ONION = Regex("[a-z2-7]{56}\\.onion")
    private val NODE = Regex("[A-Za-z0-9._:-]{1,128}")

    fun encode(packet: GatewaySubmission): ByteArray {
        validate(packet)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeUTF(packet.packetId)
            data.writeUTF(packet.destinationGatewayOnion)
            data.writeUTF(packet.destinationNodeId)
            data.writeLong(packet.createdAt)
            data.writeLong(packet.expiresAt)
            data.writeByte(packet.maxGatewayHops)
            data.writeByte(packet.priority.wireValue)
            data.writeInt(packet.opaquePayload.size)
            data.write(packet.opaquePayload)
        }
        return output.toByteArray().also { require(it.size <= MAX_PACKET_BYTES) }
    }

    fun decode(bytes: ByteArray): GatewaySubmission {
        require(bytes.size in 1..MAX_PACKET_BYTES)
        val packet = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid global gateway packet" }
            val id = data.readUTF(); val onion = data.readUTF(); val node = data.readUTF()
            val created = data.readLong(); val expires = data.readLong()
            val hops = data.readUnsignedByte(); val priority = data.readUnsignedByte()
            val size = data.readInt()
            require(size in 1..MAX_PAYLOAD_BYTES && size == data.available())
            val payload = ByteArray(size).also(data::readFully)
            require(data.available() == 0)
            GatewaySubmission(id, onion, node, created, expires, hops,
                MeshPriority.entries.firstOrNull { it.wireValue == priority }
                    ?: throw IllegalArgumentException("Invalid gateway priority"), payload)
        }
        validate(packet)
        return packet
    }

    fun isGatewaySubmission(bytes: ByteArray): Boolean =
        bytes.size >= 4 && java.nio.ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    private fun validate(packet: GatewaySubmission) {
        require(runCatching { UUID.fromString(packet.packetId) }.isSuccess)
        require(packet.destinationGatewayOnion.matches(ONION)) { "Invalid destination gateway onion" }
        require(packet.destinationNodeId.matches(NODE)) { "Invalid destination node" }
        require(packet.createdAt > 0 && packet.expiresAt > packet.createdAt)
        require(packet.expiresAt - packet.createdAt <= MAX_LIFETIME_MS) { "Gateway lifetime too long" }
        require(packet.maxGatewayHops in 1..MAX_GATEWAY_HOPS)
        require(packet.opaquePayload.size in 1..MAX_PAYLOAD_BYTES)
    }

    const val MAX_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
}

data class GatewayRoute(
    val destinationNodeId: String,
    val gatewayOnion: String,
    val expiresAt: Long,
    val learnedFrom: String
)

class GatewayRouteDirectory(private val clock: () -> Long = System::currentTimeMillis) {
    private val routes = java.util.concurrent.ConcurrentHashMap<String, GatewayRoute>()
    fun install(route: GatewayRoute): Boolean {
        if (route.destinationNodeId.isBlank() || route.expiresAt <= clock() ||
            !route.gatewayOnion.matches(Regex("[a-z2-7]{56}\\.onion")) || route.learnedFrom.isBlank()
        ) return false
        routes.compute(route.destinationNodeId) { _, old -> if (old == null || route.expiresAt >= old.expiresAt) route else old }
        return routes[route.destinationNodeId] == route
    }
    fun resolve(nodeId: String): GatewayRoute? = routes[nodeId]?.takeIf { it.expiresAt > clock() }
        ?: routes.remove(nodeId).let { null }
}

/** Bounded duplicate and expiry guard shared by gateway ingress implementations. */
class GatewayIngressPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 8192
) {
    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > capacity
    }
    @Synchronized fun accept(packet: GatewaySubmission): Boolean {
        val now = clock(); seen.entries.removeAll { it.value <= now }
        if (packet.createdAt > now + MAX_CLOCK_SKEW_MS || packet.expiresAt <= now || packet.packetId in seen) return false
        seen[packet.packetId] = packet.expiresAt
        return true
    }
    companion object { const val MAX_CLOCK_SKEW_MS = 5 * 60_000L }
}
