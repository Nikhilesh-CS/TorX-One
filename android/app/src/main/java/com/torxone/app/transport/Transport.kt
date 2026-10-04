package com.torxone.app.transport

import kotlinx.coroutines.flow.Flow

enum class TransportType {
    NEARBY,
    TOR,
    RELAY,
    LORA,
    WIFI_HALOW,
    GATEWAY,
    WIFI_DIRECT,
    FAKE,
    WEBRTC
}

sealed class TransportAvailability {
    data object Available : TransportAvailability()
    data class Unavailable(val reason: String) : TransportAvailability()
}

data class TransportDestination(
    val address: String,
    val hints: Map<String, String> = emptyMap(),
    val relationshipId: String? = null,
    /** Diagnostics only; these fields never grant route or peer authorization. */
    val deliveryId: String? = null,
    val conversationId: String? = null,
    val applicationSequence: Long? = null
)

sealed class TransportResult {
    data class Accepted(val transportType: TransportType) : TransportResult()
    data class Failed(val transportType: TransportType, val error: String) : TransportResult()
}

/**
 * Transport abstraction — moves opaque bytes without knowledge of messages, crypto, or UI.
 */
interface Transport {
    val type: TransportType
    fun availability(): Flow<TransportAvailability>
    suspend fun send(
        destination: TransportDestination,
        payload: ByteArray
    ): TransportResult
}

/** A transport that can decide whether it owns a destination's explicit route. */
interface AddressableTransport {
    fun canRoute(destination: TransportDestination): Boolean
    /** Refresh only authenticated route authority; never infer identity from a transport header. */
    suspend fun prepareRoute(destination: TransportDestination): Boolean = canRoute(destination)
}

/** Implementations with persistent/native resources must abort owned IO promptly on cancellation. */
interface RecoverableTransport {
    fun invalidate(destination: TransportDestination)
}
