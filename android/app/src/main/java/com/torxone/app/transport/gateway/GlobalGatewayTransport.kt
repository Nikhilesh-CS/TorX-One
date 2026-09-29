package com.torxone.app.transport.gateway

import com.torxone.app.transport.AddressableTransport
import com.torxone.app.transport.Transport
import com.torxone.app.transport.TransportAvailability
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import com.torxone.app.transport.halow.HaLowConnectionState
import com.torxone.app.transport.halow.HaLowGatewayManager
import com.torxone.app.transport.halow.HaLowGatewayProtocol
import com.torxone.app.transport.mesh.MeshPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/** Sends opaque TorX payloads to a paired local gateway for onion-to-onion forwarding. */
class GlobalGatewayTransport(
    private val localGateway: HaLowGatewayManager,
    private val routes: GatewayRouteDirectory,
    private val nodeIdForAddress: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) : Transport, AddressableTransport {
    override val type = TransportType.GATEWAY
    private val stream = AtomicInteger()

    override fun availability(): Flow<TransportAvailability> = localGateway.state.map {
        if (it is HaLowConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("No authenticated local TorX gateway")
    }

    override fun canRoute(destination: TransportDestination): Boolean {
        val node = destination.hints[DESTINATION_NODE_HINT] ?: nodeIdForAddress(destination.address) ?: return false
        return destination.hints[DESTINATION_GATEWAY_HINT]?.let(::validOnion) == true || routes.resolve(node) != null
    }

    override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
        val node = destination.hints[DESTINATION_NODE_HINT] ?: nodeIdForAddress(destination.address)
            ?: return TransportResult.Failed(type, "Gateway destination node missing")
        val onion = destination.hints[DESTINATION_GATEWAY_HINT]?.takeIf(::validOnion)
            ?: routes.resolve(node)?.gatewayOnion
            ?: return TransportResult.Failed(type, "No remote gateway route")
        val now = clock()
        val priority = when (destination.hints[PRIORITY_HINT]) {
            "critical" -> MeshPriority.CRITICAL; "high" -> MeshPriority.HIGH; "bulk" -> MeshPriority.BULK
            else -> MeshPriority.NORMAL
        }
        val submission = runCatching { GatewaySubmissionCodec.encode(GatewaySubmission(
            destinationGatewayOnion = onion, destinationNodeId = node, createdAt = now,
            expiresAt = now + DEFAULT_LIFETIME_MS, priority = priority, opaquePayload = payload.copyOf()
        )) }.getOrElse { return TransportResult.Failed(type, it.message ?: "Invalid gateway packet") }
        val frame = HaLowGatewayProtocol.encode(HaLowGatewayProtocol.Frame(
            HaLowGatewayProtocol.Kind.GLOBAL_GATEWAY_SUBMIT, stream.incrementAndGet(), submission
        ))
        return if (localGateway.send(frame)) TransportResult.Accepted(type)
        else TransportResult.Failed(type, "Local gateway rejected global packet")
    }

    companion object {
        const val DESTINATION_NODE_HINT = "gatewayNodeId"
        const val DESTINATION_GATEWAY_HINT = "gatewayOnion"
        const val PRIORITY_HINT = "gatewayPriority"
        const val DEFAULT_LIFETIME_MS = 24L * 60 * 60 * 1000
        private fun validOnion(value: String) = value.matches(Regex("[a-z2-7]{56}\\.onion"))
    }
}
