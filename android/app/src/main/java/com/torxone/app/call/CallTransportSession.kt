package com.torxone.app.call

import android.util.Log
import com.torxone.app.network.Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated, single-writer transport session for a call's signaling lifecycle.
 * Reuses a single persistent socket for Tor signaling (eliminating per-packet circuit renegotiation)
 * or routes directly via Nearby, ensuring frames are delivered in order without corruption.
 */
class CallTransportSession(
    val callId: String,
    val peerKey: String,
    val transport: Transport,
    private val endpointId: String? = null,
    private val onionHost: String? = null,
    private val nearbySender: ((endpointId: String, frame: String) -> Boolean)? = null,
    private val torSocketFactory: ((onionHost: String, port: Int, timeoutMs: Int) -> Socket?)? = null
) {
    companion object {
        private const val TAG = "CallTransportSession"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val SO_TIMEOUT_MS = 15_000
        const val LOCAL_PORT = 8080
    }

    private val writeMutex = Mutex()
    private val isClosed = AtomicBoolean(false)

    private var torSocket: Socket? = null
    private var torOutputStream: OutputStream? = null

    val isActive: Boolean
        get() = !isClosed.get()

    /**
     * Sends a raw wire frame sequentially.
     * Guaranteed single-writer execution via mutex — concurrent calls from ICE callbacks
     * will queue cleanly and write intact newline-delimited frames.
     */
    suspend fun sendFrame(frame: String): Boolean = writeMutex.withLock {
        if (isClosed.get()) {
            Log.w(TAG, "[$callId] Cannot send frame: session is closed")
            return false
        }

        return when (transport) {
            Transport.NEARBY_DIRECT,
            Transport.NEARBY_RELAY -> sendViaNearby(frame)
            Transport.TOR -> sendViaTor(frame)
            else -> {
                Log.w(TAG, "[$callId] Unsupported transport for call session: $transport")
                false
            }
        }
    }

    private fun sendViaNearby(frame: String): Boolean {
        val targetEndpoint = endpointId
        if (targetEndpoint.isNullOrBlank()) {
            Log.w(TAG, "[$callId] Missing endpointId for Nearby send")
            return false
        }
        val sender = nearbySender
        if (sender == null) {
            Log.w(TAG, "[$callId] Missing nearbySender function")
            return false
        }
        return try {
            sender.invoke(targetEndpoint, frame)
        } catch (e: Exception) {
            Log.e(TAG, "[$callId] Failed to send Nearby frame", e)
            false
        }
    }

    private fun sendViaTor(frame: String): Boolean {
        val host = onionHost
        if (host.isNullOrBlank()) {
            Log.w(TAG, "[$callId] Missing onionHost for Tor send")
            return false
        }

        // Try writing to existing connected socket
        if (writeToTorSocket(frame)) {
            return true
        }

        // If existing socket failed or not yet open, close stale socket and reconnect
        closeTorSocket()
        if (isClosed.get()) return false

        Log.d(TAG, "[$callId] Establishing persistent Tor socket to $host:$LOCAL_PORT")
        val socket = torSocketFactory?.invoke(host, LOCAL_PORT, CONNECT_TIMEOUT_MS)
        if (socket == null) {
            Log.w(TAG, "[$callId] Failed to connect Tor socket to $host")
            return false
        }

        return try {
            socket.soTimeout = SO_TIMEOUT_MS
            torSocket = socket
            torOutputStream = socket.getOutputStream()
            writeToTorSocket(frame)
        } catch (e: Exception) {
            Log.e(TAG, "[$callId] Error setting up Tor socket streams", e)
            closeTorSocket()
            false
        }
    }

    private fun writeToTorSocket(frame: String): Boolean {
        val out = torOutputStream ?: return false
        val sock = torSocket ?: return false
        if (sock.isClosed || !sock.isConnected) return false

        return try {
            out.write(frame.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.flush()
            Log.d(TAG, "[$callId] Frame sent over persistent Tor socket (${frame.length} chars)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$callId] Failed to write frame to Tor socket: ${e.message}")
            false
        }
    }

    fun close() {
        if (isClosed.getAndSet(true)) return
        Log.d(TAG, "[$callId] Closing CallTransportSession")
        closeTorSocket()
    }

    private fun closeTorSocket() {
        try {
            torOutputStream?.flush()
        } catch (_: Exception) {}
        try {
            torOutputStream?.close()
        } catch (_: Exception) {}
        torOutputStream = null

        try {
            torSocket?.close()
        } catch (_: Exception) {}
        torSocket = null
    }
}
