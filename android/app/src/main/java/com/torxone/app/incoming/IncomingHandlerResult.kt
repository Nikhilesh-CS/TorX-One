package com.torxone.app.incoming

/**
 * Result of processing an incoming payload by an individual feature handler.
 * Provides explicit semantics for whether an envelope should be deduplicated, retried, or rejected.
 */
sealed interface IncomingHandlerResult {
    /** The incoming envelope was successfully processed and state applied. Deduplication record should be committed. */
    data object Applied : IncomingHandlerResult

    /** The incoming envelope was already processed or redundant. Deduplication record should be committed and/or ACK resent. */
    data object Idempotent : IncomingHandlerResult

    /** A transient failure occurred. Deduplication record MUST NOT be committed so the envelope can be retried. */
    data class RetryableFailure(val reason: String, val cause: Throwable? = null) : IncomingHandlerResult

    /** The incoming envelope is malformed, untrusted, or violates security policy. Envelope is permanently rejected. */
    data class RejectedInvalid(val reason: String) : IncomingHandlerResult
}
