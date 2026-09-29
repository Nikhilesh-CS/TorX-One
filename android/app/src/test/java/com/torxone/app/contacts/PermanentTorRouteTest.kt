package com.torxone.app.contacts

import com.torxone.app.connection.Connection
import com.torxone.app.transport.tor.TorRouteManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermanentTorRouteTest {
    private val onion = "a".repeat(56) + ".onion"

    @Test
    fun `bootstrap installs permanent send queue route`() {
        val routes = TorRouteManager()
        val connection = Connection(
            relationshipId = "relationship",
            sendQueueId = "permanent-send-queue",
            recvQueueId = "permanent-recv-queue",
            sendAuth = ByteArray(32),
            recvAuth = ByteArray(32)
        )

        bindPermanentTorRoute(routes, connection, onion)

        assertEquals(onion, routes.resolve(connection.sendQueueId)?.onionHost)
        assertNull(routes.resolve("invite-temporary"))
    }
}
