package com.torxone.app.transport.halow

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Application protocol between TorX One and an IP-connected TorX HaLow bridge. */
object HaLowGatewayProtocol {
    const val VERSION = 1
    const val CONTROL_PORT = 45821
    const val SERVICE_TYPE = "_torx-halow._tcp."
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAGIC = 0x54484731 // THG1
    private const val HEADER_BYTES = 16

    enum class Kind(val wire: Int) {
        HELLO_REQUEST(1), HELLO_RESPONSE(2), SEND_PACKET(3), RECEIVED_PACKET(4),
        SEND_RESULT(5), STATUS_REQUEST(6), STATUS_RESPONSE(7), PING(8), PONG(9),
        GLOBAL_GATEWAY_SUBMIT(10), GLOBAL_GATEWAY_RESULT(11);
        companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
    }

    data class Frame(val kind: Kind, val streamId: Int, val payload: ByteArray, val flags: Int = 0)

    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) { "HaLow payload exceeds $MAX_PAYLOAD_BYTES bytes" }
        require(frame.flags in 0..255)
        val output = ByteBuffer.allocate(HEADER_BYTES + frame.payload.size + 4).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(VERSION.toByte()).put(frame.kind.wire.toByte()).put(frame.flags.toByte())
            .put(0).putInt(frame.payload.size).putInt(frame.streamId).put(frame.payload)
        val crc = CRC32().apply { update(output.array(), 0, output.position()) }.value.toInt()
        output.putInt(crc)
        return output.array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size >= HEADER_BYTES + 4) { "HaLow frame too short" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "HaLow frame magic mismatch" }
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported HaLow protocol version" }
        val kind = Kind.fromWire(input.get().toInt() and 0xff) ?: error("Unknown HaLow frame kind")
        val flags = input.get().toInt() and 0xff
        input.get() // reserved
        val length = input.int
        require(length in 0..MAX_PAYLOAD_BYTES && bytes.size == HEADER_BYTES + length + 4) { "Invalid HaLow frame length" }
        val streamId = input.int
        val payload = ByteArray(length).also(input::get)
        val expected = input.int
        val actual = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        require(expected == actual) { "HaLow frame checksum mismatch" }
        return Frame(kind, streamId, payload, flags)
    }

    fun encodeRoutedPayload(destinationNodeId: String, opaquePayload: ByteArray): ByteArray {
        val node = destinationNodeId.encodeToByteArray()
        require(node.isNotEmpty() && node.size <= 64)
        require(1 + node.size + opaquePayload.size <= MAX_PAYLOAD_BYTES)
        return ByteBuffer.allocate(1 + node.size + opaquePayload.size).put(node.size.toByte()).put(node).put(opaquePayload).array()
    }

    fun decodeRoutedPayload(payload: ByteArray): Pair<String, ByteArray> {
        require(payload.isNotEmpty())
        val length = payload[0].toInt() and 0xff
        require(length in 1..64 && payload.size > 1 + length)
        return payload.copyOfRange(1, 1 + length).decodeToString() to payload.copyOfRange(1 + length, payload.size)
    }
}

object HaLowGatewayHelloCodec {
    const val CHALLENGE_BYTES = 32
    private const val SIGNATURE_BYTES = 64

    fun decodeAndVerify(payload: ByteArray, challenge: ByteArray): HaLowGatewayCapabilities {
        require(challenge.size == CHALLENGE_BYTES && payload.size > SIGNATURE_BYTES + 32)
        val signed = payload.copyOfRange(0, payload.size - SIGNATURE_BYTES)
        val signature = payload.copyOfRange(payload.size - SIGNATURE_BYTES, payload.size)
        val input = ByteBuffer.wrap(signed).order(ByteOrder.BIG_ENDIAN)
        fun text(max: Int): String {
            require(input.hasRemaining())
            val length = input.get().toInt() and 0xff
            require(length in 1..max && input.remaining() >= length)
            return ByteArray(length).also(input::get).decodeToString()
        }
        val id = text(64); val firmware = text(32); val chipset = text(32); val region = text(16)
        require(input.remaining() == 37)
        val maxPayload = input.int
        val supportsMedia = input.get().toInt() != 0
        val publicKey = ByteArray(32).also(input::get)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(challenge, 0, challenge.size); update(signed, 0, signed.size)
        }
        require(verifier.verifySignature(signature)) { "HaLow gateway identity proof failed" }
        return HaLowGatewayCapabilities(id, firmware, chipset, region, maxPayload, supportsMedia, publicKey)
    }
}

data class HaLowGatewayCandidate(
    val serviceName: String,
    val host: String,
    val port: Int,
    val networkHandle: Long? = null
) {
    init { require(serviceName.isNotBlank()); require(host.isNotBlank()); require(port in 1..65535) }
}

data class HaLowGatewayCapabilities(
    val gatewayId: String,
    val firmwareVersion: String,
    val chipset: String,
    val region: String,
    val maxPayloadBytes: Int,
    val supportsMedia: Boolean,
    val identityKey: ByteArray
) {
    init {
        require(gatewayId.isNotBlank() && gatewayId.length <= 64)
        require(firmwareVersion.length <= 32 && chipset.length <= 32 && region.length <= 16)
        require(maxPayloadBytes in 4096..HaLowGatewayProtocol.MAX_PAYLOAD_BYTES)
        require(identityKey.size == 32)
    }
}

sealed class HaLowConnectionState {
    data object Idle : HaLowConnectionState()
    data object Discovering : HaLowConnectionState()
    data class Connecting(val candidate: HaLowGatewayCandidate) : HaLowConnectionState()
    data class NeedsPairing(val capabilities: HaLowGatewayCapabilities) : HaLowConnectionState()
    data class Ready(val capabilities: HaLowGatewayCapabilities) : HaLowConnectionState()
    data class Failed(val reason: String) : HaLowConnectionState()
}
