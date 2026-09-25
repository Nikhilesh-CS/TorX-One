package com.torxone.app.transport

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * TransportRouter — coordinates transport selection and failover.
 * Milestone 1 prioritizes NEARBY (or FAKE during testing).
 */
class TransportRouter {
    companion object {
        private const val TAG = "TransportRouter"
    }

    private val transports = ConcurrentHashMap<TransportType, Transport>()

    fun registerTransport(transport: Transport) {
        transports[transport.type] = transport
        Log.i(TAG, "Registered transport: ${transport.type}")
    }

    fun unregisterTransport(type: TransportType) {
        transports.remove(type)
    }

    fun getTransport(type: TransportType): Transport? = transports[type]

    suspend fun send(
        destination: TransportDestination,
        payload: ByteArray
    ): TransportResult {
        // Priority order for Milestone 1: FAKE (if present, e.g. tests) -> NEARBY
        val candidate = transports[TransportType.FAKE]
            ?: transports[TransportType.NEARBY]
            ?: transports.values.firstOrNull()
            ?: return TransportResult.Failed(TransportType.NEARBY, "No transports registered")

        val availability = candidate.availability().firstOrNull()
        if (availability is TransportAvailability.Unavailable) {
            return TransportResult.Failed(candidate.type, availability.reason)
        }

        Log.d(TAG, "[TX] Routing via ${candidate.type} to ${destination.address.take(8)}")
        return candidate.send(destination, payload)
    }
}
