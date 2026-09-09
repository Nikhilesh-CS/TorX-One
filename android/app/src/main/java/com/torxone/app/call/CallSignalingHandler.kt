package com.torxone.app.call

import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.CallSignalingOutboxEntity
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CallSignal(
    val callId: String,
    val signalId: String = UUID.randomUUID().toString(),
    val generation: Long = 0L,
    val seq: Int = 0,
    val ackSignalId: String? = null,
    val ackType: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: CallMode = CallMode.AUDIO,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val reason: String? = null
)

class CallSignalingHandler(
    private val messageRouter: MessageRouter,
    private val db: AppDatabase? = null,
    scopeOverride: CoroutineScope? = null,
    ioDispatcherOverride: CoroutineDispatcher? = null
) {
    var activeSession: CallTransportSession? = null
    private val ioDispatcher: CoroutineDispatcher = ioDispatcherOverride
        ?: (scopeOverride?.coroutineContext?.get(kotlin.coroutines.ContinuationInterceptor) as? CoroutineDispatcher)
        ?: Dispatchers.IO
    private val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + ioDispatcher)
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localSequence = AtomicInteger(0)

    data class TransientIceSignal(
        val signalId: String,
        val peerKey: String,
        val callId: String,
        val rawPayload: String,
        val generation: Long,
        val seq: Int,
        val timestamp: Long = System.currentTimeMillis(),
        var retryCount: Int = 0,
        var nextRetryAt: Long = 0L
    )

    private val pendingIceOutbox = ConcurrentHashMap<String, TransientIceSignal>()

    companion object {
        private const val TAG = "CallSignalingHandler"
        /** Maximum raw signal payload size (bytes). Prevents memory exhaustion from malicious signals. */
        const val MAX_SIGNAL_SIZE = 65_536       // 64 KB
        /** Maximum SDP content size (bytes). */
        const val MAX_SDP_SIZE = 65_536           // 64 KB
        /** Maximum ICE candidate string size (bytes). */
        const val MAX_ICE_CANDIDATE_SIZE = 4_096  // 4 KB
        private const val MAX_CRITICAL_RETRIES = 6
        private const val MAX_ICE_RETRIES = 4
        private const val BASE_RETRY_INTERVAL_MS = 600L
    }

    init {
        startRetryLoop()
    }

    fun nextSeq(): Int = localSequence.incrementAndGet()

    fun resetSequence() {
        localSequence.set(0)
    }

    private fun startRetryLoop() {
        retryScope.launch {
            while (isActive) {
                delay(400L)
                runCatching {
                    val now = System.currentTimeMillis()
                    // 1. Retry critical signals persisted in Room (OFFER, ANSWER, END)
                    db?.let { appDb ->
                        val pending = appDb.callSignalingOutboxDao().getPendingSignals(now, limit = 20)
                        for (sig in pending) {
                            if (sig.retryCount >= MAX_CRITICAL_RETRIES) {
                                Log.w(TAG, "[CALL_SIG_RETRY] Max retries reached for signalId=${sig.signalId} type=${sig.signalType}, removing")
                                appDb.callSignalingOutboxDao().deleteSignal(sig.signalId)
                                continue
                            }
                            val backoff = (BASE_RETRY_INTERVAL_MS * (1 shl sig.retryCount.coerceAtMost(3))).coerceAtMost(3000L)
                            appDb.callSignalingOutboxDao().updateRetry(sig.signalId, now + backoff)
                            Log.d(TAG, "[CALL_SIG_RETRY] Retrying signalId=${sig.signalId} type=${sig.signalType} retry=${sig.retryCount + 1}")
                            sendRawCallSignal(sig.peerKey, sig.rawPayload, sig.messageType)
                        }
                    }

                    // 2. Retry transient ICE signals in memory
                    for ((sigId, iceSig) in pendingIceOutbox) {
                        if (iceSig.nextRetryAt <= now) {
                            if (iceSig.retryCount >= MAX_ICE_RETRIES) {
                                Log.d(TAG, "[CALL_SIG_RETRY] Expired unacked ICE signalId=$sigId")
                                pendingIceOutbox.remove(sigId)
                                continue
                            }
                            iceSig.retryCount++
                            val backoff = (BASE_RETRY_INTERVAL_MS * (1 shl iceSig.retryCount.coerceAtMost(2))).coerceAtMost(2000L)
                            iceSig.nextRetryAt = now + backoff
                            Log.d(TAG, "[CALL_SIG_RETRY] Retrying ICE signalId=$sigId retry=${iceSig.retryCount}")
                            sendRawCallSignal(iceSig.peerKey, iceSig.rawPayload, MeshProtocol.TYPE_ICE_CANDIDATE)
                        }
                    }
                }
            }
        }
    }

    suspend fun sendOffer(
        peerKey: String,
        callId: String,
        mode: CallMode,
        description: AstraSessionDescription,
        generation: Long = 0L
    ): String {
        val signalId = UUID.randomUUID().toString()
        val seq = nextSeq()
        Log.d(TAG, "[CALL_SIG] Sent OFFER callId=$callId sigId=$signalId gen=$generation seq=$seq to=${peerKey.take(12)} mode=${mode.name}")
        sendSdp(peerKey, callId, mode, description, MeshProtocol.TYPE_CALL_OFFER, signalId, generation, seq)
        return signalId
    }

    suspend fun sendAnswer(
        peerKey: String,
        callId: String,
        mode: CallMode,
        description: AstraSessionDescription,
        generation: Long = 0L
    ): String {
        val signalId = UUID.randomUUID().toString()
        val seq = nextSeq()
        Log.d(TAG, "[CALL_SIG] Sent ANSWER callId=$callId sigId=$signalId gen=$generation seq=$seq to=${peerKey.take(12)} mode=${mode.name}")
        sendSdp(peerKey, callId, mode, description, MeshProtocol.TYPE_CALL_ANSWER, signalId, generation, seq)
        return signalId
    }

    suspend fun sendIceCandidate(
        peerKey: String,
        callId: String,
        mode: CallMode,
        candidate: AstraIceCandidate,
        generation: Long = 0L
    ): String {
        val signalId = UUID.randomUUID().toString()
        val seq = nextSeq()
        Log.d(TAG, "[CALL_SIG] Sent ICE candidate mid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex} callId=$callId sigId=$signalId gen=$generation seq=$seq to=${peerKey.take(12)}")
        val payload = JSONObject()
            .put("callId", callId)
            .put("signalId", signalId)
            .put("generation", generation)
            .put("seq", seq)
            .put("mode", mode.name)
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .put("timestamp", System.currentTimeMillis())
            .toString()

        val now = System.currentTimeMillis()
        pendingIceOutbox[signalId] = TransientIceSignal(
            signalId = signalId,
            peerKey = peerKey,
            callId = callId,
            rawPayload = payload,
            generation = generation,
            seq = seq,
            timestamp = now,
            nextRetryAt = now + BASE_RETRY_INTERVAL_MS
        )

        sendRawCallSignal(peerKey, payload, MeshProtocol.TYPE_ICE_CANDIDATE)
        return signalId
    }

    suspend fun sendEnd(
        peerKey: String,
        callId: String,
        mode: CallMode,
        reason: String,
        generation: Long = 0L
    ): String {
        val signalId = UUID.randomUUID().toString()
        val seq = nextSeq()
        Log.d(TAG, "[CALL_SIG] Sent CALL_END callId=$callId sigId=$signalId gen=$generation seq=$seq to=${peerKey.take(12)} reason=$reason")
        val payload = JSONObject()
            .put("callId", callId)
            .put("signalId", signalId)
            .put("generation", generation)
            .put("seq", seq)
            .put("mode", mode.name)
            .put("reason", reason.take(160))
            .put("timestamp", System.currentTimeMillis())
            .toString()

        persistCriticalSignal(
            signalId = signalId,
            callId = callId,
            peerKey = peerKey,
            signalType = "END",
            generation = generation,
            seq = seq,
            rawPayload = payload,
            messageType = MeshProtocol.TYPE_CALL_END
        )

        sendRawCallSignal(peerKey, payload, MeshProtocol.TYPE_CALL_END)
        return signalId
    }

    suspend fun sendAck(
        peerKey: String,
        callId: String,
        ackSignalId: String,
        generation: Long = 0L,
        seq: Int = 0,
        ackType: String = "CALL_ACK"
    ) {
        val signalId = UUID.randomUUID().toString()
        val payload = MeshProtocol.encodeCallAck(
            callId = callId,
            signalId = signalId,
            ackSignalId = ackSignalId,
            fromKey = messageRouter.mySigningKeyHex,
            toKey = peerKey,
            generation = generation,
            seq = seq,
            ackType = ackType
        )
        Log.d(TAG, "[CALL_SIG] Dispatched ACK for ackSignalId=$ackSignalId type=$ackType callId=$callId to=${peerKey.take(12)}")
        sendRawCallSignal(peerKey, payload, MeshProtocol.TYPE_CALL_ACK)
    }

    fun handleAck(signal: CallSignal) {
        val ackSignalId = signal.ackSignalId ?: return
        Log.i(TAG, "[CALL_SIG_ACK] Received ACK for ackSignalId=$ackSignalId type=${signal.ackType}")
        pendingIceOutbox.remove(ackSignalId)
        db?.let { appDb ->
            scope.launch(ioDispatcher) {
                runCatching { appDb.callSignalingOutboxDao().deleteSignal(ackSignalId) }
            }
        }
    }

    fun clearCallSignals(callId: String) {
        pendingIceOutbox.entries.removeIf { it.value.callId == callId }
        db?.let { appDb ->
            scope.launch(ioDispatcher) {
                runCatching { appDb.callSignalingOutboxDao().deleteSignalsForCall(callId) }
            }
        }
    }

    private fun persistCriticalSignal(
        signalId: String,
        callId: String,
        peerKey: String,
        signalType: String,
        generation: Long,
        seq: Int,
        rawPayload: String,
        messageType: String
    ) {
        db?.let { appDb ->
            scope.launch(ioDispatcher) {
                runCatching {
                    appDb.callSignalingOutboxDao().insertSignal(
                        CallSignalingOutboxEntity(
                            signalId = signalId,
                            callId = callId,
                            peerKey = peerKey,
                            signalType = signalType,
                            generation = generation,
                            seq = seq,
                            rawPayload = rawPayload,
                            messageType = messageType,
                            createdAt = System.currentTimeMillis(),
                            retryCount = 0,
                            nextRetryAt = System.currentTimeMillis() + BASE_RETRY_INTERVAL_MS
                        )
                    )
                }
            }
        }
    }

    /**
     * Parses and validates an incoming call signal.
     * Enforces payload size limits and schema requirements.
     * @throws SecurityException if the signal is malformed, oversized, or missing required fields.
     */
    fun parse(raw: String): CallSignal {
        // Enforce total payload size limit
        if (raw.length > MAX_SIGNAL_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = null,
                peerKey = null,
                detail = "size=${raw.length} max=$MAX_SIGNAL_SIZE"
            )
            throw SecurityException("Signal payload exceeds maximum size: ${raw.length} > $MAX_SIGNAL_SIZE")
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_MALFORMED_SIGNAL,
                callId = null,
                peerKey = null,
                detail = "invalid JSON"
            )
            throw SecurityException("Malformed signal: invalid JSON")
        }

        // callId is mandatory
        val callId = json.optString("callId").takeIf { it.isNotBlank() }
            ?: throw SecurityException("Missing required field: callId").also {
                CallSecurityLogger.logSecurityEvent(
                    CallSecurityLogger.EVENT_MALFORMED_SIGNAL, null, null, "missing callId"
                )
            }

        // Validate SDP size if present
        val sdp = json.optString("sdp").takeIf { it.isNotBlank() }
        if (sdp != null && sdp.length > MAX_SDP_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = callId,
                peerKey = null,
                detail = "SDP size=${sdp.length} max=$MAX_SDP_SIZE"
            )
            throw SecurityException("SDP exceeds maximum size: ${sdp.length} > $MAX_SDP_SIZE")
        }

        // Validate ICE candidate size if present
        val candidate = json.optString("candidate").takeIf { it.isNotBlank() }
        if (candidate != null && candidate.length > MAX_ICE_CANDIDATE_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = callId,
                peerKey = null,
                detail = "ICE candidate size=${candidate.length} max=$MAX_ICE_CANDIDATE_SIZE"
            )
            throw SecurityException("ICE candidate exceeds maximum size: ${candidate.length} > $MAX_ICE_CANDIDATE_SIZE")
        }

        val parsedMode = runCatching { CallMode.valueOf(json.optString("mode", CallMode.AUDIO.name)) }.getOrDefault(CallMode.AUDIO)
        val signalId = json.optString("signalId").ifBlank { UUID.randomUUID().toString() }
        val generation = json.optLong("generation", 0L)
        val seq = json.optInt("seq", 0)
        val ackSignalId = json.optString("ackSignalId").takeIf { it.isNotBlank() }
        val ackType = json.optString("ackType").takeIf { it.isNotBlank() }
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())

        Log.d(TAG, "[CALL_SIG] Received signal callId=$callId sigId=$signalId gen=$generation seq=$seq ackSig=$ackSignalId ackType=$ackType mode=${parsedMode.name} hasSdp=${sdp != null} hasCandidate=${candidate != null} reason=${json.optString("reason")}")
        return CallSignal(
            callId = callId,
            signalId = signalId,
            generation = generation,
            seq = seq,
            ackSignalId = ackSignalId,
            ackType = ackType,
            timestamp = timestamp,
            mode = parsedMode,
            sdp = sdp,
            sdpType = json.optString("sdpType").takeIf { it.isNotBlank() },
            candidate = candidate,
            sdpMid = json.optString("sdpMid").takeIf { it.isNotBlank() },
            sdpMLineIndex = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null,
            reason = json.optString("reason").takeIf { it.isNotBlank() }
        )
    }

    private suspend fun sendSdp(
        peerKey: String,
        callId: String,
        mode: CallMode,
        description: AstraSessionDescription,
        type: String,
        signalId: String,
        generation: Long,
        seq: Int
    ) {
        val payload = JSONObject()
            .put("callId", callId)
            .put("signalId", signalId)
            .put("generation", generation)
            .put("seq", seq)
            .put("mode", mode.name)
            .put("sdpType", description.type)
            .put("sdp", description.description)
            .put("timestamp", System.currentTimeMillis())
            .toString()

        val signalType = if (type == MeshProtocol.TYPE_CALL_OFFER) "OFFER" else "ANSWER"
        persistCriticalSignal(
            signalId = signalId,
            callId = callId,
            peerKey = peerKey,
            signalType = signalType,
            generation = generation,
            seq = seq,
            rawPayload = payload,
            messageType = type
        )

        sendRawCallSignal(peerKey, payload, type)
    }

    private suspend fun sendRawCallSignal(peerKey: String, rawText: String, messageType: String) {
        withContext(ioDispatcher) {
            val session = activeSession
            if (session != null && session.isActive && session.peerKey == peerKey) {
                val wire = messageRouter.buildEncryptedWireFrame(peerKey, rawText, messageType)
                if (wire != null && session.sendFrame(wire)) {
                    return@withContext
                }
            }
            messageRouter.sendRawPayload(peerKey, rawText, messageType)
        }
    }
}
