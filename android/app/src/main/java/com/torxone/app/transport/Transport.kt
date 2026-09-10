package com.torxone.app.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * TorX One 2.0 — Transport Abstraction Layer
 *
 * Each transport implementation wraps a specific delivery mechanism
 * (Nearby Connections, Tor, Wi-Fi Direct, Bluetooth, LAN).
 *
 * Transports know NOTHING about message content. They deliver opaque
 * encrypted envelopes. This is the core SimpleX-inspired decoupling:
 *
 *   Message → Connection → Delivery Queue → Encrypted Envelope → Transport
 *
 * If one transport fails, the delivery queue tries the next one.
 * The envelope is never re-encrypted or re-created.
 */
interface Transport {

    /** Human-readable name for logging and UI status. */
    val name: String

    /** Unique identifier for this transport type. */
    val type: TransportType

    /** Observable availability state. */
    val isAvailable: StateFlow<Boolean>

    /** Observable connection status text for diagnostics. */
    val statusText: StateFlow<String>

    /**
     * Send an opaque payload to a remote peer.
     *
     * @param destination Peer address in transport-specific format:
     *   - NearbyTransport: endpointId
     *   - TorTransport: .onion address
     *   - WifiDirectTransport: device address
     * @param payload Already-encrypted envelope bytes as String (JSON wire format).
     * @param metadata Optional transport-level metadata (e.g., message ID for logging).
     * @return [TransportResult] indicating success/failure and diagnostics.
     */
    suspend fun send(
        destination: String,
        payload: String,
        metadata: TransportMetadata? = null
    ): TransportResult

    /**
     * Register a listener for incoming payloads from this transport.
     * The dispatcher will call this to wire up incoming message handling.
     *
     * @param listener Callback receiving (sourceAddress, rawPayload).
     */
    fun setIncomingListener(listener: TransportIncomingListener?)

    /**
     * Start this transport (begin advertising/listening/connecting).
     */
    fun start()

    /**
     * Stop this transport gracefully.
     */
    fun stop()

    /**
     * Get the set of currently reachable peers via this transport.
     * Returns transport-specific addresses.
     */
    fun getReachablePeers(): Set<String>
}

/** Transport type enumeration for priority ordering and identification. */
enum class TransportType(val priority: Int) {
    /** Direct Nearby Connections (lowest latency, highest priority). */
    NEARBY_DIRECT(priority = 1),

    /** Wi-Fi Direct peer-to-peer. */
    WIFI_DIRECT(priority = 2),

    /** Tor hidden service (.onion). */
    TOR(priority = 3),

    /** Offline relay (store-and-forward, opaque queue-addressed). */
    OFFLINE_RELAY(priority = 4),

    /** Legacy mesh relay through intermediate Nearby peers. */
    @Deprecated("Legacy mesh relay replaced by offline relay protocol")
    NEARBY_RELAY(priority = 5),

    /** Local area network. */
    @Deprecated("Unused LAN transport")
    LAN(priority = 6)
}

/** Metadata passed alongside transport send operations. */
data class TransportMetadata(
    val messageId: String? = null,
    val envelopeId: String? = null,
    val isRetry: Boolean = false,
    val attemptNumber: Int = 1
)

/** Result of a transport send operation. */
data class TransportResult(
    val success: Boolean,
    val transportType: TransportType,
    val error: String? = null,
    val latencyMs: Long? = null
) {
    companion object {
        fun success(type: TransportType, latencyMs: Long? = null) =
            TransportResult(success = true, transportType = type, latencyMs = latencyMs)

        fun failure(type: TransportType, error: String) =
            TransportResult(success = false, transportType = type, error = error)
    }
}

/** Callback interface for incoming transport payloads. */
fun interface TransportIncomingListener {
    /**
     * Called when a payload arrives from a remote peer.
     *
     * @param sourceAddress Transport-specific source identifier.
     * @param payload Raw received payload (JSON wire format).
     * @param transportType Which transport received this payload.
     */
    fun onPayloadReceived(sourceAddress: String?, payload: String, transportType: TransportType)
}
