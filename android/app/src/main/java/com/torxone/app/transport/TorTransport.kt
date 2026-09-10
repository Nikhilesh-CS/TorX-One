package com.torxone.app.transport

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.torxone.app.network.TorManager

/**
 * Transport wrapper for Tor hidden services (.onion).
 *
 * Routes encrypted envelopes through the Tor network to reach
 * peers by their .onion address. This is the primary transport
 * for internet-connected peers who are not in mesh range.
 *
 * Delegates all Tor process management, SOCKS proxy handling,
 * and socket pooling to the existing [TorManager].
 */
class TorTransport(
    private val torManager: TorManager,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
) : Transport {

    companion object {
        private const val TAG = "TorTransport"
    }

    override val name: String = "Tor"
    override val type: TransportType = TransportType.TOR

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _statusText = MutableStateFlow("Idle")
    override val statusText: StateFlow<String> = _statusText

    private var incomingListener: TransportIncomingListener? = null

    init {
        scope.launch {
            torManager.isTorReady.collect { ready ->
                _isAvailable.value = ready
                _statusText.value = if (ready) "Ready" else torManager.torStatus.value
            }
        }
    }

    override suspend fun send(
        destination: String,
        payload: String,
        metadata: TransportMetadata?
    ): TransportResult = withContext(Dispatchers.IO) {
        if (!torManager.isTorReady.value) {
            return@withContext TransportResult.failure(TransportType.TOR, "Tor not ready")
        }

        val startMs = System.currentTimeMillis()
        Log.d(TAG, "[SEND] id=${metadata?.messageId ?: "?"} → $destination")

        return@withContext try {
            val sent = torManager.sendToOnion(destination, payload, metadata?.messageId)
            val elapsed = System.currentTimeMillis() - startMs

            if (sent) {
                Log.d(TAG, "[SEND] id=${metadata?.messageId ?: "?"} delivered (${elapsed}ms)")
                TransportResult.success(TransportType.TOR, elapsed)
            } else {
                Log.w(TAG, "[SEND] id=${metadata?.messageId ?: "?"} failed to $destination")
                TransportResult.failure(TransportType.TOR, "sendToOnion returned false")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SEND] Exception sending to $destination: ${e.message}")
            TransportResult.failure(TransportType.TOR, e.message ?: "Unknown error")
        }
    }

    override fun setIncomingListener(listener: TransportIncomingListener?) {
        incomingListener = listener
        if (listener != null) {
            torManager.onTorMessageReceived = { raw ->
                listener.onPayloadReceived(null, raw, TransportType.TOR)
            }
        } else {
            torManager.onTorMessageReceived = null
        }
    }

    override fun start() {
        torManager.start()
        _statusText.value = "Starting"
        Log.d(TAG, "[START] Tor transport starting")
        // Availability is driven by TorManager.isTorReady StateFlow
        // We sync it in updateAvailability()
    }

    override fun stop() {
        torManager.stop()
        _isAvailable.value = false
        _statusText.value = "Stopped"
        Log.d(TAG, "[STOP] Tor transport stopped")
    }

    override fun getReachablePeers(): Set<String> {
        // Tor can reach any .onion address when ready — we don't track specific peers.
        // The transport router checks isAvailable + the contact's onion address.
        return emptySet()
    }

    /** Sync availability with TorManager's ready state. */
    fun updateAvailability() {
        val ready = torManager.isTorReady.value
        _isAvailable.value = ready
        _statusText.value = torManager.torStatus.value
    }

    /** Get the local .onion address for this node. */
    fun getOnionAddress(): String = torManager.onionAddress.value
}
