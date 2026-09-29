package com.torxone.app.transport.lora

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class TorXRadioProtocolTest {
    @Test fun `frame round trip preserves packet`() {
        val original = TorXRadioProtocol.Frame(TorXRadioProtocol.Kind.SEND_PACKET, 41, byteArrayOf(1, 2, 3))
        val decoded = TorXRadioProtocol.decode(TorXRadioProtocol.encode(original))
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.sequence, decoded.sequence)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `corrupt frame is rejected`() {
        val encoded = TorXRadioProtocol.encode(TorXRadioProtocol.Frame(TorXRadioProtocol.Kind.PING, 1, byteArrayOf(9)))
        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()
        TorXRadioProtocol.decode(encoded)
    }

    @Test fun `signed hello proves hardware identity`() {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val fields = ByteArrayOutputStream().apply {
            listOf("radio-01", "0.1.0", "esp32-s3", "sx1262", "IN865").forEach {
                val bytes = it.encodeToByteArray(); write(bytes.size); write(bytes)
            }
            write(byteArrayOf(0x01, 0xE0.toByte()))
            write(privateKey.generatePublicKey().encoded)
        }.toByteArray()
        val signature = Ed25519Signer().apply {
            init(true, privateKey); update(challenge, 0, challenge.size); update(fields, 0, fields.size)
        }.generateSignature()
        val result = TorXRadioHelloCodec.decodeAndVerify(fields + signature, challenge)
        assertEquals("radio-01", result.deviceId)
        assertEquals("sx1262", result.radioChip)
        assertEquals(480, result.maxPayloadBytes)
    }
}
