package com.torxone.app.transport.tor

import com.torxone.app.transport.DeliveryDiagnostics
import com.torxone.app.transport.DeliveryTimeouts
import com.torxone.app.transport.TransportType
import com.torxone.app.transport.TransportDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exact relationship ownership. ACK/order/replay stay with TorXAgent, never TCP. */
class TorPeerConnectionManager(
    private val socketFactory: (Int) -> Socket = { port ->
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
    },
    private val connectSocket: ((Socket, TorRoute) -> Unit)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxPeers: Int = 32,
    private val idleMs: Long = DeliveryTimeouts.STREAM_IDLE_MS,
    private val writeTimeoutMs: Long = DeliveryTimeouts.FRAME_WRITE_MS,
    private val connectTimeoutMs: Long = DeliveryTimeouts.SOCKS_ONION_CONNECT_MS,
    private val headerTimeoutMs: Long = DeliveryTimeouts.HEADER_WRITE_MS,
    private val attemptTimeoutMs: Long = DeliveryTimeouts.TOR_ATTEMPT_MS,
    maxActive: Int = 8,
    maxCallers: Int = 128,
    maxCallersPerPeer: Int = 32
) {
    private data class Peer(val route: TorRoute, val socksPort: Int, val returnOnion: String,
                            val socket: Socket, val output: DataOutputStream, var activity: Long,
                            var idleExpiry: java.util.concurrent.ScheduledFuture<*>? = null)
    private class PeerLane {
        val mutex = Mutex()
        var users = 0 // Includes waiters: never remove a mutex somebody can still acquire.
        var generation = 0L
        var connecting: Socket? = null
        var peer: Peer? = null
    }
    private val lanes = LinkedHashMap<String, PeerLane>(16, 0.75f, true)
    private val guard = Any()
    private val admission = TorOutboundAdmission(maxActive, maxCallers, minOf(maxCallersPerPeer, maxCallers))
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Tor-stream-deadlines").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    init {
        require(maxPeers > 0 && idleMs > 0 && writeTimeoutMs > 0 && headerTimeoutMs > 0 && attemptTimeoutMs > 0)
        require(connectTimeoutMs in 1..Int.MAX_VALUE.toLong())
    }

    suspend fun send(key: String, route: TorRoute, socksPort: Int, returnOnion: String, payload: ByteArray,
                     diagnostic: TransportDestination = TransportDestination("", relationshipId = key)) {
        val reservation = admission.reserve(key)
        val retired = mutableListOf<Socket>()
        val (lane, epoch) = synchronized(guard) {
            pruneUnused(retired)
            lanes.getOrPut(key) { PeerLane() }.let { it.users++; it to it.generation }
        }
        try {
            closeSockets(retired)
            val completed = withTimeoutOrNull(attemptTimeoutMs) {
                lane.mutex.withLock {
                    // A second send for this relationship waits on its own lane, not a global permit.
                    reservation.activate()
                    synchronized(guard) {
                        if (lane.generation != epoch) throw IOException("Tor generation changed")
                    }
                    val stale = mutableListOf<Socket>()
                    val reused = synchronized(guard) {
                        lane.peer?.takeIf { it.route == route && it.socksPort == socksPort &&
                            it.returnOnion == returnOnion && !it.socket.isClosed && clock() - it.activity < idleMs }
                            ?: run { detachLane(lane, stale); null }
                    }
                    closeSockets(stale)
                    if (reused != null) DeliveryDiagnostics.forDestination("stream_reused", diagnostic, TransportType.TOR)
                    val peer = reused ?: connect(diagnostic, lane, route, socksPort, returnOnion, epoch)
                    try {
                        socketOperation(peer.socket, writeTimeoutMs) { TorStreamFraming.writeFrame(peer.output, payload) }
                        currentCoroutineContext().ensureActive()
                        synchronized(guard) {
                            if (lane.generation != epoch || peer.socket.isClosed) throw IOException("Tor generation changed")
                            peer.activity = clock()
                            peer.idleExpiry?.cancel(false)
                            peer.idleExpiry = deadlines.schedule({
                                val expired = mutableListOf<Socket>()
                                synchronized(guard) {
                                    if (lane.peer === peer && lane.users == 0 && clock() - peer.activity >= idleMs) {
                                        detachLane(lane, expired); pruneUnused(expired)
                                    }
                                }
                                closeSockets(expired)
                            }, idleMs, TimeUnit.MILLISECONDS)
                        }
                        DeliveryDiagnostics.forDestination("tor_write_success", diagnostic, TransportType.TOR)
                    } catch (error: Exception) {
                        peer.idleExpiry?.cancel(false)
                        synchronized(guard) { if (lane.peer === peer) lane.peer = null }
                        runCatching { peer.socket.close() }
                        DeliveryDiagnostics.forDestination("tor_write_failed", diagnostic, TransportType.TOR,
                            state = if (error is CancellationException) "CANCELLED" else "IO_FAILURE")
                        throw error
                    }
                }
                true
            } ?: false
            if (!completed) throw SocketTimeoutException("Tor attempt deadline")
        } finally {
            try {
                val expired = mutableListOf<Socket>()
                synchronized(guard) { lane.users--; pruneUnused(expired) }
                closeSockets(expired)
            } finally { reservation.close() }
        }
    }

    /** Cancellation closes the OWNED socket even during a blocking native connect/write. */
    private suspend fun socketOperation(socket: Socket, timeoutMs: Long, operation: () -> Unit) {
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline = deadlines.schedule({ timedOut.set(true); runCatching { socket.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val finished = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { runCatching { socket.close() } }
                        launch(Dispatchers.IO) {
                            try { operation(); continuation.resume(Unit) }
                            catch (error: Exception) { continuation.resumeWithException(error) }
                        }
                    }
                }
                true
            } ?: false
            if (!finished) throw SocketTimeoutException("Tor operation deadline")
        } catch (error: IOException) {
            if (timedOut.get()) throw SocketTimeoutException("Tor operation deadline").apply { initCause(error) }
            throw error
        } finally { deadline.cancel(false) }
    }

    private suspend fun connect(diagnostic: TransportDestination, lane: PeerLane, route: TorRoute, socksPort: Int,
                                returnOnion: String, epoch: Long): Peer {
        val socket = socketFactory(socksPort)
        val owned = synchronized(guard) {
            (lane.generation == epoch).also { if (it) lane.connecting = socket }
        }
        if (!owned) { runCatching { socket.close() }; throw IOException("Tor generation changed") }
        val started = clock()
        try {
            socketOperation(socket, connectTimeoutMs) {
                connectSocket?.invoke(socket, route) ?: socket.connect(
                    InetSocketAddress.createUnresolved(route.onionHost, route.port), connectTimeoutMs.toInt())
            }
            DeliveryDiagnostics.forDestination("tor_connect_success", diagnostic, TransportType.TOR, elapsedMs = clock() - started)
            val output = DataOutputStream(socket.getOutputStream())
            socketOperation(socket, headerTimeoutMs) { TorStreamFraming.writeHeader(output, returnOnion); output.flush() }
            val peer = Peer(route, socksPort, returnOnion, socket, output, clock())
            synchronized(guard) {
                if (lane.generation != epoch || socket.isClosed) throw IOException("Tor generation changed")
                lane.peer = peer
            }
            DeliveryDiagnostics.forDestination("stream_created", diagnostic, TransportType.TOR)
            return peer
        } catch (error: Exception) {
            runCatching { socket.close() }
            DeliveryDiagnostics.forDestination(if (error is SocketTimeoutException) "tor_connect_timeout" else "tor_connect_failed",
                diagnostic, TransportType.TOR, elapsedMs = clock() - started)
            throw error
        } finally { synchronized(guard) { if (lane.connecting === socket) lane.connecting = null } }
    }

    // Transfer ownership under the guard, then close outside it. Even socket close may block.
    private fun detachLane(lane: PeerLane, retired: MutableList<Socket>) {
        lane.connecting?.let(retired::add)
        lane.connecting = null
        lane.peer?.idleExpiry?.cancel(false)
        lane.peer?.socket?.let(retired::add)
        lane.peer = null
    }

    private fun closeSockets(sockets: List<Socket>) { sockets.forEach { runCatching { it.close() } } }

    private fun pruneUnused(retired: MutableList<Socket>) {
        val iterator = lanes.entries.iterator()
        while (iterator.hasNext()) {
            val lane = iterator.next().value
            if (lane.users == 0 && (lane.peer == null || lane.peer!!.socket.isClosed || clock() - lane.peer!!.activity >= idleMs)) {
                detachLane(lane, retired); iterator.remove()
            }
        }
        var retained = lanes.values.count { it.users == 0 && it.peer != null }
        val oldest = lanes.entries.iterator()
        while (retained > maxPeers && oldest.hasNext()) {
            val lane = oldest.next().value
            if (lane.users == 0) { detachLane(lane, retired); oldest.remove(); retained-- }
        }
    }

    fun invalidate(key: String) {
        val retired = mutableListOf<Socket>()
        synchronized(guard) {
            lanes[key]?.let { it.generation++; detachLane(it, retired) }
            pruneUnused(retired)
        }
        closeSockets(retired)
        DeliveryDiagnostics.event("stream_invalidated", key, transport = TransportType.TOR)
    }

    fun closeAll() {
        val retired = mutableListOf<Socket>()
        val keys = synchronized(guard) {
            lanes.map { (key, lane) -> lane.generation++; detachLane(lane, retired); key }.also { pruneUnused(retired) }
        }
        closeSockets(retired)
        keys.forEach { DeliveryDiagnostics.event("stream_invalidated", it, transport = TransportType.TOR, state = "GENERATION_CHANGED") }
    }

    internal fun retainedLaneCount(): Int = synchronized(guard) { lanes.size }
}
