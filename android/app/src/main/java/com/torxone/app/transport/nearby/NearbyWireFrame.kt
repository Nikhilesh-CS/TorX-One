package com.torxone.app.transport.nearby

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

const val MAX_DIRECT_FRAME_SIZE = 64 * 1024 // 64 KB limit for direct BYTES frames
const val NEARBY_PROTOCOL_VERSION = 1

/**
 * Wire framing protocol for Nearby direct transport.
 * Distinguishes between control signaling (HELLO, AUTH_PROOF, AUTH_OK, PING, PONG)
 * and encrypted user payloads (DATA).
 */
sealed class NearbyWireFrame {

    data class Data(val payload: ByteArray) : NearbyWireFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = payload.contentHashCode()
    }

    sealed class Control : NearbyWireFrame() {
        data class Hello(
            val protocolVersion: Int,
            val peerTieBreaker: Long,
            val supportedFeatures: List<String>,
            val maxFrameSize: Int,
            val challenge: ByteArray
        ) : Control() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Hello) return false
                return protocolVersion == other.protocolVersion &&
                        peerTieBreaker == other.peerTieBreaker &&
                        supportedFeatures == other.supportedFeatures &&
                        maxFrameSize == other.maxFrameSize &&
                        challenge.contentEquals(other.challenge)
            }
            override fun hashCode(): Int {
                var result = protocolVersion
                result = 31 * result + peerTieBreaker.hashCode()
                result = 31 * result + supportedFeatures.hashCode()
                result = 31 * result + maxFrameSize
                result = 31 * result + challenge.contentHashCode()
                return result
            }
        }

        data class AuthProof(
            val relationshipId: String,
            val proof: ByteArray,
            val sendQueueId: String,
            val recvQueueId: String
        ) : Control() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is AuthProof) return false
                return relationshipId == other.relationshipId &&
                        proof.contentEquals(other.proof) &&
                        sendQueueId == other.sendQueueId &&
                        recvQueueId == other.recvQueueId
            }
            override fun hashCode(): Int {
                var result = relationshipId.hashCode()
                result = 31 * result + proof.contentHashCode()
                result = 31 * result + sendQueueId.hashCode()
                result = 31 * result + recvQueueId.hashCode()
                return result
            }
        }

        data class AuthOk(val relationshipId: String) : Control()

        data object Ping : Control()
        data object Pong : Control()
    }

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        when (this) {
            is Data -> {
                dos.writeByte(FRAME_DATA.toInt())
                dos.writeInt(payload.size)
                dos.write(payload)
            }
            is Control.Hello -> {
                dos.writeByte(FRAME_CONTROL.toInt())
                dos.writeByte(CTRL_HELLO.toInt())
                dos.writeInt(protocolVersion)
                dos.writeLong(peerTieBreaker)
                dos.writeInt(supportedFeatures.size)
                for (f in supportedFeatures) {
                    dos.writeUTF(f)
                }
                dos.writeInt(maxFrameSize)
                dos.writeInt(challenge.size)
                dos.write(challenge)
            }
            is Control.AuthProof -> {
                dos.writeByte(FRAME_CONTROL.toInt())
                dos.writeByte(CTRL_AUTH_PROOF.toInt())
                dos.writeUTF(relationshipId)
                dos.writeInt(proof.size)
                dos.write(proof)
                dos.writeUTF(sendQueueId)
                dos.writeUTF(recvQueueId)
            }
            is Control.AuthOk -> {
                dos.writeByte(FRAME_CONTROL.toInt())
                dos.writeByte(CTRL_AUTH_OK.toInt())
                dos.writeUTF(relationshipId)
            }
            is Control.Ping -> {
                dos.writeByte(FRAME_CONTROL.toInt())
                dos.writeByte(CTRL_PING.toInt())
            }
            is Control.Pong -> {
                dos.writeByte(FRAME_CONTROL.toInt())
                dos.writeByte(CTRL_PONG.toInt())
            }
        }
        return bos.toByteArray()
    }

    companion object {
        const val FRAME_CONTROL: Byte = 0x01
        const val FRAME_DATA: Byte = 0x02

        const val CTRL_HELLO: Byte = 0x01
        const val CTRL_AUTH_PROOF: Byte = 0x02
        const val CTRL_AUTH_OK: Byte = 0x03
        const val CTRL_PING: Byte = 0x04
        const val CTRL_PONG: Byte = 0x05

        const val MAX_HELLO_FEATURES = 32
        const val MAX_FEATURE_STRING_LEN = 128
        const val MAX_PAYLOAD_SIZE = 1_048_576
        const val MIN_CHALLENGE_SIZE = 16
        const val MAX_CHALLENGE_SIZE = 64
        const val MIN_PROOF_SIZE = 16
        const val MAX_PROOF_SIZE = 64
        const val MAX_STRING_ID_LEN = 256

        fun decode(bytes: ByteArray): NearbyWireFrame {
            require(bytes.isNotEmpty()) { "Empty frame cannot be decoded" }
            require(bytes.size <= MAX_PAYLOAD_SIZE + 1024) { "Frame size ${bytes.size} exceeds maximum allowable" }

            return try {
                val dis = DataInputStream(ByteArrayInputStream(bytes))
                val frameType = dis.readByte()
                val result = when (frameType) {
                    FRAME_DATA -> {
                        val len = dis.readInt()
                        require(len in 0..MAX_PAYLOAD_SIZE) { "Invalid DATA payload length: $len" }
                        val payload = ByteArray(len)
                        dis.readFully(payload)
                        Data(payload)
                    }
                    FRAME_CONTROL -> {
                        val ctrlType = dis.readByte()
                        when (ctrlType) {
                            CTRL_HELLO -> {
                                val version = dis.readInt()
                                val tieBreaker = dis.readLong()
                                val featureCount = dis.readInt()
                                require(featureCount in 0..MAX_HELLO_FEATURES) { "Invalid feature count: $featureCount" }
                                val features = (0 until featureCount).map {
                                    val f = dis.readUTF()
                                    require(f.length <= MAX_FEATURE_STRING_LEN) { "Feature string exceeds max length" }
                                    f
                                }
                                val maxFrameSize = dis.readInt()
                                require(maxFrameSize > 0) { "Invalid maxFrameSize: $maxFrameSize" }
                                val challengeLen = dis.readInt()
                                require(challengeLen in MIN_CHALLENGE_SIZE..MAX_CHALLENGE_SIZE) { "Invalid challenge length: $challengeLen" }
                                val challenge = ByteArray(challengeLen).apply { dis.readFully(this) }
                                Control.Hello(version, tieBreaker, features, maxFrameSize, challenge)
                            }
                            CTRL_AUTH_PROOF -> {
                                val relId = dis.readUTF()
                                require(relId.length <= MAX_STRING_ID_LEN) { "Relationship ID too long" }
                                val proofLen = dis.readInt()
                                require(proofLen in MIN_PROOF_SIZE..MAX_PROOF_SIZE) { "Invalid proof length: $proofLen" }
                                val proof = ByteArray(proofLen).apply { dis.readFully(this) }
                                val sendQueue = dis.readUTF()
                                require(sendQueue.length <= MAX_STRING_ID_LEN) { "Send queue ID too long" }
                                val recvQueue = dis.readUTF()
                                require(recvQueue.length <= MAX_STRING_ID_LEN) { "Recv queue ID too long" }
                                Control.AuthProof(relId, proof, sendQueue, recvQueue)
                            }
                            CTRL_AUTH_OK -> {
                                val relId = dis.readUTF()
                                require(relId.length <= MAX_STRING_ID_LEN) { "Relationship ID too long" }
                                Control.AuthOk(relId)
                            }
                            CTRL_PING -> Control.Ping
                            CTRL_PONG -> Control.Pong
                            else -> Data(bytes)
                        }
                    }
                    else -> Data(bytes)
                }
                if (result !is Data || bytes[0] == FRAME_DATA) {
                    require(dis.available() == 0) { "Trailing unparsed bytes in wire frame" }
                }
                result
            } catch (_: Exception) {
                Data(bytes)
            }
        }
    }
}
