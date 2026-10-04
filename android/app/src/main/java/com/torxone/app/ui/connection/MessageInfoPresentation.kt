package com.torxone.app.ui.connection

import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity

data class MessageInfoRow(val label: String, val value: String? = null, val timestamp: Long? = null)

data class MessageInfoPresentation(val rows: List<MessageInfoRow>, val canRetry: Boolean) {
    companion object {
        /** Only persisted facts: no synthetic sent time, transport history, or security badge. */
        fun from(message: MessageEntity, outbox: List<OutboxEntity>): MessageInfoPresentation {
            val deliveries = outbox.filter { it.logicalMessageId == message.logicalMessageId }
            val terminalDelivery = deliveries.firstNotNullOfOrNull {
                ConnectionUxPresentation.terminalOrderedHead(it.status, it.applicationSequence)
            }
            val outgoing = message.direction == MessageDirection.OUTGOING
            val rows = mutableListOf(
                MessageInfoRow("Message ID", message.logicalMessageId),
                MessageInfoRow("Direction", if (outgoing) "Outgoing" else "Incoming"),
                MessageInfoRow("Status", message.status.lowercase().replace('_', ' ')),
                MessageInfoRow("Created", timestamp = message.createdAt)
            )
            if (outgoing) {
                rows += MessageInfoRow("Queued", timestamp = deliveries.minOfOrNull { it.createdAt },
                    value = if (deliveries.isEmpty()) "Time not recorded" else null)
                val accepted = deliveries.filter { it.status == "TRANSPORT_ACCEPTED" }
                rows += MessageInfoRow("Latest transport acceptance", timestamp = accepted.maxOfOrNull { it.updatedAt },
                    value = if (accepted.isEmpty()) "Time not recorded" else null)
                rows += MessageInfoRow("Delivered (peer ACK)", timestamp = message.deliveredAt,
                    value = if (message.deliveredAt == null) "No ACK time recorded" else null)
                rows += MessageInfoRow("Read (peer receipt)", timestamp = message.readAt,
                    value = if (message.readAt == null) "No read receipt recorded" else null)
                if (deliveries.isNotEmpty()) {
                    val waiting = deliveries.any { it.status == "WAITING_FOR_PEER" }
                    rows += MessageInfoRow("Delivery stage", when {
                        terminalDelivery != null -> terminalDelivery.second
                        waiting -> "Waiting for peer. Retry delivery or rescan the peer's signed QR; encrypted data remains saved."
                        accepted.isNotEmpty() -> "Waiting for authenticated receiver acknowledgement"
                        deliveries.any { it.status == "TRANSMITTING" } -> "Connecting to peer"
                        deliveries.any { it.status == "RETRY_WAIT" } -> "Retry scheduled; receiver delivery not confirmed"
                        else -> "Queued for an authenticated path"
                    })
                    rows += MessageInfoRow("Pending deliveries", deliveries.size.toString())
                    rows += MessageInfoRow("Recorded attempts", deliveries.sumOf { it.attemptCount }.toString())
                    rows += MessageInfoRow("Next scheduled attempt", timestamp = if (waiting || terminalDelivery != null) null else deliveries
                        .map { it.nextAttemptAt }.filter { it > 0 }.minOrNull(),
                        value = if (terminalDelivery != null) "Blocked until delivery acknowledgement or explicit delivery repair"
                            else if (waiting) "Paused until peer/path recovery or manual retry" else if (deliveries.none { it.nextAttemptAt > 0 }) "Waiting for agent" else null)
                    rows += MessageInfoRow("Encryption", if (deliveries.all { it.ciphertext.isNotEmpty() })
                        "Ciphertext persisted in outbox" else "Ciphertext unavailable")
                } else rows += MessageInfoRow("Encryption", "Per-message encryption history not retained")
            } else {
                rows += MessageInfoRow("Received locally", timestamp = message.receivedAt,
                    value = if (message.receivedAt == null) "Time not recorded" else null)
                rows += MessageInfoRow("Opened locally", timestamp = message.readAt,
                    value = if (message.readAt == null) "Time not recorded" else null)
            }
            rows += MessageInfoRow("Transport used", "Historical transport not recorded")
            rows += MessageInfoRow("Security", "Double Ratchet messaging; no per-message audit record retained")
            rows += MessageInfoRow("Integrity", "No per-message verification history retained")
            rows += MessageInfoRow("Timestamp source", "Created and peer receipts use sender/peer clocks; clocks may differ")
            val retry = outgoing && deliveries.isNotEmpty() && terminalDelivery == null && message.readAt == null && message.deliveredAt == null &&
                message.deletedAt == null && message.status !in setOf("EXPIRED", "FAILED")
            return MessageInfoPresentation(rows, retry)
        }
    }
}
