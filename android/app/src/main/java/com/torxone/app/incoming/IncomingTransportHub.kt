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
    open suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
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
