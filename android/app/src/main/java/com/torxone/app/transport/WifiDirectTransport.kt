package com.torxone.app.transport

import android.util.Log
import com.torxone.app.network.WifiDirectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport wrapper for Wi-Fi Direct (P2P).
 *
 * Provides peer-to-peer transport over Wi-Fi Direct when devices are
 * within range and Wi-Fi Direct is activated.
 */
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WifiDirectTransport(
    private val wifiDirectManager: WifiDirectManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Transport {

    companion object {
        private const val TAG = "WifiDirectTransport"
    }

    override val name: String = "Wi-Fi Direct"
    override val type: TransportType = TransportType.WIFI_DIRECT

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _statusText = MutableStateFlow("Idle")
    override val statusText: StateFlow<String> = _statusText

    private var incomingListener: TransportIncomingListener? = null

    init {
        scope.launch {
            wifiDirectManager.isWifiDirectEnabled.collect { enabled ->
                _isAvailable.value = enabled
                _statusText.value = if (enabled) "Enabled" else "Disabled"
            }
        }
    }

    override suspend fun send(
        destination: String,
        payload: String,
        metadata: TransportMetadata?
    ): TransportResult {
        if (!wifiDirectManager.isWifiDirectEnabled.value) {
            return TransportResult.failure(TransportType.WIFI_DIRECT, "Wi-Fi Direct is not enabled")
        }

        val startMs = System.currentTimeMillis()
        Log.d(TAG, "[SEND] id=${metadata?.messageId ?: "?"} to $destination")
        return TransportResult.failure(TransportType.WIFI_DIRECT, "Wi-Fi Direct data plane pending socket channel")
    }

    override fun setIncomingListener(listener: TransportIncomingListener?) {
        incomingListener = listener
    }

    override fun start() {
        _statusText.value = "Active"
        _isAvailable.value = wifiDirectManager.isWifiDirectEnabled.value
        Log.d(TAG, "[START] WifiDirect transport initialized")
    }

    override fun stop() {
        _isAvailable.value = false
        _statusText.value = "Stopped"
        Log.d(TAG, "[STOP] WifiDirect transport stopped")
    }

    override fun getReachablePeers(): Set<String> {
        return wifiDirectManager.peers.value.map { it.deviceAddress }.toSet()
    }
}
