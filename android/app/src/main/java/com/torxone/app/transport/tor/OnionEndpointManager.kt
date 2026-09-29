package com.torxone.app.transport.tor

import android.content.Context
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.protocol.ProtocolLimits
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class OnionEndpointManager(
    private val context: Context,
    private val incomingTransportHub: IncomingTransportHub,
    private val routeManager: TorRouteManager,
    private val connectionManager: ConnectionManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val ONION_PORT = 17654
    }

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: ServerSocket? = null

    val hiddenServiceDirectory = context.getDir("torx_onion_v3", Context.MODE_PRIVATE)
    val localPort: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (serverSocket != null) return localPort
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        serverSocket = server
        scope.launch {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { receive(socket) }
            }
        }
        return server.localPort
    }

    private suspend fun receive(socket: Socket) = withContext(dispatcher) {
        socket.use {
            it.soTimeout = 15_000
            val input = DataInputStream(it.getInputStream())
            val returnOnion = input.readUTF()
            require(returnOnion.matches(Regex("[a-z2-7]{56}\\.onion")))
            val length = input.readInt()
            require(length in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
            val payload = ByteArray(length)
            input.readFully(payload)
            require(input.read() == -1) { "Trailing bytes in Tor frame" }
            val accepted = incomingTransportHub.onRawFrameReceived(payload, TransportType.TOR)
            if (accepted) {
                val incomingQueue = ProtocolCodec.decodeTransportEnvelope(payload).queueAddress
                val connection = connectionManager.getConnectionByRecvQueue(incomingQueue)
                if (connection != null) routeManager.bind(connection.sendQueueId, TorRoute(returnOnion))
            }
        }
    }

    fun torrcLines(): List<String> {
        val port = start()
        return listOf(
            "HiddenServiceDir ${hiddenServiceDirectory.absolutePath}",
            "HiddenServiceVersion 3",
            "HiddenServicePort $ONION_PORT 127.0.0.1:$port"
        )
    }

    fun onionAddress(): String? {
        val hostname = hiddenServiceDirectory.resolve("hostname")
        if (!hostname.isFile) return null
        return hostname.readText().trim().takeIf { it.matches(Regex("[a-z2-7]{56}\\.onion")) }
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        scope.cancel()
    }
}
