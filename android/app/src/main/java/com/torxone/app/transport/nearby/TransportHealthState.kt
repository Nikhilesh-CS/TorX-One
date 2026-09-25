package com.torxone.app.transport.nearby

/**
 * Health states for direct Nearby transport.
 * Allows UI and routing layers to observe connectivity status.
 */
enum class TransportHealthState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    READY,
    DEGRADED
}
