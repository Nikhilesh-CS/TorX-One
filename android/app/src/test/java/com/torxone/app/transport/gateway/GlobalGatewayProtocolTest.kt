package com.torxone.app.transport.gateway

import com.torxone.app.transport.mesh.MeshPriority
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalGatewayProtocolTest {
    private val onion = "a".repeat(56) + ".onion"

    @Test fun `submission round trip preserves opaque ciphertext and route`() {
        val packet = GatewaySubmission(
            destinationGatewayOnion = onion, destinationNodeId = "remote-node",
            createdAt = 1_000, expiresAt = 61_000, priority = MeshPriority.HIGH,
            opaquePayload = byteArrayOf(9, 8, 7)
        )
        val decoded = GatewaySubmissionCodec.decode(GatewaySubmissionCodec.encode(packet))
        assertEquals(packet.packetId, decoded.packetId)
        assertEquals(onion, decoded.destinationGatewayOnion)
        assertEquals("remote-node", decoded.destinationNodeId)
        assertEquals(MeshPriority.HIGH, decoded.priority)
        assertArrayEquals(packet.opaquePayload, decoded.opaquePayload)
    }

    @Test fun `ingress rejects replay expired packet and excessive future timestamp`() {
        val now = 100_000L
        val policy = GatewayIngressPolicy(clock = { now })
        val valid = GatewaySubmission(destinationGatewayOnion = onion, destinationNodeId = "n",
            createdAt = now, expiresAt = now + 10_000, opaquePayload = byteArrayOf(1))
        assertTrue(policy.accept(valid))
        assertFalse(policy.accept(valid))
        assertFalse(policy.accept(valid.copy(packetId = java.util.UUID.randomUUID().toString(), expiresAt = now)))
        assertFalse(policy.accept(valid.copy(packetId = java.util.UUID.randomUUID().toString(), createdAt = now + 300_001, expiresAt = now + 310_000)))
    }

    @Test fun `route directory expires and rejects invalid onion`() {
        var now = 1_000L
        val routes = GatewayRouteDirectory { now }
        assertFalse(routes.install(GatewayRoute("node", "invalid.onion", now + 100, "signed-peer")))
        assertTrue(routes.install(GatewayRoute("node", onion, now + 100, "signed-peer")))
        assertEquals(onion, routes.resolve("node")?.gatewayOnion)
        now += 101
        assertEquals(null, routes.resolve("node"))
    }
}
