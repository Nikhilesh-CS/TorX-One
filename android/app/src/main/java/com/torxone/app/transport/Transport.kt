package com.torxone.app.transport

import com.torxone.app.protocol.OpaqueTransportEnvelope

/**
 * Transport abstraction — Nearby, Tor, Relay, Wi-Fi Direct
 * are all just implementations of this interface.
 *
 * A Transport is a pipe. It doesn't know about messages,
 * contacts, groups, or crypto. It moves opaque bytes.
 */
interface Transport {
    /** Human-readable name for diagnostics */
    val name: String

    /** Is this transport currently available? */
    suspend fun isAvailable(): Boolean

    /**
     * Send an opaque envelope via this transport.
     * Returns success/failure — never throws for delivery failures.
     */
    suspend fun send(envelope: OpaqueTransportEnvelope, connectionId: String): TransportResult

    /**
     * Send a delivery acknowledgment.
     */
    suspend fun sendAck(queueAddress: String, envelopeId: String): TransportResult

    /**
     * Set callback for incoming data.
     */
    fun setIncomingHandler(handler: (OpaqueTransportEnvelope) -> Unit)

    /** Start this transport */
    suspend fun start()

    /** Stop this transport */
    suspend fun stop()
}

/**
 * Result of a transport operation.
 */
sealed class TransportResult {
    data class Success(val transportName: String) : TransportResult()
    data class Failure(val transportName: String, val reason: String) : TransportResult()
}

/**
 * Transport selection policy.
 *
 * This replaces random `if` statements with testable policy:
 *   same-device vicinity  → Nearby
 *   internet direct        → Tor
 *   peer unavailable       → Relay
 *   large local media      → Wi-Fi Direct
 */
data class TransportPolicy(
    val messageClass: MessageClass = MessageClass.NORMAL,
    val privacyMode: PrivacyMode = PrivacyMode.STANDARD,
    val peerCapabilities: Set<TransportCapability> = emptySet(),
    val availableTransports: Set<String> = emptySet()
) {
    /**
     * Select transports to try, in priority order.
     */
    fun prioritizedTransports(): List<String> {
        val result = mutableListOf<String>()

        when {
            messageClass == MessageClass.LARGE_MEDIA -> {
                // Large media prefers local direct connections
                if ("WIFI_DIRECT" in availableTransports) result.add("WIFI_DIRECT")
                if ("NEARBY" in availableTransports) result.add("NEARBY")
                if ("TOR" in availableTransports) result.add("TOR")
                if ("RELAY" in availableTransports) result.add("RELAY")
            }

            privacyMode == PrivacyMode.MAXIMUM -> {
                // Maximum privacy prefers Tor
                if ("TOR" in availableTransports) result.add("TOR")
                if ("RELAY" in availableTransports) result.add("RELAY")
            }

            else -> {
                // Normal: try fast local first, then onion, then relay
                if ("NEARBY" in availableTransports) result.add("NEARBY")
                if ("TOR" in availableTransports) result.add("TOR")
                if ("RELAY" in availableTransports) result.add("RELAY")
                if ("WIFI_DIRECT" in availableTransports) result.add("WIFI_DIRECT")
            }
        }

        return result
    }
}

enum class MessageClass {
    NORMAL,
    EPHEMERAL,      // typing, presence
    LARGE_MEDIA,
    SIGNALING       // call signals
}

enum class PrivacyMode {
    STANDARD,
    MAXIMUM
}

enum class TransportCapability {
    NEARBY_AVAILABLE,
    TOR_CONNECTED,
    RELAY_REACHABLE,
    WIFI_DIRECT_AVAILABLE
}
