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
) : Transport, AddressableTransport {
    override val type = TransportType.TOR
    private val peers = TorPeerConnectionManager()

    fun closePendingConnections() { peers.closeAll() }

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
        Log.i("TORX_DIAG", "route_lookup queue=${destination.address.take(8)} present=$present")
        return present
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
                Log.i("TORX_DIAG", "tor_outbound queue=${destination.address.take(8)} routePresent=true")
                val relationship = destination.relationshipId ?: relationshipForQueue(destination.address)
                peers.send(relationship ?: "bootstrap:${destination.address}", route,
                    state.socksPort, state.onionAddress, payload)
                TransportResult.Accepted(type)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("TORX_DIAG", "tor_send_failed queue=${destination.address.take(8)} type=${e.javaClass.simpleName}")
                TransportResult.Failed(type, e.message ?: e.javaClass.simpleName)
            }
        }

    private fun parseHint(value: String): TorRoute? = runCatching {
        val separator = value.lastIndexOf(':')
        if (separator > 0) TorRoute(value.substring(0, separator), value.substring(separator + 1).toInt())
        else TorRoute(value)
    }.getOrNull()
}
