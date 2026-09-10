package com.torxone.app.transport

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.torxone.app.network.NearbyConnectionManager

/**
 * Transport wrapper for Google Nearby Connections.
 *
 * Handles both direct peer connections (NEARBY_DIRECT) and
 * mesh relay forwarding (NEARBY_RELAY) through intermediate nodes.
 *
 * This wrapper delegates all low-level Nearby API calls to the
 * existing [NearbyConnectionManager], adding only the [Transport]
 * interface contract on top.
 */
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NearbyTransport(
    private val nearbyManager: NearbyConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Transport {

    companion object {
        private const val TAG = "NearbyTransport"
    }

    override val name: String = "Nearby Connections"
    override val type: TransportType = TransportType.NEARBY_DIRECT

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _statusText = MutableStateFlow("Idle")
    override val statusText: StateFlow<String> = _statusText

    private var incomingListener: TransportIncomingListener? = null

    init {
        scope.launch {
            nearbyManager.connectedEndpoints.collect { endpoints ->
                _isAvailable.value = endpoints.isNotEmpty()
                _statusText.value = if (endpoints.isNotEmpty()) "${endpoints.size} peers" else "No peers"
            }
        }
    }

    override suspend fun send(
        destination: String,
        payload: String,
        metadata: TransportMetadata?
    ): TransportResult {
        val connected = nearbyManager.connectedEndpoints.value
        if (!connected.contains(destination)) {
            return TransportResult.failure(
                TransportType.NEARBY_DIRECT,
                "Endpoint $destination not connected"
            )
        }

        val startMs = System.currentTimeMillis()
        return try {
            val ok = nearbyManager.sendRaw(destination, payload)
            val elapsed = System.currentTimeMillis() - startMs
            if (ok) {
                Log.d(TAG, "[SEND] id=${metadata?.messageId ?: "?"} → $destination (${elapsed}ms)")
                TransportResult.success(TransportType.NEARBY_DIRECT, elapsed)
            } else {
                TransportResult.failure(TransportType.NEARBY_DIRECT, "sendRaw returned false")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SEND] Failed to $destination: ${e.message}")
            TransportResult.failure(TransportType.NEARBY_DIRECT, e.message ?: "Unknown error")
        }
    }

    /**
     * Attempt to relay a payload through any connected intermediate peer.
     * Used when the target endpoint is not directly connected.
     */
    suspend fun sendViaRelay(
        relayPayload: String,
        metadata: TransportMetadata? = null
    ): TransportResult {
        val connected = nearbyManager.connectedEndpoints.value
        if (connected.isEmpty()) {
            return TransportResult.failure(TransportType.NEARBY_RELAY, "No connected peers for relay")
        }

        val startMs = System.currentTimeMillis()
        val relayed = connected.any { endpoint ->
            runCatching { nearbyManager.sendRaw(endpoint, relayPayload) }.getOrDefault(false)
        }

        val elapsed = System.currentTimeMillis() - startMs
        return if (relayed) {
            Log.d(TAG, "[RELAY] id=${metadata?.messageId ?: "?"} relayed (${elapsed}ms)")
            TransportResult.success(TransportType.NEARBY_RELAY, elapsed)
        } else {
            TransportResult.failure(TransportType.NEARBY_RELAY, "All relay attempts failed")
        }
    }

    override fun setIncomingListener(listener: TransportIncomingListener?) {
        incomingListener = listener
        // Wire up the NearbyConnectionManager callback to our transport listener
        if (listener != null) {
            nearbyManager.onMessageReceived = { endpointId, message ->
                listener.onPayloadReceived(endpointId, message, TransportType.NEARBY_DIRECT)
            }
        } else {
            nearbyManager.onMessageReceived = null
        }
    }

    override fun start() {
        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
        _isAvailable.value = true
        _statusText.value = "Active"
        Log.d(TAG, "[START] Advertising + Discovery started")
    }

    override fun stop() {
        nearbyManager.stopAll()
        _isAvailable.value = false
        _statusText.value = "Stopped"
        Log.d(TAG, "[STOP] Nearby transport stopped")
    }

    override fun getReachablePeers(): Set<String> {
        return nearbyManager.connectedEndpoints.value
    }

    /** Update availability based on connected endpoints. */
    fun updateAvailability() {
        val hasConnections = nearbyManager.connectedEndpoints.value.isNotEmpty()
        _isAvailable.value = hasConnections
        _statusText.value = if (hasConnections) {
            "${nearbyManager.connectedEndpoints.value.size} peers"
        } else {
            "No peers"
        }
    }
}
