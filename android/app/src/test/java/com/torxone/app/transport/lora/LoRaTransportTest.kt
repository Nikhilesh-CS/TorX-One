package com.torxone.app.transport.lora

import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoRaTransportTest {
    private class NoDiscovery : TorXRadioDiscovery {
        override val candidates = MutableSharedFlow<TorXRadioCandidate>()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
    }
    private class FakeLink : TorXRadioLink {
        override val method = RadioConnectionMethod.BLE
        override val incomingFrames: Flow<ByteArray> = MutableSharedFlow()
        var sent: ByteArray? = null
        override suspend fun connect(candidate: TorXRadioCandidate) = TorXRadioCapabilities(
            "radio", "1", "board", "sx1262", "IN865", 480, ByteArray(32) { 1 }
        )
        override suspend fun send(frame: ByteArray): Boolean { sent = frame; return true }
        override suspend fun disconnect() = Unit
    }

    @Test fun `authenticated radio carries explicitly addressed small packet`() = runBlocking {
        val link = FakeLink()
        val manager = TorXRadioManager(CoroutineScope(Dispatchers.Unconfined), NoDiscovery(), TorXRadioLinkFactory { link }, { _, _ -> true })
        manager.connect(TorXRadioCandidate("id", "TorX", RadioConnectionMethod.BLE, TorXRadioProtocol.SERVICE_UUID))
        val transport = LoRaTransport(manager)
        val result = transport.send(TransportDestination("queue", mapOf(LoRaTransport.LORA_NODE_ID_HINT to "node-b")), byteArrayOf(7, 8))
        assertEquals(TransportResult.Accepted(com.torxone.app.transport.TransportType.LORA), result)
        assertEquals(TorXRadioProtocol.Kind.SEND_PACKET, TorXRadioProtocol.decode(requireNotNull(link.sent)).kind)
    }

    @Test fun `large payload is refused before radio write`() = runBlocking {
        val link = FakeLink()
        val manager = TorXRadioManager(CoroutineScope(Dispatchers.Unconfined), NoDiscovery(), TorXRadioLinkFactory { link }, { _, _ -> true })
        manager.connect(TorXRadioCandidate("id", null, RadioConnectionMethod.BLE, TorXRadioProtocol.SERVICE_UUID))
        val result = LoRaTransport(manager).send(
            TransportDestination("queue", mapOf(LoRaTransport.LORA_NODE_ID_HINT to "node-b")), ByteArray(480)
        )
        assertTrue(result is TransportResult.Failed)
        assertEquals(null, link.sent)
    }
}
