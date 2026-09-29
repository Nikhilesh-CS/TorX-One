package com.torxone.app.transport.lora

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Stable phone/firmware contract. All multibyte integers are big endian. */
object TorXRadioProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7e582001-7c69-4f72-9858-746f72786f6e")
    val CONTROL_UUID: UUID = UUID.fromString("7e582002-7c69-4f72-9858-746f72786f6e")
    val PHONE_TO_RADIO_UUID: UUID = UUID.fromString("7e582003-7c69-4f72-9858-746f72786f6e")
    val RADIO_TO_PHONE_UUID: UUID = UUID.fromString("7e582004-7c69-4f72-9858-746f72786f6e")
    const val VERSION = 1
    const val MAX_RADIO_PAYLOAD = 480
    private const val MAGIC = 0x54585231 // TXR1
    private const val HEADER_BYTES = 12

    enum class Kind(val wire: Int) {
        HELLO_REQUEST(1), HELLO_RESPONSE(2), SEND_PACKET(3), RECEIVED_PACKET(4),
        TX_RESULT(5), STATUS_REQUEST(6), STATUS_RESPONSE(7), PING(8), PONG(9);
        companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wire == value } }
    }

    data class Frame(val kind: Kind, val sequence: Int, val payload: ByteArray)

    fun encode(frame: Frame): ByteArray {
        require(frame.payload.size <= MAX_RADIO_PAYLOAD) { "Radio payload exceeds $MAX_RADIO_PAYLOAD bytes" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + frame.payload.size + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC).put(VERSION.toByte()).put(frame.kind.wire.toByte())
            .putShort(frame.payload.size.toShort()).putInt(frame.sequence).put(frame.payload)
        val crc = CRC32().apply { update(buffer.array(), 0, buffer.position()) }.value.toInt()
        buffer.putInt(crc)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size >= HEADER_BYTES + 4) { "Radio frame too short" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "Radio frame magic mismatch" }
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported radio protocol version" }
        val kind = Kind.fromWire(input.get().toInt() and 0xff) ?: error("Unknown radio frame kind")
        val length = input.short.toInt() and 0xffff
        require(length <= MAX_RADIO_PAYLOAD && bytes.size == HEADER_BYTES + length + 4) { "Invalid radio frame length" }
        val sequence = input.int
        val payload = ByteArray(length).also(input::get)
        val expected = input.int
        val actual = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        require(expected == actual) { "Radio frame checksum mismatch" }
        return Frame(kind, sequence, payload)
    }
}

object TorXRadioHelloCodec {
    const val CHALLENGE_BYTES = 32
    const val SIGNATURE_BYTES = 64

    fun decodeAndVerify(payload: ByteArray, challenge: ByteArray): TorXRadioCapabilities {
        require(challenge.size == CHALLENGE_BYTES)
        require(payload.size > SIGNATURE_BYTES + 32) { "Radio HELLO response too short" }
        val signed = payload.copyOfRange(0, payload.size - SIGNATURE_BYTES)
        val signature = payload.copyOfRange(payload.size - SIGNATURE_BYTES, payload.size)
        val input = ByteBuffer.wrap(signed).order(ByteOrder.BIG_ENDIAN)
        fun string(max: Int): String {
            require(input.hasRemaining()) { "Truncated radio identity" }
            val length = input.get().toInt() and 0xff
            require(length in 1..max && input.remaining() >= length) { "Invalid radio identity field" }
            return ByteArray(length).also(input::get).decodeToString()
        }
        val deviceId = string(64)
        val firmware = string(32)
        val board = string(64)
        val chip = string(32)
        val region = string(16)
        require(input.remaining() == 34) { "Invalid radio HELLO fields" }
        val maxPayload = input.short.toInt() and 0xffff
        val publicKey = ByteArray(32).also(input::get)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(challenge, 0, challenge.size)
            update(signed, 0, signed.size)
        }
        require(verifier.verifySignature(signature)) { "Radio identity proof failed" }
        return TorXRadioCapabilities(deviceId, firmware, board, chip, region, maxPayload, publicKey)
    }
}

enum class RadioConnectionMethod { BLE, USB }

data class TorXRadioCandidate(
    val stableId: String,
    val displayName: String?,
    val method: RadioConnectionMethod,
    val advertisedService: UUID? = null,
    val nativeHandle: Any? = null
) {
    fun looksLikeTorXRadio(): Boolean = when (method) {
        RadioConnectionMethod.BLE -> advertisedService == TorXRadioProtocol.SERVICE_UUID
        RadioConnectionMethod.USB -> false // USB is accepted only after a protocol HELLO, never by name alone.
    }
}

data class TorXRadioCapabilities(
    val deviceId: String,
    val firmwareVersion: String,
    val board: String,
    val radioChip: String,
    val region: String,
    val maxPayloadBytes: Int,
    val identityKey: ByteArray
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 64)
        require(firmwareVersion.length <= 32 && board.length <= 64 && radioChip.length <= 32)
        require(region.length <= 16)
        require(maxPayloadBytes in 64..TorXRadioProtocol.MAX_RADIO_PAYLOAD)
        require(identityKey.size == 32) { "Radio identity must be an Ed25519 public key" }
    }
}

sealed class RadioConnectionState {
    data object Idle : RadioConnectionState()
    data object Scanning : RadioConnectionState()
    data class Recognized(val candidate: TorXRadioCandidate) : RadioConnectionState()
    data class Connecting(val candidate: TorXRadioCandidate) : RadioConnectionState()
    data class NeedsPairing(val capabilities: TorXRadioCapabilities) : RadioConnectionState()
    data class Ready(val capabilities: TorXRadioCapabilities, val method: RadioConnectionMethod) : RadioConnectionState()
    data class Failed(val reason: String) : RadioConnectionState()
}
