package com.torxone.app.transport.tor

import android.util.Log
import com.torxone.app.protocol.ProtocolLimits
import com.torxone.app.transport.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class TorTransport(
    private val controller: TorController,
    private val routeManager: TorRouteManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Transport, AddressableTransport {
    override val type = TransportType.TOR
    private val pendingSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    fun closePendingConnections() {
        pendingSockets.forEach { socket -> runCatching { socket.close() } }
    }

    override fun availability(): Flow<TransportAvailability> = controller.state.map {
        if (it is TorConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("Tor is not ready")
    }

    override fun canRoute(destination: TransportDestination): Boolean {
        val present = routeManager.resolve(destination.address) != null ||
            destination.hints["tor_onion"]?.let(::parseHint) != null
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
            val route = routeManager.resolve(destination.address)
                ?: destination.hints["tor_onion"]?.let { parseHint(it) }
                ?: return@withContext TransportResult.Failed(type, "No Tor route for destination")
            try {
                Log.i("TORX_DIAG", "tor_outbound queue=${destination.address.take(8)} routePresent=true")
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", state.socksPort))
                Socket(proxy).use { socket ->
                    pendingSockets.add(socket)
                    try {
                    // Onion rendezvous can exceed 30 seconds on mobile networks (observed 89s).
                    socket.connect(InetSocketAddress.createUnresolved(route.onionHost, route.port), 120_000)
                    socket.soTimeout = 15_000
                    DataOutputStream(socket.getOutputStream()).use { output ->
                        val returnOnion = state.onionAddress
                        output.writeUTF(returnOnion)
                        output.writeInt(payload.size)
                        output.write(payload)
                        output.flush()
                    }
                    } finally {
                        pendingSockets.remove(socket)
                    }
                }
                TransportResult.Accepted(type)
            } catch (e: Exception) {
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
