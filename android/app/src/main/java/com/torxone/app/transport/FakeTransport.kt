package com.torxone.app.transport

import com.torxone.app.incoming.IncomingTransportHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * FakeTransport for testing and simulation.
 * Capable of injecting delays, packet loss, duplicate deliveries, and disconnection.
 */
class FakeTransport(
    var peerHub: IncomingTransportHub? = null
) : Transport {

    override val type: TransportType = TransportType.FAKE

    private val _availability = MutableStateFlow<TransportAvailability>(TransportAvailability.Available)
    override fun availability(): Flow<TransportAvailability> = _availability.asStateFlow()

    private val random = SecureRandom()

    var isConnected: Boolean = true
        set(value) {
            field = value
            _availability.value = if (value) {
                TransportAvailability.Available
            } else {
                TransportAvailability.Unavailable("Fake transport disconnected")
            }
        }

    var packetLossRate: Double = 0.0
    var duplicateDelivery: Boolean = false
    var artificialDelayMs: Long = 0L

    override suspend fun send(
        destination: TransportDestination,
        payload: ByteArray
    ): TransportResult {
        if (!isConnected) {
            return TransportResult.Failed(type, "Fake transport is offline")
        }

        if (artificialDelayMs > 0) {
            delay(artificialDelayMs)
        }

        // Simulate packet loss on the wire
        if (packetLossRate > 0.0 && random.nextDouble() < packetLossRate) {
            // Emulates physical packet dropped in transit after radio accepted it
            return TransportResult.Accepted(type)
        }

        peerHub?.onRawFrameReceived(payload.copyOf(), type)

        // Simulate duplicate delivery
        if (duplicateDelivery) {
            peerHub?.onRawFrameReceived(payload.copyOf(), type)
        }

        return TransportResult.Accepted(type)
    }
}
