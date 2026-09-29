package com.torxone.app.transport

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap

/** Selects a destination-aware transport and fails over without changing payload bytes. */
class TransportRouter {
    companion object { private const val TAG = "TransportRouter" }

    private val transports = ConcurrentHashMap<TransportType, Transport>()
    private val _routingEvents = MutableSharedFlow<RoutingEvent>(extraBufferCapacity = 64)
    val routingEvents: SharedFlow<RoutingEvent> = _routingEvents.asSharedFlow()

    fun registerTransport(transport: Transport) {
        transports[transport.type] = transport
        Log.i(TAG, "Registered transport: ${transport.type}")
    }

    fun unregisterTransport(type: TransportType) { transports.remove(type) }
    fun getTransport(type: TransportType): Transport? = transports[type]

    suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
        val fake = transports[TransportType.FAKE]
        val candidates = if (fake != null) {
            listOf(fake)
        } else {
            listOf(TransportType.NEARBY, TransportType.TOR, TransportType.WIFI_DIRECT, TransportType.RELAY)
                .mapNotNull(transports::get)
                .filter { (it as? AddressableTransport)?.canRoute(destination) ?: true }
        }
        if (candidates.isEmpty()) return TransportResult.Failed(TransportType.NEARBY, "No route for destination")

        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            when (val availability = candidate.availability().firstOrNull()) {
                is TransportAvailability.Unavailable -> {
                    failures += "${candidate.type}: ${availability.reason}"
                    _routingEvents.tryEmit(RoutingEvent.Skipped(destination.address, candidate.type, availability.reason))
                    continue
                }
                null -> {
                    failures += "${candidate.type}: availability unknown"
                    continue
                }
                TransportAvailability.Available -> Unit
            }

            Log.d(TAG, "[TX] Trying ${candidate.type} for ${destination.address.take(8)}")
            _routingEvents.tryEmit(RoutingEvent.Attempted(destination.address, candidate.type))
            when (val result = candidate.send(destination, payload)) {
                is TransportResult.Accepted -> {
                    _routingEvents.tryEmit(RoutingEvent.Accepted(destination.address, candidate.type))
                    return result
                }
                is TransportResult.Failed -> {
                    failures += "${candidate.type}: ${result.error}"
                    _routingEvents.tryEmit(RoutingEvent.FailedOver(destination.address, candidate.type, result.error))
                }
            }
        }

        return TransportResult.Failed(
            candidates.last().type,
            failures.joinToString("; ").ifBlank { "No transport accepted payload" }
        )
    }
}

sealed class RoutingEvent {
    abstract val destination: String
    abstract val transportType: TransportType
    data class Attempted(override val destination: String, override val transportType: TransportType) : RoutingEvent()
    data class Accepted(override val destination: String, override val transportType: TransportType) : RoutingEvent()
    data class Skipped(override val destination: String, override val transportType: TransportType, val reason: String) : RoutingEvent()
    data class FailedOver(override val destination: String, override val transportType: TransportType, val reason: String) : RoutingEvent()
}