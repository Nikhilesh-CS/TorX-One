package com.torxone.app.transport

import kotlinx.coroutines.flow.Flow

enum class TransportType {
    NEARBY,
    TOR,
    RELAY,
    WIFI_DIRECT,
    FAKE
}

sealed class TransportAvailability {
    data object Available : TransportAvailability()
    data class Unavailable(val reason: String) : TransportAvailability()
}

data class TransportDestination(
    val address: String,
    val hints: Map<String, String> = emptyMap()
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
