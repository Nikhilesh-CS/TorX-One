package com.torxone.app.agent

import android.util.Log
import com.torxone.app.network.MeshProtocol
import com.torxone.app.protocol.HashChainVerificationResult
import com.torxone.app.protocol.ProtocolEnvelope
import com.torxone.app.transport.DurableTransportIncomingListener
import com.torxone.app.transport.TransportIncomingListener
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Buffered envelope stored during out-of-order sequence arrivals.
 */
data class BufferedEnvelope(
    val envelope: ProtocolEnvelope,
    val sourceAddress: String?,
    val transportType: TransportType,
    val receivedAt: Long = System.currentTimeMillis()
)

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
) : DurableTransportIncomingListener {

    companion object {
        private const val TAG = "IncomingDispatcher"
    }

    /** Out-of-order sequence gap buffer per connection. Key: connectionId -> (sequenceNumber -> BufferedEnvelope) */
    val sequenceGapBuffers = ConcurrentHashMap<String, ConcurrentSkipListMap<Long, BufferedEnvelope>>()

    // ─── Handler Registrations ───

    /** Handler for decrypted chat messages. */
    var onChatMessage: (suspend (senderKey: String, payload: JSONObject, transportType: TransportType) -> Unit)? = null

    /** Handler for session-encrypted messages (Double Ratchet). Returns true if decrypted & persisted. */
    var onSessionMessage: (suspend (sourceAddress: String?, payload: JSONObject, transportType: TransportType) -> Boolean)? = null

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
        scope.launch(Dispatchers.IO) {
            processIncomingPayload(sourceAddress, payload, transportType)
        }
    }

    override suspend fun onPayloadReceivedDurably(
        sourceAddress: String?,
        payload: String,
        transportType: TransportType
    ): Boolean = processIncomingPayload(sourceAddress, payload, transportType)

    private suspend fun processIncomingPayload(
        sourceAddress: String?,
        payload: String,
        transportType: TransportType
    ): Boolean {
        val json = MeshProtocol.parse(payload) ?: run {
            Log.w(TAG, "[INCOMING] Dropped unparseable payload from $sourceAddress via $transportType (len=${payload.length})")
            return false
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
                return try {
                    dispatchProtocolEnvelope(envelope, sourceAddress, transportType)
                } catch (e: Exception) {
                    Log.e(TAG, "[INCOMING] Error dispatching ProtocolEnvelope: ${e.message}", e)
                    false
                }
            }
        }

        val type = json.optString("type", "")
        Log.d(TAG, "[INCOMING] type=$type from=$sourceAddress via=$transportType")

        // The Nearby hello is the only pre-envelope bootstrap packet. Every
        // state-changing packet must arrive inside a signed v2 envelope; do not
        // retain a second unauthenticated/legacy receive wire alongside v2.
        if (type != MeshProtocol.TYPE_HELLO) {
            Log.w(TAG, "[INCOMING] Rejected non-envelope packet type=$type")
            return false
        }
        return try {
            dispatch(type, json, sourceAddress, transportType)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[INCOMING] Error dispatching bootstrap type=$type: ${e.message}", e)
            false
        }
    }

    internal suspend fun dispatchProtocolEnvelope(
        envelope: ProtocolEnvelope,
        sourceAddress: String?,
        transportType: TransportType
    ): Boolean {
        val connManager = agent.connectionManager

        // Signature, recipient, contact and authenticated bootstrap validation.
        val conn = agent.authenticateEnvelope(envelope) ?: run {
            Log.w(TAG, "[INCOMING_ENVELOPE] Authentication failed for ${envelope.envelopeId}")
            return false
        }

        // Queue validation: verify queue is currently accepted (active, pending, or draining)
        val isAccepted = connManager.isQueueAccepted(envelope.connectionId, envelope.queueId)
        if (!isAccepted) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Queue ${envelope.queueId} is not accepted for conn=${envelope.connectionId}")
            return false
        }

        // Sequence validation (pre-commit check against conn.lastRecvSeq)
        if (envelope.sequenceNumber <= conn.lastRecvSeq) {
            Log.d(TAG, "[INCOMING_ENVELOPE] Replay/duplicate sequence ${envelope.sequenceNumber} <= lastRecvSeq=${conn.lastRecvSeq} on conn=${envelope.connectionId}")
            return true
        }

        if (envelope.sequenceNumber > conn.lastRecvSeq + 1) {
            // Sequence gap detected: buffer until missing sequence arrives
            Log.w(TAG, "[INCOMING_ENVELOPE] Sequence gap detected on conn=${envelope.connectionId}: expected=${conn.lastRecvSeq + 1}, received=${envelope.sequenceNumber}. Buffering in sequenceGapBuffer.")
            val buffer = sequenceGapBuffers.computeIfAbsent(envelope.connectionId) { ConcurrentSkipListMap() }
            if (buffer.size < 200) {
                buffer[envelope.sequenceNumber] = BufferedEnvelope(envelope, sourceAddress, transportType)
            } else {
                Log.w(TAG, "[INCOMING_ENVELOPE] Gap buffer full (200) for conn=${envelope.connectionId}, dropping envelope seq=${envelope.sequenceNumber}")
            }
            return false
        }

        // Exactly expected next sequence: envelope.sequenceNumber == conn.lastRecvSeq + 1
        return processAndDrainSequence(envelope, sourceAddress, transportType)
    }

    private suspend fun processAndDrainSequence(
        initialEnvelope: ProtocolEnvelope,
        initialSourceAddress: String?,
        initialTransportType: TransportType
    ): Boolean {
        var currentEnvelope: ProtocolEnvelope? = initialEnvelope
        var currentSource: String? = initialSourceAddress
        var currentTransport: TransportType = initialTransportType

        while (currentEnvelope != null) {
            val success = processSingleEnvelope(currentEnvelope, currentSource, currentTransport)
            if (!success) {
                Log.w(TAG, "[INCOMING_ENVELOPE] Processing failed for seq=${currentEnvelope.sequenceNumber} on conn=${currentEnvelope.connectionId}. Cursor NOT advanced.")
                return false
            }

            // After successfully processing and committing currentEnvelope, check gap buffer for next consecutive sequence
            val connId = currentEnvelope.connectionId
            val nextExpectedSeq = currentEnvelope.sequenceNumber + 1
            val buffer = sequenceGapBuffers[connId]
            val nextBuffered = buffer?.remove(nextExpectedSeq)
            if (nextBuffered != null) {
                Log.i(TAG, "[GAP_BUFFER] Draining buffered envelope seq=$nextExpectedSeq for conn=$connId")
                currentEnvelope = nextBuffered.envelope
                currentSource = nextBuffered.sourceAddress
                currentTransport = nextBuffered.transportType
            } else {
                currentEnvelope = null
            }
        }
        return true
    }

    private suspend fun processSingleEnvelope(
        envelope: ProtocolEnvelope,
        sourceAddress: String?,
        transportType: TransportType
    ): Boolean {
        val connManager = agent.connectionManager
        val conn = connManager.getConnectionById(envelope.connectionId) ?: return false

        // Validate hash chain continuity
        val continuity = ProtocolEnvelope.verifyContinuity(conn.lastCommittedHash, envelope)
        when (continuity) {
            is HashChainVerificationResult.Continuous -> {
                Log.d(TAG, "[INCOMING_ENVELOPE] Hash chain continuous for conn=${envelope.connectionId} seq=${envelope.sequenceNumber}")
            }
            is HashChainVerificationResult.GapDetected -> {
                Log.e(TAG, "[INCOMING_ENVELOPE] Rejecting broken hash chain for conn=${envelope.connectionId} seq=${envelope.sequenceNumber}: ${continuity.reason} (expected=${continuity.expectedPrevHash}, actual=${continuity.actualPrevHash})")
                return false
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

        // Attempt application dispatch FIRST
        val dispatchOk = dispatchEnvelopePayload(envelope, conn, sourceAddress, transportType)
        if (!dispatchOk) {
            Log.e(TAG, "[INCOMING_ENVELOPE] Application dispatch failed for seq=${envelope.sequenceNumber} conn=${envelope.connectionId}. Cursor NOT committed.")
            return false
        }

        // ONLY AFTER successful decryption and persistence:
        // 1. Commit receive sequence and envelope hash in Room DB
        connManager.commitRecvSequence(envelope.connectionId, envelope.sequenceNumber, computedHash)

        // 2. Mark deduplication cache
        val dedupeKey = "${envelope.connectionId}:${envelope.sequenceNumber}"
        agent.markProcessed(dedupeKey)

        Log.i(TAG, "[INCOMING_ENVELOPE] Successfully committed recvSeq=${envelope.sequenceNumber} (hash=$computedHash) for conn=${envelope.connectionId}")
        return true
    }

    private suspend fun dispatchEnvelopePayload(
        envelope: ProtocolEnvelope,
        conn: com.torxone.app.agent.ConnectionQueueEntity,
        sourceAddress: String?,
        transportType: TransportType
    ): Boolean {
        val outerType = EnvelopeType.fromWireTypeOrNull(envelope.messageType) ?: run {
            Log.w(TAG, "[INCOMING_ENVELOPE] Unsupported outer type=${envelope.messageType}")
            return false
        }
        val json = try {
            JSONObject(envelope.ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Ciphertext field is not a valid inner wire frame", e)
            return false
        }
        val innerType = json.optString("type", "")
        val innerFrom = json.optString("from", "").trim().lowercase()
        val innerTo = json.optString("to", "").trim().lowercase()
        if (innerFrom.isNotBlank() && innerFrom != conn.remotePartyKey) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Inner sender does not match authenticated connection")
            return false
        }
        if (innerTo.isNotBlank() && innerTo != conn.localPartyKey) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Inner recipient does not match authenticated connection")
            return false
        }
        if (innerType == MeshProtocol.TYPE_SESSION_MSG) {
            return onSessionMessage?.invoke(sourceAddress, json, transportType) ?: false
        }

        if (outerType == EnvelopeType.QUEUE_ROTATE_PROPOSE || outerType == EnvelopeType.QUEUE_ROTATE_ACK) {
            onQueueRotation?.invoke(json) ?: return false
            return true
        }

        val expectedInnerType = EnvelopeType.toWireType(outerType)
        if (innerType != expectedInnerType) {
            Log.w(TAG, "[INCOMING_ENVELOPE] Type mismatch outer=$expectedInnerType inner=$innerType")
            return false
        }
        dispatch(innerType, json, sourceAddress, transportType)
        return true
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
            "queue_rotate", "QUEUE_ROTATE_PROPOSE", "ROTATE_PROPOSE", "QUEUE_ROTATE_ACK", "ROTATE_ACK" -> {
                onQueueRotation?.invoke(json)
            }

            else -> {
                Log.w(TAG, "[INCOMING] Rejected unsupported type=$type")
            }
        }
    }
}
