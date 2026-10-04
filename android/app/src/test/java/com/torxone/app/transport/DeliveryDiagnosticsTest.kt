package com.torxone.app.transport

import org.junit.Assert.*
import org.junit.Test

class DeliveryDiagnosticsTest {
    @Test fun diagnosticFieldsCannotExposeCallerSecretsOrInjectAnotherRecord() {
        val secret = "private-onion-and-message\nevent=message_delivered"
        val line = DeliveryDiagnostics.line(secret, secret, secret, secret,
            state = secret, sequence = 2, attempt = 3, elapsedMs = -1, present = false)
        assertFalse(line.contains(secret))
        assertFalse(line.contains("private"))
        assertFalse(line.contains("\n"))
        assertTrue(line.startsWith("event=unknown_event "))
        assertTrue(line.contains("state=UNKNOWN_STATE"))
        assertTrue(line.contains("elapsedMs=0"))
        assertTrue(Regex("relationship=[a-f0-9]{12} conversation=[a-f0-9]{12} delivery=[a-f0-9]{12}").containsMatchIn(line))
    }

    @Test fun stableHashedIdsCorrelateAcrossPeersWithoutExposingDeliveryId() {
        val sender = DeliveryDiagnostics.line("transport_accepted", delivery = "delivery-private", state = "TRANSPORT_ACCEPTED")
        val receiver = DeliveryDiagnostics.line("receiver_frame", delivery = "delivery-private")
        val hash = DeliveryDiagnostics.id("delivery-private")
        assertTrue(sender.contains("delivery=$hash"))
        assertTrue(receiver.contains("delivery=$hash"))
        assertFalse(sender.contains("delivery-private"))
        assertNotEquals(hash, DeliveryDiagnostics.id("another-delivery"))
    }
}
