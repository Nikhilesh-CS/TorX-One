package com.torxone.app.transport.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded persistent SOCKS streams. Protocol ACKs, ordering and retries stay with TorXAgent. */
class TorPeerConnectionManager(
    private val socketFactory: (Int) -> Socket = { port ->
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
    },
    private val connectSocket: (Socket, TorRoute) -> Unit = { socket, route ->
        socket.connect(InetSocketAddress.createUnresolved(route.onionHost, route.port), 120_000)
    },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxPeers: Int = 32,
    private val idleMs: Long = 90_000,
    private val writeTimeoutMs: Long = 15_000
) {
    private data class Peer(val route: TorRoute, val socksPort: Int, val returnOnion: String,
                            val socket: Socket, val output: DataOutputStream, var activity: Long)
    private val peers = LinkedHashMap<String, Peer>(16, 0.75f, true)
    private val pending = ConcurrentHashMap.newKeySet<Socket>()
    private val locks = Array(64) { Mutex() }
    private val guard = Any()
    private var generation = 0L
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Tor-stream-deadlines").apply { isDaemon = true }
    }

    init { require(maxPeers > 0 && idleMs > 0 && writeTimeoutMs > 0) }

    suspend fun send(key: String, route: TorRoute, socksPort: Int, returnOnion: String, payload: ByteArray) {
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val epoch = synchronized(guard) { generation }
                val peer = synchronized(guard) {
                    peers[key]?.takeIf { it.route == route && it.socksPort == socksPort &&
                        it.returnOnion == returnOnion && !it.socket.isClosed && clock() - it.activity < idleMs }
                        ?: run { peers.remove(key)?.socket?.close(); null }
                } ?: connect(key, route, socksPort, returnOnion, epoch)
                val timeout = deadlines.schedule({ runCatching { peer.socket.close() } }, writeTimeoutMs, TimeUnit.MILLISECONDS)
                try {
                    TorStreamFraming.writeFrame(peer.output, payload)
                    currentCoroutineContext().ensureActive()
                    synchronized(guard) { peer.activity = clock() }
                } catch (error: Exception) {
                    synchronized(guard) { if (peers[key] === peer) peers.remove(key) }
                    runCatching { peer.socket.close() }
                    // Do not replay here: the peer may have received this frame. The durable retry
                    // sends the same delivery ID and the authenticated receiver deduplicates it.
                    throw error
                } finally { timeout.cancel(false) }
            }
        }
    }

    private fun connect(key: String, route: TorRoute, socksPort: Int, returnOnion: String, epoch: Long): Peer {
        val socket = socketFactory(socksPort)
        synchronized(guard) {
            if (epoch != generation) { socket.close(); throw IOException("Tor network changed") }
            pending.add(socket)
        }
        try {
            connectSocket(socket, route)
            val output = DataOutputStream(socket.getOutputStream())
            val timeout = deadlines.schedule({ runCatching { socket.close() } }, writeTimeoutMs, TimeUnit.MILLISECONDS)
            try { TorStreamFraming.writeHeader(output, returnOnion); output.flush() }
            finally { timeout.cancel(false) }
            val peer = Peer(route, socksPort, returnOnion, socket, output, clock())
            synchronized(guard) {
                if (epoch != generation) throw IOException("Tor network changed")
                while (peers.size >= maxPeers) {
                    val oldest = peers.entries.first()
                    peers.remove(oldest.key)
                    runCatching { oldest.value.socket.close() }
                }
                peers[key] = peer
            }
            return peer
        } catch (error: Exception) { runCatching { socket.close() }; throw error }
        finally { pending.remove(socket) }
    }

    fun closeAll() = synchronized(guard) {
        generation++
        pending.forEach { runCatching { it.close() } }
        peers.values.forEach { runCatching { it.socket.close() } }
        peers.clear()
    }
}
