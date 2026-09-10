package com.torxone.app.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * TorX One 2.0 — Transport Router
 *
 * Central coordinator that selects the best available transport for
 * delivering an encrypted envelope to a specific peer. Implements
 * the priority-based failover chain:
 *
 *   Nearby Direct → Wi-Fi Direct → LAN → Nearby Relay → Tor → Offline Relay
 *
 * **Key principle**: The router receives already-encrypted envelopes.
 * It never sees plaintext. If one transport fails, the same envelope
 * is tried on the next transport without re-encryption.
 *
 * This is the core SimpleX-inspired decoupling:
 * transport does NOT know about messages.
 */
class TransportRouter(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "TransportRouter"
    }

    private val transports = mutableListOf<Transport>()
    private var nearbyTransport: NearbyTransport? = null
    private var torTransport: TorTransport? = null
    private var relayTransport: RelayTransport? = null

    private val _activeTransports = MutableStateFlow<List<TransportStatus>>(emptyList())
    val activeTransports: StateFlow<List<TransportStatus>> = _activeTransports

    /** Register a transport implementation. Order matters for priority. */
    fun registerTransport(transport: Transport) {
        transports.add(transport)
        when (transport) {
            is NearbyTransport -> nearbyTransport = transport
            is TorTransport -> torTransport = transport
            is RelayTransport -> relayTransport = transport
        }
        // Sort by priority (lower = higher priority)
        transports.sortBy { it.type.priority }
        updateStatus()
        Log.d(TAG, "[REGISTER] ${transport.name} (priority=${transport.type.priority})")
    }

    /**
     * Set the incoming listener for ALL registered transports.
     * The IncomingDispatcher calls this once during initialization.
     */
    fun setGlobalIncomingListener(listener: TransportIncomingListener) {
        transports.forEach { it.setIncomingListener(listener) }
        Log.d(TAG, "[LISTENER] Global incoming listener set for ${transports.size} transports")
    }

    /**
     * Deliver an encrypted envelope to a peer using the best available transport.
     *
     * Tries transports in priority order. The [peerAddresses] map provides
     * transport-specific addresses for the target peer.
     *
     * @param peerAddresses Map of TransportType → address for the target peer.
     *   e.g., { NEARBY_DIRECT: "ep123", TOR: "abc...xyz.onion" }
     * @param payload Already-encrypted envelope as JSON string.
     * @param metadata Optional metadata for logging/tracking.
     * @return [TransportResult] from the first successful transport, or the last failure.
     */
    suspend fun deliver(
        peerAddresses: Map<TransportType, String>,
        payload: String,
        metadata: TransportMetadata? = null
    ): TransportResult {
        var lastResult: TransportResult? = null

        for (transport in transports) {
            // Skip transports that aren't available
            if (!transport.isAvailable.value) {
                Log.d(TAG, "[DELIVER] Skipping ${transport.name} — not available")
                continue
            }

            // Skip transports where we don't have an address for this peer
            val address = peerAddresses[transport.type]
            if (address.isNullOrBlank()) {
                Log.d(TAG, "[DELIVER] Skipping ${transport.name} — no address for peer")
                continue
            }

            // Attempt delivery
            val result = transport.send(address, payload, metadata)
            if (result.success) {
                Log.i(TAG, "[DELIVER] id=${metadata?.messageId ?: "?"} via ${transport.name} (${result.latencyMs}ms)")
                return result
            }

            lastResult = result
            Log.w(TAG, "[DELIVER] ${transport.name} failed: ${result.error}, trying next...")
        }

        // Try relay as last resort if we have a NearbyTransport
        if (nearbyTransport != null && peerAddresses.containsKey(TransportType.NEARBY_RELAY)) {
            val relayPayload = peerAddresses[TransportType.NEARBY_RELAY]
            if (!relayPayload.isNullOrBlank()) {
                val relayResult = nearbyTransport!!.sendViaRelay(relayPayload, metadata)
                if (relayResult.success) {
                    Log.i(TAG, "[DELIVER] id=${metadata?.messageId ?: "?"} via Nearby Relay")
                    return relayResult
                }
                lastResult = relayResult
            }
        }

        val finalResult = lastResult ?: TransportResult.failure(
            TransportType.NEARBY_DIRECT,
            "No transports available for delivery"
        )
        Log.w(TAG, "[DELIVER] id=${metadata?.messageId ?: "?"} ALL transports failed: ${finalResult.error}")
        return finalResult
    }

    /**
     * Simplified delivery for when we know the exact transport to use.
     * Used for relay forwarding and ACK responses where we want to
     * reply on the same transport that brought the original message.
     */
    suspend fun sendDirect(
        transportType: TransportType,
        destination: String,
        payload: String,
        metadata: TransportMetadata? = null
    ): TransportResult {
        val transport = transports.find { it.type == transportType }
            ?: return TransportResult.failure(transportType, "Transport $transportType not registered")

        return transport.send(destination, payload, metadata)
    }

    /** Start all registered transports. */
    fun startAll() {
        transports.forEach {
            try {
                it.start()
                Log.d(TAG, "[START] ${it.name}")
            } catch (e: Exception) {
                Log.e(TAG, "[START] Failed to start ${it.name}: ${e.message}")
            }
        }
        updateStatus()
    }

    /** Stop all registered transports. */
    fun stopAll() {
        transports.forEach {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "[STOP] Error stopping ${it.name}: ${e.message}")
            }
        }
        updateStatus()
    }

    /** Get the local Tor .onion address, if available. */
    fun getOnionAddress(): String = torTransport?.getOnionAddress() ?: ""

    /** Check if Tor is ready. */
    fun isTorReady(): Boolean = torTransport?.isAvailable?.value ?: false

    /** Check if offline relay is connected and available. */
    fun isRelayAvailable(): Boolean = relayTransport?.isAvailable?.value ?: false

    /** Get the registered RelayTransport instance. */
    fun getRelayTransport(): RelayTransport? = relayTransport

    /** Get currently connected Nearby endpoints. */
    fun getConnectedEndpoints(): Set<String> = nearbyTransport?.getReachablePeers() ?: emptySet()

    /** Refresh availability status from all transports. */
    fun refreshAvailability() {
        nearbyTransport?.updateAvailability()
        torTransport?.updateAvailability()
        updateStatus()
    }

    private fun updateStatus() {
        _activeTransports.value = transports.map { transport ->
            TransportStatus(
                type = transport.type,
                name = transport.name,
                isAvailable = transport.isAvailable.value,
                statusText = transport.statusText.value
            )
        }
    }
}

/** Observable status for a single transport. */
data class TransportStatus(
    val type: TransportType,
    val name: String,
    val isAvailable: Boolean,
    val statusText: String
)
