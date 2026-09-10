package com.torxone.app.agent

import android.util.Log
import com.torxone.app.network.MeshProtocol
import com.torxone.app.protocol.HashChainVerificationResult
import com.torxone.app.protocol.ProtocolEnvelope
import com.torxone.app.transport.TransportIncomingListener
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * TorX One 2.0 — Incoming Dispatcher
 *
 * Replaces the duplicated `handleNearbyPayload()` and `handleTorPayload()`
 * from MessageRouter with a single, transport-agnostic entry point.
 *
 * All incoming payloads from any transport flow through here:
 *
 *   NearbyTransport ──┐
 *   TorTransport   ───┤──→ IncomingDispatcher ──→ Handler callbacks
 *   WifiTransport  ───┘
 *
 * The dispatcher:
 * 1. Parses the wire format
 * 2. Deduplicates (via TorXAgent)
 * 3. Routes to the appropriate handler based on message type
 *
 * Handlers are registered by the application layer (chat, calls, groups, etc.)
 */
class IncomingDispatcher(
    private val agent: TorXAgent,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TransportIncomingListener {

    companion object {
        private const val TAG = "IncomingDispatcher"
    }

    // ─── Handler Registrations ───

    /** Handler for decrypted chat messages. */
    var onChatMessage: (suspend (senderKey: String, payload: JSONObject, transportType: TransportType) -> Unit)? = null

    /** Handler for session-encrypted messages (Double Ratchet). */
    var onSessionMessage: (suspend (sourceAddress: String?, payload: JSONObject, transportType: TransportType) -> Unit)? = null

    /** Handler for ACK receipts. */
    var onAckReceived: (suspend (payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for READ receipts. */
    var onReadReceived: (suspend (payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for call signaling. */
    var onCallSignal: (suspend (signalType: String, payload: JSONObject, sourceAddress: String?, transportType: TransportType) -> Unit)? = null

    /** Handler for group protocol messages. */
    var onGroupMessage: (suspend (groupType: String, payload: JSONObject, sourceAddress: String?, transportType: TransportType) -> Unit)? = null

    /** Handler for media transfer frames. */
    var onMediaTransfer: (suspend (mediaType: String, payload: JSONObject, sourceAddress: String?, transportType: TransportType) -> Unit)? = null

    /** Handler for presence updates. */
    var onPresence: (suspend (payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for profile sync. */
    var onProfileSync: (suspend (syncType: String, payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for music sync. */
    var onMusicSync: (suspend (musicType: String, payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for relay messages (mesh forwarding). */
    var onRelayMessage: (suspend (payload: JSONObject, sourceAddress: String?) -> Unit)? = null

    /** Handler for hello/handshake. */
    var onHello: ((endpointId: String, contactString: String) -> Unit)? = null

    /** Handler for ping/pong. */
    var onPing: ((payload: JSONObject, sourceAddress: String?) -> Unit)? = null
    var onPong: ((payload: JSONObject) -> Unit)? = null

    /** Handler for pairwise queue rotation (TorX One 2.0 connection layer). */
    var onQueueRotation: (suspend (payload: JSONObject) -> Unit)? = null

    /** Handler for legacy encrypted messages (pre-ratchet). */
    var onLegacyEncrypted: (suspend (payload: JSONObject, sourceAddress: String?, messageType: String) -> Unit)? = null

    // ─── Transport Incoming Listener ───

    /**
     * Called by TransportRouter when any transport receives a payload.
     * This is the single entry point for ALL incoming data.
     */
    override fun onPayloadReceived(sourceAddress: String?, payload: String, transportType: TransportType) {
        val json = MeshProtocol.parse(payload) ?: run {
            Log.w(TAG, "[INCOMING] Dropped unparseable payload from $sourceAddress via $transportType (len=${payload.length})")
            return
        }

        // Check for TorX One 2.0 ProtocolEnvelope (Version 2)
        if (json.optInt("version", 0) == 2 && json.has("connectionId")) {
            val envelope = try {
                ProtocolEnvelope.fromJson(payload)
            } catch (e: Exception) {
                Log.w(TAG, "[INCOMING] Failed to parse ProtocolEnvelope: ${e.message}")
                null
            }
            if (envelope != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        dispatchProtocolEnvelope(envelope, sourceAddress, transportType)
                    } catch (e: Exception) {
                        Log.e(TAG, "[INCOMING] Error dispatching ProtocolEnvelope: ${e.message}", e)
                    }
                }
                return
            }
        }

        val type = json.optString("type", "")
        Log.d(TAG, "[INCOMING] type=$type from=$sourceAddress via=$transportType")

        // Deduplication check
        val msgId = json.optString("msgId", "")
        if (msgId.isNotBlank() && agent.isAlreadyProcessed(msgId)) {
            Log.d(TAG, "[INCOMING] Duplicate msgId=$msgId, dropping")
            return
        }
        if (msgId.isNotBlank()) {
            agent.markProcessed(msgId)
        }

        // Route based on message type
        scope.launch(Dispatchers.IO) {
            try {
                dispatch(type, json, sourceAddress, transportType)
            } catch (e: Exception) {
                Log.e(TAG, "[INCOMING] Error dispatching type=$type: ${e.message}", e)
            }
        }
    }

    private suspend fun dispatchProtocolEnvelope(
        envelope: ProtocolEnvelope,
        sourceAddress: String?,
        transportType: TransportType
    ) {
        val connManager = agent.connectionManager

        // Deduplication: connectionId:sequenceNumber
        val dedupeKey = "${envelope.connectionId}:${envelope.sequenceNumber}"
        if (agent.isAlreadyProcessed(dedupeKey)) {
            Log.d(TAG, "[INCOMING_ENVELOPE] Duplicate envelope seq=${envelope.sequenceNumber} conn=${envelope.connectionId}, dropping")
            return
        }
        agent.markProcessed(dedupeKey)

        // Queue validation: verify queue is currently accepted (active, pending, or draining)
        val isAccepted = connManager.isQueueAccepted(envelope.connectionId, envelope.queueId)
        if (!isAccepted) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Queue ${envelope.queueId} is not accepted for conn=${envelope.connectionId}")
            return
        }

        // Sequence validation (pre-commit check)
        val seqValidation = connManager.validateRecvSequence(envelope.connectionId, envelope.sequenceNumber)
        when (seqValidation) {
            is com.torxone.app.connection.SequenceValidationResult.Replay,
            is com.torxone.app.connection.SequenceValidationResult.Invalid -> {
                Log.w(TAG, "[INCOMING_ENVELOPE] Sequence ${envelope.sequenceNumber} rejected by validator for conn=${envelope.connectionId}: $seqValidation")
                return
            }
            is com.torxone.app.connection.SequenceValidationResult.ValidWithGap -> {
                Log.w(TAG, "[INCOMING_ENVELOPE] Sequence gap detected: expected=${seqValidation.expected}, received=${seqValidation.received}")
            }
            is com.torxone.app.connection.SequenceValidationResult.Valid -> {
                Log.d(TAG, "[INCOMING_ENVELOPE] Sequence ${envelope.sequenceNumber} valid")
            }
        }

        // Validate hash chain continuity
        val conn = connManager.getConnectionById(envelope.connectionId)
        val continuity = ProtocolEnvelope.verifyContinuity(conn?.lastCommittedHash, envelope)
        when (continuity) {
            is HashChainVerificationResult.Continuous -> {
                Log.d(TAG, "[INCOMING_ENVELOPE] Hash chain continuous for conn=${envelope.connectionId} seq=${envelope.sequenceNumber}")
            }
            is HashChainVerificationResult.GapDetected -> {
                Log.w(TAG, "[INCOMING_ENVELOPE] Non-fatal hash chain gap for conn=${envelope.connectionId} seq=${envelope.sequenceNumber}: ${continuity.reason} (expected=${continuity.expectedPrevHash}, actual=${continuity.actualPrevHash})")
            }
        }

        // Compute current envelope hash for hash chain advancement
        val computedHash = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = envelope.previousHash,
            connectionId = envelope.connectionId,
            queueId = envelope.queueId,
            sequenceNumber = envelope.sequenceNumber,
            messageType = envelope.messageType,
            ciphertext = envelope.ciphertext
        )

        // Commit sequence cursor advancement after validation
        connManager.commitRecvSequence(envelope.connectionId, envelope.sequenceNumber, computedHash)

        // Dispatch according to envelope type
        when (envelope.messageType) {
            ProtocolEnvelope.TYPE_MESSAGE -> {
                val sessionJson = JSONObject().apply {
                    put("type", MeshProtocol.TYPE_SESSION_MSG)
                    put("ciphertext", envelope.ciphertext)
                    envelope.cryptoMetadata?.let {
                        put("sessionId", it.sessionId)
                        put("msgNum", it.msgNum)
                        put("ratchetPub", it.ratchetPub)
                        it.iv?.let { iv -> put("iv", iv) }
                        it.signature?.let { sig -> put("signature", sig) }
                    }
                }
                onSessionMessage?.invoke(sourceAddress, sessionJson, transportType)
            }
            ProtocolEnvelope.TYPE_ACK -> {
                val json = try { JSONObject(envelope.ciphertext) } catch (_: Exception) { JSONObject() }
                val ackMsgId = json.optString("msgId", "")
                if (ackMsgId.isNotBlank()) {
                    agent.handleAck(ackMsgId)
                }
                onAckReceived?.invoke(json, sourceAddress)
            }
            ProtocolEnvelope.TYPE_READ -> {
                val json = try { JSONObject(envelope.ciphertext) } catch (_: Exception) { JSONObject() }
                val readMsgId = json.optString("msgId", "")
                if (readMsgId.isNotBlank()) {
                    agent.handleRead(readMsgId)
                }
                onReadReceived?.invoke(json, sourceAddress)
            }
            ProtocolEnvelope.TYPE_CALL_OFFER,
            ProtocolEnvelope.TYPE_CALL_ANSWER,
            ProtocolEnvelope.TYPE_ICE_CANDIDATE,
            ProtocolEnvelope.TYPE_CALL_ACK,
            ProtocolEnvelope.TYPE_CALL_END -> {
                val json = try { JSONObject(envelope.ciphertext) } catch (_: Exception) { JSONObject() }
                onCallSignal?.invoke(envelope.messageType, json, sourceAddress, transportType)
            }
            ProtocolEnvelope.TYPE_QUEUE_ROTATE_PROPOSE,
            ProtocolEnvelope.TYPE_QUEUE_ROTATE_ACK -> {
                val json = try { JSONObject(envelope.ciphertext) } catch (_: Exception) { JSONObject() }
                onQueueRotation?.invoke(json)
            }
            else -> {
                Log.d(TAG, "[INCOMING_ENVELOPE] Dispatched envelope type=${envelope.messageType}")
            }
        }
    }

    private suspend fun dispatch(
        type: String,
        json: JSONObject,
        sourceAddress: String?,
        transportType: TransportType
    ) {
        when (type) {
            // Hello / handshake
            MeshProtocol.TYPE_HELLO -> {
                val contact = json.optString("contact", "")
                if (sourceAddress != null && contact.isNotBlank()) {
                    onHello?.invoke(sourceAddress, contact)
                }
            }

            // Session-encrypted (Double Ratchet) messages
            MeshProtocol.TYPE_SESSION_MSG -> {
                onSessionMessage?.invoke(sourceAddress, json, transportType)
            }

            // Legacy encrypted messages (pre-ratchet NaCl box)
            MeshProtocol.TYPE_MSG -> {
                onLegacyEncrypted?.invoke(json, sourceAddress, type)
            }

            // Delivery receipts
            MeshProtocol.TYPE_ACK -> {
                onAckReceived?.invoke(json, sourceAddress)
            }
            MeshProtocol.TYPE_READ -> {
                onReadReceived?.invoke(json, sourceAddress)
            }

            // Call signaling — now flows through the same dispatcher
            MeshProtocol.TYPE_CALL_OFFER,
            MeshProtocol.TYPE_CALL_ANSWER,
            MeshProtocol.TYPE_ICE_CANDIDATE,
            MeshProtocol.TYPE_CALL_ACK,
            MeshProtocol.TYPE_CALL_END -> {
                onCallSignal?.invoke(type, json, sourceAddress, transportType)
            }

            // Group protocol
            MeshProtocol.TYPE_GROUP_INVITE,
            MeshProtocol.TYPE_GROUP_JOIN,
            MeshProtocol.TYPE_GROUP_UPDATE,
            MeshProtocol.TYPE_GROUP_LEAVE,
            MeshProtocol.TYPE_GROUP_KEY,
            MeshProtocol.TYPE_GROUP_SYNC_REQUEST,
            MeshProtocol.TYPE_GROUP_SYNC_RESPONSE,
            MeshProtocol.TYPE_GROUP_KEY_REQUEST,
            MeshProtocol.TYPE_GROUP_JOIN_REQUEST,
            MeshProtocol.TYPE_GROUP_INVITE_LINK,
            MeshProtocol.TYPE_GROUP_MESSAGE -> {
                onGroupMessage?.invoke(type, json, sourceAddress, transportType)
            }

            // Media transfer
            MeshProtocol.TYPE_MEDIA_OFFER,
            MeshProtocol.TYPE_MEDIA_CHUNK,
            MeshProtocol.TYPE_MEDIA_ACK,
            MeshProtocol.TYPE_MEDIA_COMPLETE -> {
                onMediaTransfer?.invoke(type, json, sourceAddress, transportType)
            }

            // Presence
            MeshProtocol.TYPE_PRESENCE -> {
                onPresence?.invoke(json, sourceAddress)
            }

            // Profile sync
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK -> {
                onProfileSync?.invoke(type, json, sourceAddress)
            }

            // Music sync
            MeshProtocol.TYPE_MUSIC_NOTE,
            MeshProtocol.TYPE_MUSIC_SYNC -> {
                onMusicSync?.invoke(type, json, sourceAddress)
            }

            // Relay (mesh forwarding)
            MeshProtocol.TYPE_RELAY -> {
                onRelayMessage?.invoke(json, sourceAddress)
            }

            // Ping/Pong
            MeshProtocol.TYPE_PING -> {
                onPing?.invoke(json, sourceAddress)
            }
            MeshProtocol.TYPE_PONG -> {
                onPong?.invoke(json)
            }

            // Catch-all for encrypted types
            MeshProtocol.TYPE_REACTION,
            MeshProtocol.TYPE_POLL_VOTE -> {
                onLegacyEncrypted?.invoke(json, sourceAddress, type)
            }

            // TorX One 2.0 Pairwise queue rotation
            "queue_rotate" -> {
                onQueueRotation?.invoke(json)
            }

            else -> {
                Log.w(TAG, "[INCOMING] Unknown type=$type, attempting legacy handler")
                onLegacyEncrypted?.invoke(json, sourceAddress, type)
            }
        }
    }
}
