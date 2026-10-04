package com.torxone.app.transport

import android.util.Log
import java.security.MessageDigest

/** Never pass secrets, payloads, onion addresses or exception messages. */
object DeliveryDiagnostics {
    private val states = setOf("QUEUED", "TRANSMITTING", "TRANSPORT_ACCEPTED", "RETRY_WAIT", "DELIVERED", "READ",
        "FAILED", "EXPIRED", "WAITING_FOR_PEER", "UNREACHABLE", "CANCELLED", "IO_FAILURE", "TIMEOUT",
        "GENERATION_CHANGED", "INVALID_LENGTH", "INVALID_HMAC", "DECRYPT_OR_COMMIT_REJECTED", "ACK_COMMITTED",
        "RECOVERY_RETRY", "RECEIVER_RECONCILIATION_BUDGET", "UPLOAD_RETRY_BUDGET", "CHUNK_RETRY_BUDGET", "COMPLETION_NOT_COMMITTED",
        "MEDIA_LOCAL_COMPLETE", "UPLOAD_REJECTED", "RECONCILIATION_READ_REJECTED")
    private val events = setOf("outbox_selected", "lane_worker_start", "route_lookup", "tor_attempt_start",
        "tor_connect_success", "tor_connect_timeout", "tor_connect_failed", "tor_write_success", "tor_write_failed",
        "tor_send_failed", "transport_fallback", "transport_accepted", "ack_wait", "ack_received", "ack_enqueued",
        "ack_enqueue_failed", "retry_scheduled", "circuit_open", "circuit_half_open", "circuit_closed", "stream_created",
        "stream_reused", "stream_invalidated", "receiver_frame", "queue_auth_success", "queue_auth_failure",
        "decrypt_success", "decrypt_failure", "db_commit", "message_delivered", "relationship_degraded",
        "connection_not_found", "media_waiting_for_peer", "media_recovery_failed", "media_completion_retry", "media_transfer")
    fun id(value: String?): String = value?.takeIf { it.isNotBlank() }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    } ?: "none"

    fun event(name: String, relationship: String? = null, conversation: String? = null,
              delivery: String? = null, sequence: Long? = null, transport: TransportType? = null,
              state: String? = null, attempt: Int? = null, elapsedMs: Long? = null,
              present: Boolean? = null) {
        Log.i("TORX_DIAG", line(name, relationship, conversation, delivery, sequence, transport, state, attempt, elapsedMs, present))
    }

    fun forDestination(name: String, destination: TransportDestination, transport: TransportType,
                       state: String? = null, elapsedMs: Long? = null, present: Boolean? = null) =
        event(name, destination.relationshipId, destination.conversationId, destination.deliveryId,
            destination.applicationSequence, transport, state, elapsedMs = elapsedMs, present = present)

    internal fun line(name: String, relationship: String? = null, conversation: String? = null,
                      delivery: String? = null, sequence: Long? = null, transport: TransportType? = null,
                      state: String? = null, attempt: Int? = null, elapsedMs: Long? = null,
                      present: Boolean? = null): String {
        val safeName = name.takeIf { it in events } ?: "unknown_event"
        val safeState = state?.let { it.takeIf(states::contains) ?: "UNKNOWN_STATE" }
        return buildString {
            append("event=$safeName relationship=${id(relationship)} conversation=${id(conversation)} delivery=${id(delivery)}")
            sequence?.let { append(" sequence=$it") }
            transport?.let { append(" transport=$it") }
            safeState?.let { append(" state=$it") }
            attempt?.let { append(" attempt=$it") }
            elapsedMs?.let { append(" elapsedMs=${it.coerceAtLeast(0)}") }
            present?.let { append(" present=$it") }
        }
    }
}
