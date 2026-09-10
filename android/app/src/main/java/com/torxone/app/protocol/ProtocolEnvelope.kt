package com.torxone.app.protocol

import org.json.JSONObject
import java.security.MessageDigest

/**
 * TorX One 2.0 — Protocol Layer: Protocol Envelope
 *
 * Dedicated protocol layer positioned between Delivery and Crypto:
 * Application -> Connection -> Delivery -> Protocol Layer -> Crypto -> Transport
 *
 * Implements the standard Version 2 wire format and the genuine
 * cryptographic message hash chain:
 * H_N = SHA-256(H_{N-1} || connectionId || queueId || sequenceNumber || messageType || ciphertext)
 *
 * The hash chain provides an integrity and order signal.
 * Gaps or out-of-order deliveries are signaled to the delivery engine
 * rather than acting as a fatal session-destroying error.
 */
data class ProtocolEnvelope(
    val version: Int = PROTOCOL_VERSION,
    val connectionId: String,
    val queueId: String,
    val sequenceNumber: Long,
    val previousHash: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String,
    val ciphertext: String,
    val cryptoMetadata: CryptoMetadata? = null
) {
    companion object {
        const val PROTOCOL_VERSION = 2

        // Standard Protocol Discriminators
        const val TYPE_MESSAGE = "MESSAGE"
        const val TYPE_ACK = "ACK"
        const val TYPE_READ = "READ"
        const val TYPE_REACTION = "REACTION"
        const val TYPE_CALL_OFFER = "CALL_OFFER"
        const val TYPE_CALL_ANSWER = "CALL_ANSWER"
        const val TYPE_ICE_CANDIDATE = "ICE_CANDIDATE"
        const val TYPE_CALL_ACK = "CALL_ACK"
        const val TYPE_CALL_END = "CALL_END"
        const val TYPE_GROUP_EVENT = "GROUP_EVENT"
        const val TYPE_MEDIA_CONTROL = "MEDIA_CONTROL"
        const val TYPE_QUEUE_ROTATE_PROPOSE = "QUEUE_ROTATE_PROPOSE"
        const val TYPE_QUEUE_ROTATE_ACK = "QUEUE_ROTATE_ACK"

        /**
         * Compute the SHA-256 hash for an envelope in the hash chain:
         * H_N = SHA-256(H_{N-1} || connectionId || queueId || sequenceNumber || messageType || ciphertext)
         */
        fun computeEnvelopeHash(
            previousHash: String?,
            connectionId: String,
            queueId: String,
            sequenceNumber: Long,
            messageType: String,
            ciphertext: String
        ): String {
            val md = MessageDigest.getInstance("SHA-256")
            val payload = buildString {
                append(previousHash ?: "GENESIS")
                append("|")
                append(connectionId)
                append("|")
                append(queueId)
                append("|")
                append(sequenceNumber)
                append("|")
                append(messageType)
                append("|")
                append(ciphertext)
            }
            return md.digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Verify hash chain continuity as an integrity/order signal.
         * Non-fatal: returns HashChainVerificationResult so the delivery engine
         * can buffer or report gaps without breaking the cryptographic session.
         */
        fun verifyContinuity(
            lastCommittedHash: String?,
            envelope: ProtocolEnvelope
        ): HashChainVerificationResult {
            if (envelope.sequenceNumber <= 1L) {
                return if (envelope.previousHash == null) {
                    HashChainVerificationResult.Continuous
                } else {
                    HashChainVerificationResult.GapDetected(
                        expectedPrevHash = null,
                        actualPrevHash = envelope.previousHash,
                        reason = "Sequence 1 expected null previousHash"
                    )
                }
            }

            return if (lastCommittedHash == envelope.previousHash) {
                HashChainVerificationResult.Continuous
            } else {
                HashChainVerificationResult.GapDetected(
                    expectedPrevHash = lastCommittedHash,
                    actualPrevHash = envelope.previousHash,
                    reason = "Out-of-order delivery or gap in hash chain"
                )
            }
        }

        /**
         * Serialize envelope to JSON wire string.
         */
        fun toJson(envelope: ProtocolEnvelope): String {
            return JSONObject().apply {
                put("version", envelope.version)
                put("connectionId", envelope.connectionId)
                put("queueId", envelope.queueId)
                put("sequenceNumber", envelope.sequenceNumber)
                envelope.previousHash?.let { put("previousHash", it) }
                put("timestamp", envelope.timestamp)
                put("messageType", envelope.messageType)
                put("ciphertext", envelope.ciphertext)
                envelope.cryptoMetadata?.let { crypto ->
                    put("crypto", JSONObject().apply {
                        put("sessionId", crypto.sessionId)
                        put("msgNum", crypto.msgNum)
                        put("ratchetPub", crypto.ratchetPub)
                        crypto.iv?.let { put("iv", it) }
                        crypto.signature?.let { put("signature", it) }
                    })
                }
            }.toString()
        }

        /**
         * Parse envelope from JSON wire string.
         */
        fun fromJson(jsonStr: String): ProtocolEnvelope {
            val json = JSONObject(jsonStr)
            val cryptoObj = json.optJSONObject("crypto")
            val cryptoMeta = cryptoObj?.let {
                CryptoMetadata(
                    sessionId = it.optString("sessionId", ""),
                    msgNum = it.optInt("msgNum", 0),
                    ratchetPub = it.optString("ratchetPub", ""),
                    iv = if (it.has("iv")) it.optString("iv") else null,
                    signature = if (it.has("signature")) it.optString("signature") else null
                )
            }

            return ProtocolEnvelope(
                version = json.optInt("version", PROTOCOL_VERSION),
                connectionId = json.getString("connectionId"),
                queueId = json.getString("queueId"),
                sequenceNumber = json.getLong("sequenceNumber"),
                previousHash = if (json.has("previousHash")) json.optString("previousHash") else null,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                messageType = json.getString("messageType"),
                ciphertext = json.getString("ciphertext"),
                cryptoMetadata = cryptoMeta
            )
        }
    }
}

/**
 * Cryptographic ratchet header and signature metadata.
 */
data class CryptoMetadata(
    val sessionId: String,
    val msgNum: Int,
    val ratchetPub: String,
    val iv: String? = null,
    val signature: String? = null
)

/**
 * Result of hash chain continuity verification.
 */
sealed class HashChainVerificationResult {
    object Continuous : HashChainVerificationResult()
    data class GapDetected(
        val expectedPrevHash: String?,
        val actualPrevHash: String?,
        val reason: String
    ) : HashChainVerificationResult()
}
