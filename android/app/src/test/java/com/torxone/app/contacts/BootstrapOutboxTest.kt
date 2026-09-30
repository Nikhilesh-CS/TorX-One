package com.torxone.app.contacts

import com.torxone.app.connection.Connection
import org.junit.Assert.*
import org.junit.Test

class BootstrapOutboxTest {
    @Test fun inviteAndDeliveryIdsMatchAuthenticatedConfirmationContract() {
        val connection = Connection(relationshipId = "rel", sendQueueId = "send", recvQueueId = "recv",
            sendAuth = ByteArray(32), recvAuth = ByteArray(32))
        val item = bootstrapOutboxItem("invite-id", connection, "chat", byteArrayOf(1), "delivery-id")
        assertEquals("invite-id", item.logicalMessageId)
        assertEquals("delivery-id", item.deliveryId)
        assertEquals(connection.connectionId, item.connectionId)
        assertEquals("rel", item.relationshipId)
        assertEquals("invite-invite-id", item.queueAddress)
        assertTrue(item.expectsAck)
    }
}
