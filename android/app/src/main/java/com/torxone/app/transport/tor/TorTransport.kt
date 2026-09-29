package com.torxone.app.transport.tor

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

    override fun availability(): Flow<TransportAvailability> = controller.state.map {
        if (it is TorConnectionState.Ready) TransportAvailability.Available
        else TransportAvailability.Unavailable("Tor is not ready")
    }

    override fun canRoute(destination: TransportDestination): Boolean =
        routeManager.resolve(destination.address) != null ||
            destination.hints["tor_onion"]?.let(::parseHint) != null

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
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", state.socksPort))
                Socket(proxy).use { socket ->
                    socket.connect(InetSocketAddress.createUnresolved(route.onionHost, route.port), 30_000)
                    socket.soTimeout = 15_000
                    DataOutputStream(socket.getOutputStream()).use { output ->
                        val returnOnion = state.onionAddress ?: controller.onionAddress()
                            ?: return@withContext TransportResult.Failed(type, "Local onion service is not published")
                        output.writeUTF(returnOnion)
                        output.writeInt(payload.size)
                        output.write(payload)
                        output.flush()
                    }
                }
                TransportResult.Accepted(type)
            } catch (e: Exception) {
                TransportResult.Failed(type, e.message ?: e.javaClass.simpleName)
            }
        }

    private fun parseHint(value: String): TorRoute? = runCatching {
        val separator = value.lastIndexOf(':')
        if (separator > 0) TorRoute(value.substring(0, separator), value.substring(separator + 1).toInt())
        else TorRoute(value)
    }.getOrNull()
}
