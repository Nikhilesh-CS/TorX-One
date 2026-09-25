package com.torxone.app.incoming

import com.torxone.app.transport.TransportType

/**
 * Single incoming funnel for all transports.
 */
class IncomingTransportHub(
    var dispatcher: IncomingDispatcher
) {
    suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
        return dispatcher.dispatch(rawBytes, transportType)
    }
}
