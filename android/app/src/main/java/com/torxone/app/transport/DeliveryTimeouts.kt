package com.torxone.app.transport

/** Retry preserves data during slow circuit construction/publication. */
object DeliveryTimeouts {
    // Java's SOCKS Socket combines proxy negotiation and onion connection in connect().
    const val SOCKS_ONION_CONNECT_MS = 45_000L
    const val HEADER_WRITE_MS = 10_000L
    const val FRAME_WRITE_MS = 15_000L
    const val TOR_ATTEMPT_MS = 60_000L
    const val ROUTE_PREPARATION_MS = 2_000L
    const val AVAILABILITY_MS = 2_000L
    const val FALLBACK_ATTEMPT_MS = 20_000L
    const val ROUTER_ATTEMPT_MS = 180_000L
    const val RECEIVER_ACK_MS = 30_000L
    const val STREAM_IDLE_MS = 90_000L
}
