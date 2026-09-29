package com.torxone.app.transport.lora

import com.torxone.app.transport.AddressableTransport
import com.torxone.app.transport.Transport
import com.torxone.app.transport.TransportAvailability
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Low bandwidth adapter. It carries only explicitly routed, already encrypted TorX packets. */
class LoRaTransport(private val radioManager: TorXRadioManager) : Transport, AddressableTransport {
    override val type = TransportType.LORA
    private val sequence = AtomicInteger()

    override fun availability(): Flow<TransportAvailability> = radioManager.state.map {
        if (it is RadioConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("TorX Radio is not authenticated and ready")
    }

    override fun canRoute(destination: TransportDestination): Boolean =
        !(destination.hints[LORA_NODE_ID_HINT] ?: destination.hints[MESH_NODE_ID_HINT]).isNullOrBlank()

    override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
        val nodeId = destination.hints[LORA_NODE_ID_HINT] ?: destination.hints[MESH_NODE_ID_HINT]
            ?: return TransportResult.Failed(type, "LoRa destination hint missing")
        val nodeBytes = nodeId.encodeToByteArray()
        if (nodeBytes.isEmpty() || nodeBytes.size > 64) return TransportResult.Failed(type, "Invalid LoRa node ID")
        val bodySize = 1 + nodeBytes.size + payload.size
        if (bodySize > TorXRadioProtocol.MAX_RADIO_PAYLOAD) {
            return TransportResult.Failed(type, "Payload too large for LoRa; media requires a higher-bandwidth transport")
        }
        val body = ByteBuffer.allocate(bodySize).put(nodeBytes.size.toByte()).put(nodeBytes).put(payload).array()
        val frame = TorXRadioProtocol.encode(TorXRadioProtocol.Frame(
            TorXRadioProtocol.Kind.SEND_PACKET, sequence.incrementAndGet(), body
        ))
        return if (radioManager.send(frame)) TransportResult.Accepted(type)
        else TransportResult.Failed(type, "TorX Radio rejected packet")
    }

    companion object {
        const val LORA_NODE_ID_HINT = "loraNodeId"
        const val MESH_NODE_ID_HINT = "meshNodeId"

        fun decodeReceivedPacket(frameBytes: ByteArray): Pair<String, ByteArray> {
            val frame = TorXRadioProtocol.decode(frameBytes)
            require(frame.kind == TorXRadioProtocol.Kind.RECEIVED_PACKET) { "Not a received radio packet" }
            require(frame.payload.isNotEmpty()) { "Missing source node" }
            val sourceLength = frame.payload[0].toInt() and 0xff
            require(sourceLength in 1..64 && frame.payload.size > 1 + sourceLength) { "Invalid source node" }
            val source = frame.payload.copyOfRange(1, 1 + sourceLength).decodeToString()
            return source to frame.payload.copyOfRange(1 + sourceLength, frame.payload.size)
        }
    }
}
