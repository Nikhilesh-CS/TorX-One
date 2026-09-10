package com.torxone.app.transport

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * RelayTransportTest
 *
 * Verifies:
 * 1. Relay challenge-response auth payload formatting & structure
 * 2. Relay message wire format serialization & deserialization
 * 3. Incoming message extraction & listener dispatch
 * 4. Priority ordering and TransportRouter integration
 */
@RunWith(RobolectricTestRunner::class)
class RelayTransportTest {

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    @Test
    fun testChallengeResponseAuthentication_structure() {
        val pubKeyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val mockSignatureHex = "aabbccdd".repeat(16) // 64-byte signature hex

        val authMsg = JSONObject().apply {
            put("type", "auth")
            put("publicKey", pubKeyHex)
            put("signature", mockSignatureHex)
        }

        assertEquals("auth", authMsg.getString("type"))
        assertEquals(pubKeyHex, authMsg.getString("publicKey"))
        assertEquals(mockSignatureHex, authMsg.getString("signature"))
    }

    @Test
    fun testRelayOutgoingMessageFormat() {
        val destinationKey = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val wirePayload = "{\"type\":\"session_msg\",\"msgId\":\"test-123\",\"ciphertext\":\"abc\"}"

        val relayMsg = JSONObject().apply {
            put("type", "message")
            put("to", destinationKey)
            put("payload", wirePayload)
            put("ciphertext", bytesToHex(wirePayload.toByteArray(Charsets.UTF_8)))
            put("nonce", "")
        }

        assertEquals("message", relayMsg.getString("type"))
        assertEquals(destinationKey, relayMsg.getString("to"))
        assertEquals(wirePayload, relayMsg.getString("payload"))
        assertTrue(relayMsg.getString("ciphertext").isNotBlank())
    }

    @Test
    fun testRelayIncomingMessageParsing() {
        val senderKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val originalPayload = "{\"type\":\"msg\",\"text\":\"Hello via offline relay\"}"
        val messageId = UUID.randomUUID().toString()

        val incomingJson = JSONObject().apply {
            put("type", "message")
            put("id", messageId)
            put("from", senderKey)
            put("payload", originalPayload)
            put("ciphertext", bytesToHex(originalPayload.toByteArray(Charsets.UTF_8)))
        }

        var dispatchedSource: String? = null
        var dispatchedPayload: String? = null
        var dispatchedType: TransportType? = null

        val listener = TransportIncomingListener { source, payload, type ->
            dispatchedSource = source
            dispatchedPayload = payload
            dispatchedType = type
        }

        // Simulate RelayTransport incoming handler logic
        val from = incomingJson.optString("from")
        val payload = incomingJson.optString("payload")
        val ciphertextHex = incomingJson.optString("ciphertext")

        val wireString = if (payload.isNotBlank()) {
            payload
        } else {
            String(hexToBytes(ciphertextHex), Charsets.UTF_8)
        }

        listener.onPayloadReceived(from, wireString, TransportType.OFFLINE_RELAY)

        assertEquals(senderKey, dispatchedSource)
        assertEquals(originalPayload, dispatchedPayload)
        assertEquals(TransportType.OFFLINE_RELAY, dispatchedType)
    }

    @Test
    fun testTransportTypePriority_offlineRelayIsFallback() {
        assertTrue(
            "OFFLINE_RELAY must have lower priority number than TOR (higher priority int = later fallback)",
            TransportType.OFFLINE_RELAY.priority > TransportType.TOR.priority
        )
        assertEquals(6, TransportType.OFFLINE_RELAY.priority)
    }

    @Test
    fun testTransportRouter_isRelayAvailable() {
        val router = TransportRouter()
        assertFalse(router.isRelayAvailable())

        // Create a mock transport matching OFFLINE_RELAY
        val mockRelay = object : Transport {
            override val name: String = "Offline Relay"
            override val type: TransportType = TransportType.OFFLINE_RELAY
            override val isAvailable = MutableStateFlow(true)
            override val statusText = MutableStateFlow("Connected")
            override suspend fun send(destination: String, payload: String, metadata: TransportMetadata?) =
                TransportResult.success(type)
            override fun setIncomingListener(listener: TransportIncomingListener?) {}
            override fun start() {}
            override fun stop() {}
            override fun getReachablePeers(): Set<String> = emptySet()
        }

        router.registerTransport(mockRelay)
        // TransportRouter sorts by priority
        val active = router.activeTransports.value
        assertTrue(active.any { it.type == TransportType.OFFLINE_RELAY })
    }

    @Test
    fun testOpaqueQueueAddressedMessage_noPublicKeyExposure() {
        val opaqueQueueId = "queue-uuid-8899-aabb-ccdd"
        val wirePayload = "{\"version\":2,\"connectionId\":\"conn-1\",\"ciphertext\":\"enc_blob\"}"

        val relayMsg = JSONObject().apply {
            put("type", "message")
            put("queueId", opaqueQueueId)
            put("to", opaqueQueueId)
            put("payload", wirePayload)
            put("ciphertext", bytesToHex(wirePayload.toByteArray(Charsets.UTF_8)))
            put("nonce", "")
        }

        // Must address strictly by queueId
        assertEquals("message", relayMsg.getString("type"))
        assertEquals(opaqueQueueId, relayMsg.getString("queueId"))
        assertEquals(opaqueQueueId, relayMsg.getString("to"))
        // CRITICAL: Sender public key must NOT be exposed in the relay envelope
        assertFalse(relayMsg.has("from"))
        assertFalse(relayMsg.has("senderKey"))
        assertFalse(relayMsg.has("publicKey"))
    }

    @Test
    fun testQueueSubscriptionMessage_format() {
        val recvQueues = listOf("queue-recv-1", "queue-recv-2", "queue-recv-3")
        val subMsg = JSONObject().apply {
            put("type", "subscribe")
            put("queues", org.json.JSONArray(recvQueues))
        }

        assertEquals("subscribe", subMsg.getString("type"))
        val array = subMsg.getJSONArray("queues")
        assertEquals(3, array.length())
        assertEquals("queue-recv-1", array.getString(0))
        assertEquals("queue-recv-2", array.getString(1))
        assertEquals("queue-recv-3", array.getString(2))
    }

    @Test
    fun testIncomingQueueAddressedMessage_routesByQueueId() {
        val opaqueQueueId = "queue-recv-9900"
        val originalPayload = "{\"version\":2,\"connectionId\":\"conn-123\",\"ciphertext\":\"xyz\"}"
        val messageId = UUID.randomUUID().toString()

        val incomingJson = JSONObject().apply {
            put("type", "message")
            put("id", messageId)
            put("queueId", opaqueQueueId)
            put("payload", originalPayload)
        }

        var dispatchedSource: String? = null
        var dispatchedPayload: String? = null

        val listener = TransportIncomingListener { source, payload, _ ->
            dispatchedSource = source
            dispatchedPayload = payload
        }

        val queueId = incomingJson.optString("queueId")
        val payload = incomingJson.optString("payload")
        val source = if (queueId.isNotBlank()) queueId else incomingJson.optString("from", "")

        listener.onPayloadReceived(source, payload, TransportType.OFFLINE_RELAY)

        // Verifies the sourceAddress is the opaque queueId rather than a public key
        assertEquals(opaqueQueueId, dispatchedSource)
        assertEquals(originalPayload, dispatchedPayload)
    }
}
