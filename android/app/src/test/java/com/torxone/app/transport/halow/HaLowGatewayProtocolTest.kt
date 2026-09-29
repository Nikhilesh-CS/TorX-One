package com.torxone.app.transport.halow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HaLowGatewayProtocolTest {
    @Test fun `gateway frame round trips large payload`() {
        val payload = ByteArray(128 * 1024) { (it and 0xff).toByte() }
        val frame = HaLowGatewayProtocol.Frame(HaLowGatewayProtocol.Kind.SEND_PACKET, 72, payload, flags = 1)
        val decoded = HaLowGatewayProtocol.decode(HaLowGatewayProtocol.encode(frame))
        assertEquals(frame.kind, decoded.kind)
        assertEquals(72, decoded.streamId)
        assertEquals(1, decoded.flags)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test fun `routed payload preserves destination and opaque bytes`() {
        val decoded = HaLowGatewayProtocol.decodeRoutedPayload(
            HaLowGatewayProtocol.encodeRoutedPayload("node-far", byteArrayOf(4, 5, 6))
        )
        assertEquals("node-far", decoded.first)
        assertArrayEquals(byteArrayOf(4, 5, 6), decoded.second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `checksum corruption is rejected`() {
        val bytes = HaLowGatewayProtocol.encode(HaLowGatewayProtocol.Frame(HaLowGatewayProtocol.Kind.PING, 1, byteArrayOf(1)))
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        HaLowGatewayProtocol.decode(bytes)
    }
}
