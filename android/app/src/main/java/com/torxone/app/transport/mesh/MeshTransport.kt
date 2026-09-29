package com.torxone.app.transport.mesh

import com.torxone.app.transport.AddressableTransport
import com.torxone.app.transport.Transport
import com.torxone.app.transport.TransportAvailability
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

fun interface MeshPeerLink {
    suspend fun sendToAuthenticatedPeer(peer: MeshPeer, encodedPacket: ByteArray): Boolean
}

/** TransportRouter adapter for destinations explicitly carrying a mesh node ID hint. */
class MeshTransport(
    private val localNodeIdProvider: () -> String?,
    private val routingTable: MeshRoutingTable,
    private val peerDirectory: MeshPeerDirectory,
    private val peerLink: MeshPeerLink,
    private val nodeIdForAddress: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) : Transport, AddressableTransport {
    override val type: TransportType = TransportType.RELAY
    private val availability = MutableStateFlow<TransportAvailability>(TransportAvailability.Unavailable("No mesh routes"))
    override fun availability(): Flow<TransportAvailability> = availability

    override fun canRoute(destination: TransportDestination): Boolean {
        val nodeId = destination.hints[MESH_NODE_ID_HINT] ?: nodeIdForAddress(destination.address) ?: return false
        val route = routingTable.bestRoute(nodeId) ?: return false
        val available = peerDirectory.get(route.nextHopNodeId) != null
        availability.value = if (available) TransportAvailability.Available else TransportAvailability.Unavailable("Mesh next hop unavailable")
        return available
    }

    override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
        val source = localNodeIdProvider()?.takeIf(String::isNotBlank)
            ?: return TransportResult.Failed(type, "Local mesh identity unavailable")
        val target = destination.hints[MESH_NODE_ID_HINT] ?: nodeIdForAddress(destination.address)
            ?: return TransportResult.Failed(type, "Mesh destination hint missing")
        val route = routingTable.bestRoute(target)
            ?: return TransportResult.Failed(type, "No mesh route")
        val peer = peerDirectory.get(route.nextHopNodeId)
            ?: return TransportResult.Failed(type, "Mesh next hop disconnected")
        val now = clock()
        val priority = when (destination.hints[MESH_PRIORITY_HINT]) {
            "critical" -> MeshPriority.CRITICAL
            "high" -> MeshPriority.HIGH
            "bulk" -> MeshPriority.BULK
            else -> MeshPriority.NORMAL
        }
        val packet = MeshPacket(
            packetId = UUID.randomUUID().toString(), sourceNodeId = source, destinationNodeId = target,
            ttl = destination.hints[MESH_TTL_HINT]?.toIntOrNull()?.coerceIn(1, MeshPacketCodec.MAX_TTL) ?: 8,
            priority = priority, createdAt = now, expiresAt = now + DEFAULT_PACKET_LIFETIME_MS,
            opaquePayload = payload.copyOf()
        )
        return if (peerLink.sendToAuthenticatedPeer(peer, MeshPacketCodec.encode(packet))) {
            TransportResult.Accepted(type)
        } else TransportResult.Failed(type, "Mesh next hop rejected packet")
    }

    companion object {
        const val MESH_NODE_ID_HINT = "meshNodeId"
        const val MESH_PRIORITY_HINT = "meshPriority"
        const val MESH_TTL_HINT = "meshTtl"
        const val DEFAULT_PACKET_LIFETIME_MS = 5 * 60_000L
    }
}
