package com.torxone.app.incoming

import com.torxone.app.transport.TransportType
import com.torxone.app.media.DedicatedMediaFrameCodec
import com.torxone.app.transport.mesh.MeshPacketCodec
import com.torxone.app.transport.mesh.MeshRouteAnnouncementCodec
import com.torxone.app.transport.mesh.MeshCustodyAckCodec

/**
 * Single incoming funnel for all transports.
 */
open class IncomingTransportHub(
    var dispatcher: IncomingDispatcher? = null,
    var dedicatedMediaFrameHandler: (suspend (ByteArray, TransportType) -> Boolean)? = null,
    var meshFrameHandler: (suspend (ByteArray, TransportType, String?) -> Boolean)? = null
) {
    private val mediaFragments = com.torxone.app.media.DedicatedMediaReassembler()

    var admittedMediaFrameHandler: (suspend (ByteArray, TransportType, (String) -> Boolean) -> Boolean)? = null

    /** Tor stream ownership is assigned only by authenticated payload processing. */
    open suspend fun onRawTorFrameReceived(rawBytes: ByteArray, onAuthenticatedRelationship: (String) -> Boolean): Boolean {
        com.torxone.app.transport.DeliveryDiagnostics.event("receiver_frame", transport = TransportType.TOR)
        if (rawBytes.size > com.torxone.app.protocol.ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) return false
        // Tor supports the full dedicated frame. Fragments/mesh lack this stream's
        // authenticated relationship callback; their alternate-link paths stay separate.
        if (com.torxone.app.media.DedicatedMediaFragments.isFragment(rawBytes) ||
            MeshPacketCodec.isMeshPacket(rawBytes) || MeshRouteAnnouncementCodec.isAnnouncement(rawBytes) ||
            MeshCustodyAckCodec.isAck(rawBytes)) return false
        if (DedicatedMediaFrameCodec.isDedicatedMediaFrame(rawBytes))
            return admittedMediaFrameHandler?.invoke(rawBytes, TransportType.TOR, onAuthenticatedRelationship) ?: false
        return dispatcher?.dispatch(rawBytes, TransportType.TOR, onAuthenticatedRelationship) ?: false
    }

    open suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
        com.torxone.app.transport.DeliveryDiagnostics.event("receiver_frame", transport = transportType)
        if (com.torxone.app.media.DedicatedMediaFragments.isFragment(rawBytes)) {
            return when (val result = mediaFragments.accept(rawBytes)) {
                com.torxone.app.media.DedicatedMediaReassembler.Result.Pending -> true
                com.torxone.app.media.DedicatedMediaReassembler.Result.Rejected -> false
                is com.torxone.app.media.DedicatedMediaReassembler.Result.Complete ->
                    dedicatedMediaFrameHandler?.invoke(result.bytes, transportType) ?: false
            }
        }
        if (DedicatedMediaFrameCodec.isDedicatedMediaFrame(rawBytes)) {
            return dedicatedMediaFrameHandler?.invoke(rawBytes, transportType) ?: false
        }
        if (MeshPacketCodec.isMeshPacket(rawBytes) || MeshRouteAnnouncementCodec.isAnnouncement(rawBytes) || MeshCustodyAckCodec.isAck(rawBytes)) {
            return meshFrameHandler?.invoke(rawBytes, transportType, null) ?: false
        }
        return dispatcher?.dispatch(rawBytes, transportType) ?: false
    }

    open suspend fun onRawFrameReceivedFromAuthenticatedPeer(
        rawBytes: ByteArray,
        transportType: TransportType,
        relationshipId: String
    ): Boolean {
        if (MeshPacketCodec.isMeshPacket(rawBytes) || MeshRouteAnnouncementCodec.isAnnouncement(rawBytes) || MeshCustodyAckCodec.isAck(rawBytes)) {
            return meshFrameHandler?.invoke(rawBytes, transportType, relationshipId) ?: false
        }
        return onRawFrameReceived(rawBytes, transportType)
    }
}
