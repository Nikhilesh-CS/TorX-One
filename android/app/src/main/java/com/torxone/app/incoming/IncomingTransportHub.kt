package com.torxone.app.incoming

import com.torxone.app.transport.TransportType

/**
 * Single incoming funnel for all transports.
 */
open class IncomingTransportHub(
    var dispatcher: IncomingDispatcher? = null
) {
    open suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
        return dispatcher?.dispatch(rawBytes, transportType) ?: false
    }
}
