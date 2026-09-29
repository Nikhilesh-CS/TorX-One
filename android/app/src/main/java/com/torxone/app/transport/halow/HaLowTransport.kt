package com.torxone.app.transport.halow

import com.torxone.app.transport.AddressableTransport
import com.torxone.app.transport.Transport
import com.torxone.app.transport.TransportAvailability
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

class HaLowTransport(private val manager: HaLowGatewayManager) : Transport, AddressableTransport {
    override val type = TransportType.WIFI_HALOW
    private val streamIds = AtomicInteger()

    override fun availability(): Flow<TransportAvailability> = manager.state.map {
        if (it is HaLowConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("TorX HaLow gateway is not authenticated and ready")
    }

    override fun canRoute(destination: TransportDestination): Boolean =
        !(destination.hints[HALOW_NODE_ID_HINT] ?: destination.hints[MESH_NODE_ID_HINT]).isNullOrBlank()

    override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
        val nodeId = destination.hints[HALOW_NODE_ID_HINT] ?: destination.hints[MESH_NODE_ID_HINT]
            ?: return TransportResult.Failed(type, "HaLow destination hint missing")
        val routed = runCatching { HaLowGatewayProtocol.encodeRoutedPayload(nodeId, payload) }
            .getOrElse { return TransportResult.Failed(type, it.message ?: "Invalid HaLow payload") }
        val frame = HaLowGatewayProtocol.encode(HaLowGatewayProtocol.Frame(
            HaLowGatewayProtocol.Kind.SEND_PACKET, streamIds.incrementAndGet(), routed
        ))
        return if (manager.send(frame)) TransportResult.Accepted(type)
        else TransportResult.Failed(type, "TorX HaLow gateway rejected packet")
    }

    companion object {
        const val HALOW_NODE_ID_HINT = "halowNodeId"
        const val MESH_NODE_ID_HINT = "meshNodeId"
    }
}
