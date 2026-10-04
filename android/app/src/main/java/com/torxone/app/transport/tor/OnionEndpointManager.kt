package com.torxone.app.transport.tor

import android.content.Context
import android.util.Log
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.connection.ConnectionManager
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class OnionEndpointManager(
    context: Context,
    private val incomingTransportHub: IncomingTransportHub,
    @Suppress("UNUSED_PARAMETER") routeManager: TorRouteManager,
    @Suppress("UNUSED_PARAMETER") connectionManager: ConnectionManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serverFactory: ((Int) -> ServerSocket)? = null
) {
    companion object {
        const val ONION_PORT = 17654
        private const val TAG = "OnionEndpointManager"
        private const val HEADER_READ_MS = 10_000L
        private const val FRAME_READ_MS = 15_000L
    }

    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var accepting = false
    @Volatile private var configuredPort = 0
    private val lifecycle = Any()
    private val admission = TorListenerAdmission()
    private val recovery = TorRecoveryBudget()

    val hiddenServiceDirectory = context.getDir("torx_onion_v3", Context.MODE_PRIVATE)
    val localPort: Int get() = configuredPort
    fun isListening(): Boolean = accepting && admission.isOpen() && acceptJob?.isActive == true &&
        serverSocket?.let { it.isBound && !it.isClosed } == true

    fun start(): Int = synchronized(lifecycle) {
        if (acceptJob?.isActive == true) return@synchronized localPort
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + dispatcher)
        // Android may select ::1 for getLoopbackAddress(), while torrc forwards to IPv4.
        val server = bindLocal(configuredPort)
        configuredPort = server.localPort
        val lifecycleTicket = recovery.start()
        var epoch = admission.start()
        serverSocket = server
        val owningScope = scope
        acceptJob = owningScope.launch {
            var activeServer = server
            while (isActive && recovery.isCurrent(lifecycleTicket)) {
                val owned = synchronized(lifecycle) {
                    (recovery.isCurrent(lifecycleTicket) && serverSocket === activeServer).also { if (it) accepting = true }
                }
                if (!owned) break
                recovery.healthy(lifecycleTicket)
                try {
                    while (isActive && !activeServer.isClosed && admission.isCurrent(epoch)) {
                        val socket = try { activeServer.accept() }
                        catch (_: Exception) {
                            if (!activeServer.isClosed) Log.w(TAG, "Tor listener accept failed")
                            break
                        }
                        if (!admission.register(epoch, socket)) { runCatching { socket.close() }; continue }
                        val admittedEpoch = epoch
                        // Client lifetimes belong to the manager, not the accept-loop coroutine.
                        owningScope.launch {
                            try { receive(socket, admittedEpoch) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Log.w(TAG, "Tor frame rejected") }
                            finally {
                                admission.release(socket)
                                runCatching { socket.close() }
                            }
                        }
                    }
                } finally {
                    recovery.healthy(lifecycleTicket)
                    val clients = synchronized(lifecycle) {
                        if (serverSocket === activeServer) {
                            accepting = false
                            serverSocket = null
                            admission.stop()
                        } else emptyList()
                    }
                    runCatching { activeServer.close() }
                    clients.forEach { runCatching { it.close() } }
                }
                recovery.unhealthy(lifecycleTicket)
                // Rebind the configured port: the existing torrc forward remains correct.
                var replacement: ServerSocket? = null
                while (isActive && recovery.isCurrent(lifecycleTicket) && replacement == null) {
                    val wait = recovery.nextDelay(lifecycleTicket) ?: break
                    delay(wait)
                    if (!recovery.isCurrent(lifecycleTicket)) break
                    val candidate = runCatching { bindLocal(configuredPort) }.getOrNull() ?: continue
                    val installed = synchronized(lifecycle) {
                        if (recovery.isCurrent(lifecycleTicket) && serverSocket == null) {
                            epoch = admission.start()
                            serverSocket = candidate
                            true
                        } else false
                    }
                    if (installed) replacement = candidate else runCatching { candidate.close() }
                }
                if (replacement == null) {
                    if (recovery.isCurrent(lifecycleTicket)) Log.w(TAG, "Tor listener recovery budget exhausted")
                    break
                }
                activeServer = replacement
            }
            synchronized(lifecycle) {
                if (recovery.isCurrent(lifecycleTicket)) accepting = false
            }
        }
        server.localPort
    }

    private fun bindLocal(port: Int): ServerSocket = serverFactory?.invoke(port) ?: ServerSocket().apply {
        try {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private suspend fun receive(socket: Socket, epoch: Long) = withContext(dispatcher) {
        socket.use {
            it.soTimeout = com.torxone.app.transport.DeliveryTimeouts.STREAM_IDLE_MS.toInt()
            val stream = java.io.PushbackInputStream(it.getInputStream(), 4)
            val input = DataInputStream(stream)
            TorIoDeadline.watch(it, HEADER_READ_MS).use { _ ->
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
                    return@withContext
                }
                stream.unread(prefix)
                // This header is framing metadata, not proof of peer identity. Only signed
                // pairing payloads may update the durable relationship endpoint.
                TorStreamFraming.readHeader(input)
            }
            val frames = TorIncomingFrameReader(it, stream, FRAME_READ_MS)
            while (currentCoroutineContext().isActive && admission.isCurrent(epoch)) {
                val payload = frames.readFrame() ?: break
                // The funnel calls this only after verifying permanent HMAC, signed pairing,
                // or media AEAD. Promotion reserves the verified relationship's quota.
                val accepted = incomingTransportHub.onRawTorFrameReceived(payload) { relationship ->
                    admission.authenticate(epoch, it, relationship)
                }
                if (!accepted) break
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

    fun onionAddress(): String? = runCatching {
        val hostname = hiddenServiceDirectory.resolve("hostname")
        if (!hostname.isFile) null
        else hostname.readText().trim().takeIf { it.matches(Regex("[a-z2-7]{56}\\.onion")) }
    }.getOrNull()

    fun closePeerConnections() { admission.snapshot().forEach { runCatching { it.close() } } }

    fun stop() {
        val stopped = synchronized(lifecycle) {
            recovery.stop()
            val clients = admission.stop()
            val server = serverSocket
            serverSocket = null
            configuredPort = 0
            acceptJob = null
            accepting = false
            scope.cancel()
            server to clients
        }
        runCatching { stopped.first?.close() }
        stopped.second.forEach { runCatching { it.close() } }
    }
}
