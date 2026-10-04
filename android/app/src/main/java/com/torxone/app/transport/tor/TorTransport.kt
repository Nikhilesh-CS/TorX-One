package com.torxone.app.transport.tor

import android.util.Log
import com.torxone.app.protocol.ProtocolLimits
import com.torxone.app.transport.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TorTransport(
    private val controller: TorController,
    private val routeManager: TorRouteManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val peerTorEndpoints: PeerTorEndpointRepository? = null,
    private val relationshipForQueue: (String) -> String? = { null }
) : Transport, AddressableTransport, RecoverableTransport {
    override val type = TransportType.TOR
    private val peers = TorPeerConnectionManager()

    fun closePendingConnections() { peers.closeAll() }
    override fun invalidate(destination: TransportDestination) {
        peers.invalidate(destination.relationshipId ?: relationshipForQueue(destination.address) ?: "bootstrap:${destination.address}")
    }

    private fun route(destination: TransportDestination): TorRoute? {
        val relationship = destination.relationshipId ?: relationshipForQueue(destination.address)
        if (relationship != null && peerTorEndpoints != null) {
            // Established traffic has one Room-owned endpoint. Queue prefs are bootstrap-only.
            return peerTorEndpoints.resolve(relationship)
        }
        return routeManager.resolve(destination.address)
            ?: destination.hints["tor_onion"]?.let(::parseHint)
    }

    override fun availability(): Flow<TransportAvailability> = controller.state.map {
        if (it is TorConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("Tor is not ready")
    }

    override fun canRoute(destination: TransportDestination): Boolean {
        val present = route(destination) != null
        DeliveryDiagnostics.forDestination("route_lookup", destination, type, present = present)
        return present
    }

    override suspend fun prepareRoute(destination: TransportDestination): Boolean {
        val relationship = destination.relationshipId ?: relationshipForQueue(destination.address)
        // Read the authoritative row before an attempt, including deletion/supersession.
        // Cache-only canRoute remains a cheap UI read; it never authorizes a send by itself.
        if (relationship != null) peerTorEndpoints?.refresh(relationship)
        return canRoute(destination)
    }

    override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult =
        withContext(dispatcher) {
            if (payload.size !in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) {
                return@withContext TransportResult.Failed(type, "Tor frame exceeds protocol limit")
            }
            val state = controller.state.value as? TorConnectionState.Ready
                ?: return@withContext TransportResult.Failed(type, "Tor is not ready")
            val route = route(destination)
                ?: return@withContext TransportResult.Failed(type, "No Tor route for destination")
            try {
                val relationship = destination.relationshipId ?: relationshipForQueue(destination.address)
                peers.send(relationship ?: "bootstrap:${destination.address}", route,
                    state.socksPort, state.onionAddress, payload, destination)
                TransportResult.Accepted(type)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DeliveryDiagnostics.forDestination("tor_send_failed", destination, type,
                    state = if (e is java.net.SocketTimeoutException) "TIMEOUT" else "IO_FAILURE")
                TransportResult.Failed(type, if (e is java.net.SocketTimeoutException) "Tor connect or write timed out" else "Tor peer unreachable")
            }
        }

    private fun parseHint(value: String): TorRoute? = runCatching {
        val separator = value.lastIndexOf(':')
        if (separator > 0) TorRoute(value.substring(0, separator), value.substring(separator + 1).toInt())
        else TorRoute(value)
    }.getOrNull()
}
