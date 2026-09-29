package com.torxone.app.transport.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorRouteManagerTest {
    private val onion = "a".repeat(56) + ".onion"

    @Test
    fun `bind resolve and remove a v3 onion route`() {
        val routes = TorRouteManager()
        val route = TorRoute(onion, 17654)

        routes.bind("queue-1", route)
        assertEquals(route, routes.resolve("queue-1"))

        routes.remove("queue-1")
        assertNull(routes.resolve("queue-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects legacy or malformed onion addresses`() {
        TorRoute("short.onion")
    }
}
