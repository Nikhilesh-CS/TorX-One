package com.torxone.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Tor first; bounded candidates; unchanged opaque payload on authenticated fallback. */
class TransportRouter(
    private val torAttemptMs: Long = DeliveryTimeouts.TOR_ATTEMPT_MS,
    private val fallbackAttemptMs: Long = DeliveryTimeouts.FALLBACK_ATTEMPT_MS,
    private val availabilityMs: Long = DeliveryTimeouts.AVAILABILITY_MS,
    private val preparationMs: Long = DeliveryTimeouts.ROUTE_PREPARATION_MS,
    private val attemptMs: Long = DeliveryTimeouts.ROUTER_ATTEMPT_MS,
    private val breaker: TorCircuitBreaker = TorCircuitBreaker()
) {
    private val transports = ConcurrentHashMap<TransportType, Transport>()
    private val _routingEvents = MutableSharedFlow<RoutingEvent>(extraBufferCapacity = 64)
    val routingEvents: SharedFlow<RoutingEvent> = _routingEvents.asSharedFlow()

    init { require(listOf(torAttemptMs, fallbackAttemptMs, availabilityMs, preparationMs, attemptMs).all { it > 0 }) }
    fun registerTransport(transport: Transport) { transports[transport.type] = transport }
    fun unregisterTransport(type: TransportType) { transports.remove(type) }
    fun getTransport(type: TransportType): Transport? = transports[type]
    private fun key(destination: TransportDestination) = destination.relationshipId ?: "bootstrap:${destination.address}"

    fun resetRelationship(relationshipId: String) {
        breaker.reset(relationshipId)
        (transports[TransportType.TOR] as? RecoverableTransport)?.invalidate(TransportDestination("", relationshipId = relationshipId))
    }
    fun resetTorHealth() { breaker.resetAll() }
    fun invalidateRelationship(destination: TransportDestination) {
        (transports[TransportType.TOR] as? RecoverableTransport)?.invalidate(destination)
    }
    fun onAckTimeout(destination: TransportDestination, acceptedTransport: TransportType? = null) {
        invalidateRelationship(destination)
        // A fallback ACK timeout does not prove Tor failed. Restarted deliveries
        // have no process-local attribution, so only invalidate their stale stream.
        if (acceptedTransport == TransportType.TOR) breaker.missingAck(key(destination))
    }
    fun onAuthenticatedAck(destination: TransportDestination) { breaker.reset(key(destination)) }

    suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult =
        withTimeoutOrNull(attemptMs) { route(destination, payload) }
            ?: TransportResult.Failed(TransportType.TOR, "Delivery attempt deadline")

    private suspend fun route(destination: TransportDestination, payload: ByteArray): TransportResult {
        val fake = transports[TransportType.FAKE]
        val candidates = if (fake != null) listOf(fake) else listOf(
            TransportType.TOR, TransportType.NEARBY, TransportType.WIFI_DIRECT, TransportType.WIFI_HALOW,
            TransportType.GATEWAY, TransportType.LORA, TransportType.RELAY).mapNotNull(transports::get)
        var lastFailure = "No route for destination"
        var lastType = TransportType.TOR
        for (candidate in candidates) {
            lastType = candidate.type
            val permit = if (candidate.type == TransportType.TOR) breaker.acquire(key(destination)) else null
            if (candidate.type == TransportType.TOR && permit == null) {
                lastFailure = "Peer Tor circuit cooling down"
                _routingEvents.tryEmit(RoutingEvent.Skipped(destination.address, candidate.type, lastFailure))
                continue
            }
            var resolvedPermit = false
            try {
                val hasRoute = withTimeoutOrNull(preparationMs) {
                    (candidate as? AddressableTransport)?.prepareRoute(destination) ?: true
                } ?: false
                if (!hasRoute) { lastFailure = "No route for destination"; continue }
                val availability = withTimeoutOrNull(availabilityMs) { candidate.availability().firstOrNull() }
                if (availability !is TransportAvailability.Available) {
                    lastFailure = if (candidate.type == TransportType.TOR) "Tor unavailable" else "Transport unavailable"
                    _routingEvents.tryEmit(RoutingEvent.Skipped(destination.address, candidate.type, lastFailure))
                    continue
                }
                val started = System.nanoTime()
                DeliveryDiagnostics.forDestination(if (candidate.type == TransportType.TOR) "tor_attempt_start" else "transport_fallback",
                    destination, candidate.type)
                _routingEvents.tryEmit(RoutingEvent.Attempted(destination.address, candidate.type))
                val result = withTimeoutOrNull(if (candidate.type == TransportType.TOR) torAttemptMs else fallbackAttemptMs) {
                    candidate.send(destination, payload)
                } ?: run {
                    (candidate as? RecoverableTransport)?.invalidate(destination)
                    TransportResult.Failed(candidate.type, "Transport attempt deadline")
                }
                when (result) {
                    is TransportResult.Accepted -> {
                        permit?.let { breaker.accepted(it); resolvedPermit = true }
                        DeliveryDiagnostics.forDestination("transport_accepted", destination, candidate.type,
                            elapsedMs = (System.nanoTime() - started) / 1_000_000)
                        _routingEvents.tryEmit(RoutingEvent.Accepted(destination.address, candidate.type))
                        return result
                    }
                    is TransportResult.Failed -> {
                        permit?.let { breaker.failed(it); resolvedPermit = true }
                        lastFailure = result.error
                        _routingEvents.tryEmit(RoutingEvent.FailedOver(destination.address, candidate.type, result.error))
                    }
                }
            } catch (cancelled: CancellationException) {
                (candidate as? RecoverableTransport)?.invalidate(destination)
                throw cancelled
            } catch (_: Exception) {
                (candidate as? RecoverableTransport)?.invalidate(destination)
                permit?.let { breaker.failed(it); resolvedPermit = true }
                lastFailure = "Transport failure"
                _routingEvents.tryEmit(RoutingEvent.FailedOver(destination.address, candidate.type, lastFailure))
            } finally { if (!resolvedPermit) permit?.let(breaker::abandon) }
        }
        return TransportResult.Failed(lastType, lastFailure)
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
