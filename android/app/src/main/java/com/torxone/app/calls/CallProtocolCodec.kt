package com.torxone.app.calls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Deterministic canonical binary codec for call signaling payloads.
 * Follows the same pattern as GroupProtocolCodec — magic headers,
 * DataOutputStream/DataInputStream, no JSON.
 *
 * Payload types map 1:1 to MessageType.CALL_* values:
 *   CALL_OFFER         → CallOfferPayload
 *   CALL_RINGING       → CallRingingPayload
 *   CALL_ANSWER        → CallAnswerPayload
 *   CALL_ICE_CANDIDATE → IceCandidatePayload
 *   CALL_CONNECTED     → CallConnectedPayload
 *   CALL_END           → CallEndPayload
 *   CALL_DECLINE       → CallDeclinePayload
 *   CALL_BUSY          → CallBusyPayload
 */
object CallProtocolCodec {

    // Magic headers — "TXC" prefix + payload type initial
    private const val OFFER_MAGIC    = 0x5458434F // "TXCO"
    private const val RINGING_MAGIC  = 0x54584352 // "TXCR"
    private const val ANSWER_MAGIC   = 0x54584341 // "TXCA"
    private const val ICE_MAGIC      = 0x54584349 // "TXCI"
    private const val CONNECTED_MAGIC = 0x54584343 // "TXCC"
    private const val END_MAGIC      = 0x54584345 // "TXCE"
    private const val DECLINE_MAGIC  = 0x54584344 // "TXCD"
    private const val BUSY_MAGIC     = 0x54584342 // "TXCB"

    // ─── Call Offer ──────────────────────────────────────────────────────

    fun encodeOffer(payload: CallOfferPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(OFFER_MAGIC)
        dos.writeUTF(payload.callId)
        dos.writeUTF(payload.callType.name)
        dos.writeUTF(payload.sdpOffer)
        dos.writeLong(payload.createdAt)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeOffer(bytes: ByteArray): CallOfferPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == OFFER_MAGIC) { "Invalid CallOffer magic header" }
        return CallOfferPayload(
            callId = dis.readUTF(),
            callType = CallType.valueOf(dis.readUTF()),
            sdpOffer = dis.readUTF(),
            createdAt = dis.readLong()
        )
    }

    // ─── Call Ringing ────────────────────────────────────────────────────

    fun encodeRinging(payload: CallRingingPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(RINGING_MAGIC)
        dos.writeUTF(payload.callId)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeRinging(bytes: ByteArray): CallRingingPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == RINGING_MAGIC) { "Invalid CallRinging magic header" }
        return CallRingingPayload(callId = dis.readUTF())
    }

    // ─── Call Answer ─────────────────────────────────────────────────────

    fun encodeAnswer(payload: CallAnswerPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(ANSWER_MAGIC)
        dos.writeUTF(payload.callId)
        dos.writeUTF(payload.sdpAnswer)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeAnswer(bytes: ByteArray): CallAnswerPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == ANSWER_MAGIC) { "Invalid CallAnswer magic header" }
        return CallAnswerPayload(
            callId = dis.readUTF(),
            sdpAnswer = dis.readUTF()
        )
    }

    // ─── ICE Candidate ──────────────────────────────────────────────────

    fun encodeIceCandidate(payload: IceCandidatePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(ICE_MAGIC)
        dos.writeUTF(payload.callId)
        dos.writeUTF(payload.sdpMid ?: "")
        dos.writeInt(payload.sdpMLineIndex)
        dos.writeUTF(payload.candidate)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeIceCandidate(bytes: ByteArray): IceCandidatePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == ICE_MAGIC) { "Invalid IceCandidate magic header" }
        val callId = dis.readUTF()
        val sdpMidRaw = dis.readUTF()
        val sdpMid = if (sdpMidRaw.isEmpty()) null else sdpMidRaw
        return IceCandidatePayload(
            callId = callId,
            sdpMid = sdpMid,
            sdpMLineIndex = dis.readInt(),
            candidate = dis.readUTF()
        )
    }

    // ─── Call Connected ──────────────────────────────────────────────────

    fun encodeConnected(payload: CallConnectedPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CONNECTED_MAGIC)
        dos.writeUTF(payload.callId)
        dos.writeLong(payload.connectedAt)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeConnected(bytes: ByteArray): CallConnectedPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == CONNECTED_MAGIC) { "Invalid CallConnected magic header" }
        return CallConnectedPayload(
            callId = dis.readUTF(),
            connectedAt = dis.readLong()
        )
    }

    // ─── Call End ────────────────────────────────────────────────────────

    fun encodeEnd(payload: CallEndPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(END_MAGIC)
        dos.writeUTF(payload.callId)
        dos.writeUTF(payload.reason.name)
        dos.writeLong(payload.durationMs)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeEnd(bytes: ByteArray): CallEndPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == END_MAGIC) { "Invalid CallEnd magic header" }
        return CallEndPayload(
            callId = dis.readUTF(),
            reason = CallEndReason.valueOf(dis.readUTF()),
            durationMs = dis.readLong()
        )
    }

    // ─── Call Decline ────────────────────────────────────────────────────

    fun encodeDecline(payload: CallDeclinePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(DECLINE_MAGIC)
        dos.writeUTF(payload.callId)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeDecline(bytes: ByteArray): CallDeclinePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == DECLINE_MAGIC) { "Invalid CallDecline magic header" }
        return CallDeclinePayload(callId = dis.readUTF())
    }

    // ─── Call Busy ───────────────────────────────────────────────────────

    fun encodeBusy(payload: CallBusyPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(BUSY_MAGIC)
        dos.writeUTF(payload.callId)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeBusy(bytes: ByteArray): CallBusyPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == BUSY_MAGIC) { "Invalid CallBusy magic header" }
        return CallBusyPayload(callId = dis.readUTF())
    }
}

// ─── Payload Data Classes ────────────────────────────────────────────────

data class CallOfferPayload(
    val callId: String,
    val callType: CallType,
    val sdpOffer: String,
    val createdAt: Long
)

data class CallRingingPayload(
    val callId: String
)

data class CallAnswerPayload(
    val callId: String,
    val sdpAnswer: String
)

data class IceCandidatePayload(
    val callId: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)

data class CallConnectedPayload(
    val callId: String,
    val connectedAt: Long
)

data class CallEndPayload(
    val callId: String,
    val reason: CallEndReason,
    val durationMs: Long
)

data class CallDeclinePayload(
    val callId: String
)

data class CallBusyPayload(
    val callId: String
)
