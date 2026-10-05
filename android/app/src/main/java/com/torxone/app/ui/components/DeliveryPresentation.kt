package com.torxone.app.ui.components

import com.torxone.app.agent.DeliveryStatus

enum class DeliveryGlyph { WAITING, SENT, RECEIVED, ERROR, EXPIRED }

/** Presentation only: never advances a receipt or writes delivery state. */
data class DeliveryPresentation(val label: String, val glyph: DeliveryGlyph, val read: Boolean = false) {
    companion object {
        fun from(status: DeliveryStatus): DeliveryPresentation = when (status) {
            DeliveryStatus.CREATED, DeliveryStatus.ENCRYPTED, DeliveryStatus.QUEUED ->
                DeliveryPresentation("Waiting to send", DeliveryGlyph.WAITING)
            DeliveryStatus.TRANSMITTING -> DeliveryPresentation("Sending", DeliveryGlyph.WAITING)
            DeliveryStatus.TRANSPORT_ACCEPTED -> DeliveryPresentation("Sent", DeliveryGlyph.SENT)
            DeliveryStatus.DEVICE_RECEIVED -> DeliveryPresentation("Received by device", DeliveryGlyph.RECEIVED)
            DeliveryStatus.DELIVERED -> DeliveryPresentation("Delivered", DeliveryGlyph.RECEIVED)
            DeliveryStatus.READ -> DeliveryPresentation("Read", DeliveryGlyph.RECEIVED, read = true)
            DeliveryStatus.RETRY_WAIT -> DeliveryPresentation("Waiting to retry", DeliveryGlyph.WAITING)
            DeliveryStatus.WAITING_FOR_PEER -> DeliveryPresentation("Waiting for this contact", DeliveryGlyph.WAITING)
            DeliveryStatus.FAILED -> DeliveryPresentation("Couldn’t send", DeliveryGlyph.ERROR)
            DeliveryStatus.EXPIRED -> DeliveryPresentation("Expired", DeliveryGlyph.EXPIRED)
        }
    }
}
