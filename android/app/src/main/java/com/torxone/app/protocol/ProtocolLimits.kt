package com.torxone.app.protocol

/**
 * Hard protocol limits enforced before allocating memory or processing frames.
 */
object ProtocolLimits {
    /** Maximum size for an opaque transport envelope (64 KB for Milestone 1) */
    const val MAX_TRANSPORT_ENVELOPE_BYTES = 64 * 1024

    /** Maximum size for decrypted secure envelope payload */
    const val MAX_SECURE_PAYLOAD_BYTES = 32 * 1024

    /** Maximum text message length (characters) */
    const val MAX_TEXT_LENGTH = 10_000

    /** Maximum identifier length (UUID / queue ID) */
    const val MAX_ID_LENGTH = 128

    /** Maximum allowed wall-clock timestamp skew (24 hours) */
    const val MAX_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L
}
