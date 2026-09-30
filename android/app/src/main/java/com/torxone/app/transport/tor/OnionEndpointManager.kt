package com.torxone.app.transport.tor

import android.content.Context
import android.util.Log
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
        private const val TAG = "OnionEndpointManager"
    }

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: ServerSocket? = null

    val hiddenServiceDirectory = context.getDir("torx_onion_v3", Context.MODE_PRIVATE)
    val localPort: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        if (serverSocket != null) return localPort
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + dispatcher)
        // Android may select ::1 for getLoopbackAddress(), while torrc forwards to IPv4.
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        Log.i("TORX_DIAG", "listener_bound localPort=${server.localPort} onionPort=$ONION_PORT")
        scope.launch {
            while (isActive && !server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    if (!server.isClosed) Log.e(TAG, "Tor listener accept failed", error)
                    break
                }
                launch {
                    try {
                        receive(socket)
                    } catch (cancelled: CancellationException) {
                        socket.close()
                        throw cancelled
                    } catch (error: Exception) {
                        // A disconnected/malformed client must not cancel the accepting loop.
                        socket.close()
                        Log.w(TAG, "Tor frame rejected: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
        return server.localPort
    }

    private suspend fun receive(socket: Socket) = withContext(dispatcher) {
        socket.use {
            it.soTimeout = 15_000
            val stream = java.io.PushbackInputStream(it.getInputStream(), 4)
            val input = DataInputStream(stream)
            val prefix = ByteArray(4)
            input.readFully(prefix)
            // Debug-only transport proof; bypass contacts, crypto, Room and dispatcher.
            if (com.torxone.app.BuildConfig.DEBUG && java.nio.ByteBuffer.wrap(prefix).int == 0x54585031) {
                val nonce = ByteArray(16)
                input.readFully(nonce)
                java.io.DataOutputStream(it.getOutputStream()).apply {
                    writeInt(0x54585032)
                    write(nonce)
                    flush()
                }
                Log.i("TORX_DIAG", "PING received; PONG sent")
                return@withContext
            }
            stream.unread(prefix)
            val returnOnion = input.readUTF()
            require(returnOnion.matches(Regex("[a-z2-7]{56}\\.onion")))
            val length = input.readInt()
            require(length in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
            val payload = ByteArray(length)
            input.readFully(payload)
            require(input.read() == -1) { "Trailing bytes in Tor frame" }
            val accepted = incomingTransportHub.onRawFrameReceived(payload, TransportType.TOR)
            Log.i("TORX_DIAG", "tor_rx queue=${ProtocolCodec.decodeTransportEnvelope(payload).queueAddress.take(8)} accepted=$accepted bytes=$length")
            if (accepted) {
                val incomingQueue = ProtocolCodec.decodeTransportEnvelope(payload).queueAddress
                val connection = connectionManager.getConnectionByRecvQueue(incomingQueue)
                if (connection != null) routeManager.bind(connection.sendQueueId, TorRoute(returnOnion))
            }
        }
    }

    fun torrcLines(): List<String> {
        // Context.getDir creates 0771 directories. Tor onion key directories require 0700.
        android.system.Os.chmod(hiddenServiceDirectory.absolutePath, 448)
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
