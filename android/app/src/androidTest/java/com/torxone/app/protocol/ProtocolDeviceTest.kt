package com.torxone.app.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolDeviceTest {
    @Test fun transportCodecRunsOnAndroid() {
        val frame = OpaqueTransportEnvelope(version = 1, envelopeId = "device-test",
            queueAddress = "queue", opaqueCiphertext = byteArrayOf(1, 2), queueAuthenticator = ByteArray(32))
        val decoded = ProtocolCodec.decodeTransportEnvelope(ProtocolCodec.encodeTransportEnvelope(frame))
        assertEquals(frame.queueAddress, decoded.queueAddress)
        assertArrayEquals(frame.opaqueCiphertext, decoded.opaqueCiphertext)
    }
}
