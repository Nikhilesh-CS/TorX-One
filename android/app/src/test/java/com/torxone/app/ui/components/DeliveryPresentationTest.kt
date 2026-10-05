package com.torxone.app.ui.components

import com.torxone.app.agent.DeliveryStatus
import org.junit.Assert.*
import org.junit.Test

class DeliveryPresentationTest {
    @Test fun localTransportAcceptanceDoesNotClaimRecipientDelivery() {
        val transport = DeliveryPresentation.from(DeliveryStatus.TRANSPORT_ACCEPTED)
        val device = DeliveryPresentation.from(DeliveryStatus.DEVICE_RECEIVED)
        val delivered = DeliveryPresentation.from(DeliveryStatus.DELIVERED)
        assertEquals(DeliveryGlyph.SENT, transport.glyph)
        assertNotEquals(delivered.label, transport.label)
        assertNotEquals(delivered.label, device.label)
        assertFalse(transport.read)
        assertFalse(device.read)
        assertFalse(delivered.read)
        assertTrue(DeliveryPresentation.from(DeliveryStatus.READ).read)
    }

    @Test fun exhaustedWaitingAndTerminalFailureRemainDistinct() {
        val waiting = DeliveryPresentation.from(DeliveryStatus.WAITING_FOR_PEER)
        val failed = DeliveryPresentation.from(DeliveryStatus.FAILED)
        val expired = DeliveryPresentation.from(DeliveryStatus.EXPIRED)
        assertEquals(DeliveryGlyph.WAITING, waiting.glyph)
        assertEquals(DeliveryGlyph.ERROR, failed.glyph)
        assertEquals(DeliveryGlyph.EXPIRED, expired.glyph)
        assertNotEquals(waiting.label, failed.label)
        assertNotEquals(expired.label, failed.label)
    }

    @Test fun everyAuthoritativeStateHasFriendlyUnambiguousReadSemantics() {
        DeliveryStatus.entries.forEach { status ->
            val presentation = DeliveryPresentation.from(status)
            assertTrue(presentation.label.isNotBlank())
            assertFalse(presentation.label.contains('_'))
            assertEquals(status == DeliveryStatus.READ, presentation.read)
        }
    }
}
