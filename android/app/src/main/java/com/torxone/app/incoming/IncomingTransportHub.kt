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

    open suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
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
