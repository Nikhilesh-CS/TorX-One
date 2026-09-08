package com.torxone.app.call

import android.content.Context
import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CallManager(
    private val context: Context,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RING_TIMEOUT_MS = 30_000L
        private const val TERMINATED_CALL_TTL_MS = 2 * 60 * 1000L
    }

    val stateStore = CallStateStore()
    val audioRouteManager = AudioRouteManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signaling = CallSignalingHandler(messageRouter)
    private val permissions = AudioVideoPermissionManager(context)

    private val webRtcEngine = WebRtcCallEngine(context, signaling, stateStore, audioRouteManager)
    private val voiceNoteEngine = VoiceNoteCallEngine(messageRouter)
    private val engines: List<CallEngine> = listOf(webRtcEngine, voiceNoteEngine)
    private val adaptiveRouter = AdaptiveCallRouter(messageRouter, engines)

    private var activeEngine: CallEngine? = null
    private var activeCallId: String? = null
    private var activePeerKey: String? = null
    private var activeMode: CallMode = CallMode.AUDIO
    private var pendingOffer: AstraSessionDescription? = null
    private var durationJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var callStartTimeMs: Long = 0L
    private val terminatedCalls = ConcurrentHashMap<String, Long>()

    fun startAudioCall(peerKey: String) {
        scope.launch {
            if (!permissions.hasAudioPermission()) {
                stateStore.update(CallUiState.Unavailable("Microphone permission is required."))
                return@launch
            }
            val contact = db.contactDao().getContact(peerKey)
            if (contact == null) {
                stateStore.update(CallUiState.Unavailable("Contact not found."))
                return@launch
            }
            val routeContext = adaptiveRouter.buildContext(contact)
            if (routeContext.transport == com.torxone.app.network.Transport.FAILED) {
                stateStore.update(CallUiState.Unavailable("Peer is offline. Move closer or wait for mesh connection."))
                return@launch
            }
            val callId = UUID.randomUUID().toString()
            activeCallId = callId
            activePeerKey = peerKey
            stateStore.update(CallUiState.Ringing(callId, peerKey, contact.name, CallDirection.OUTGOING, CallMode.AUDIO))
            startRingTimeout()

            val selected = adaptiveRouter.selectAudioEngine(routeContext)
            if (selected == null) {
                stateStore.update(CallUiState.Unavailable("No compatible call engine is available for ${routeContext.transport}."))
                return@launch
            }

            activeEngine = selected
            val result = selected.startOutgoing(callId, contact, routeContext)
            handleStartResult(result, callId, peerKey, contact.name, selected.capabilities.type)
        }
    }

    fun acceptIncomingCall() {
        scope.launch {
            val callId = activeCallId ?: return@launch
            val peerKey = activePeerKey ?: return@launch
            val offer = pendingOffer ?: return@launch
            val contact = db.contactDao().getContact(peerKey)
            if (contact == null) {
                endCall("Contact missing")
                return@launch
            }
            if (!permissions.hasAudioPermission()) {
                stateStore.update(CallUiState.Unavailable("Microphone permission is required."))
                return@launch
            }
            cancelRingTimeout()
            com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
            val routeContext = adaptiveRouter.buildContext(contact)
            val selected = adaptiveRouter.selectAudioEngine(routeContext)
            if (selected == null) {
                stateStore.update(CallUiState.Unavailable("No compatible call engine is available for ${routeContext.transport}."))
                return@launch
            }
            activeEngine = selected
            val result = selected.acceptIncoming(callId, contact, offer)
            handleStartResult(result, callId, peerKey, contact.name, selected.capabilities.type)
        }
    }

    fun rejectIncomingCall() {
        cancelRingTimeout()
        endCall("Call declined")
    }

    fun endCall(reason: String = "Call ended") {
        cancelRingTimeout()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        stopDurationTimer()
        val duration = if (callStartTimeMs > 0) {
            ((System.currentTimeMillis() - callStartTimeMs) / 1000).toInt()
        } else 0
        val endingCallId = activeCallId
        val endingPeerKey = activePeerKey
        if (endingCallId != null) {
            rememberTerminated(endingCallId)
            if (endingPeerKey != null) {
                scope.launch { signaling.sendEnd(endingPeerKey, endingCallId, activeMode, reason) }
            }
        }
        activeEngine?.end()
        activeEngine = null
        activeCallId = null
        activePeerKey = null
        pendingOffer = null
        callStartTimeMs = 0L
        stateStore.update(CallUiState.Ended(reason, duration))
    }

    fun toggleMute() {
        val muted = audioRouteManager.toggleMute()
        (activeEngine as? WebRtcCallEngine)?.setMicEnabled(!muted)
        stateStore.updateConnectedState(isMuted = muted)
    }

    fun toggleSpeaker() {
        val speaker = audioRouteManager.toggleSpeaker()
        stateStore.updateConnectedState(isSpeaker = speaker)
    }

    fun handleSignal(packetType: String, rawPayload: String, senderKey: String) {
        scope.launch {
            runCatching {
                val signal = signaling.parse(rawPayload)
                when (packetType) {
                    com.torxone.app.network.MeshProtocol.TYPE_CALL_OFFER -> handleOffer(signal, senderKey)
                    com.torxone.app.network.MeshProtocol.TYPE_CALL_ANSWER -> handleAnswer(signal, senderKey)
                    com.torxone.app.network.MeshProtocol.TYPE_ICE_CANDIDATE -> handleIce(signal)
                    com.torxone.app.network.MeshProtocol.TYPE_CALL_END -> handleRemoteEnd(signal, senderKey)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to handle call signal $packetType", e)
            }
        }
    }

    private suspend fun handleOffer(signal: CallSignal, senderKey: String) {
        if (isTerminated(signal.callId)) {
            Log.d(TAG, "Ignoring delayed offer for terminated call ${signal.callId}")
            return
        }
        val contact = db.contactDao().getContact(senderKey)
        val peerName = contact?.name ?: "Unknown Contact"
        val offer = AstraSessionDescription("offer", signal.sdp ?: return)
        
        // Differentiate between new call and ICE restart renegotiation
        if (activeCallId == signal.callId && activeEngine != null) {
            Log.d(TAG, "Received renegotiation offer for active call: ${signal.callId}")
            activeEngine?.handleRenegotiationOffer(offer, senderKey, signal.callId)
            return
        }

        activeCallId = signal.callId
        activePeerKey = senderKey
        activeMode = signal.mode
        pendingOffer = offer
        stateStore.update(CallUiState.Ringing(signal.callId, senderKey, peerName, CallDirection.INCOMING, signal.mode))
        com.torxone.app.service.NotificationHelper.showIncomingCall(context, signal.callId, senderKey, peerName)
        startRingTimeout()
    }

    private suspend fun handleAnswer(signal: CallSignal, senderKey: String) {
        if (signal.callId != activeCallId || senderKey != activePeerKey || isTerminated(signal.callId)) return
        cancelRingTimeout()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        val contact = db.contactDao().getContact(senderKey)
        val callId = signal.callId
        val answer = AstraSessionDescription("answer", signal.sdp ?: return)
        activeEngine?.handleRemoteDescription(answer)
        val peerName = contact?.name ?: "Unknown Contact"
        stateStore.update(CallUiState.Accepted(callId, senderKey, peerName, signal.mode))
    }

    private fun handleIce(signal: CallSignal) {
        if (signal.callId != activeCallId || isTerminated(signal.callId)) return
        val candidateText = signal.candidate ?: return
        val mid = signal.sdpMid ?: return
        val index = signal.sdpMLineIndex ?: return
        activeEngine?.handleIceCandidate(AstraIceCandidate(mid, index, candidateText))
    }

    private fun handleRemoteEnd(signal: CallSignal, senderKey: String) {
        if (senderKey != activePeerKey || signal.callId != activeCallId) {
            rememberTerminated(signal.callId)
            return
        }
        val reason = signal.reason ?: "Remote ended call"
        // Avoid echoing CALL_END back to the peer.
        cancelRingTimeout()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        stopDurationTimer()
        rememberTerminated(signal.callId)
        activeEngine?.end()
        activeEngine = null
        activeCallId = null
        activePeerKey = null
        pendingOffer = null
        callStartTimeMs = 0L
        stateStore.update(CallUiState.Ended(reason, 0))
    }

    private fun rememberTerminated(callId: String) {
        val now = System.currentTimeMillis()
        terminatedCalls[callId] = now + TERMINATED_CALL_TTL_MS
        terminatedCalls.entries.removeIf { it.value <= now }
    }

    private fun isTerminated(callId: String): Boolean = (terminatedCalls[callId] ?: 0L) > System.currentTimeMillis()

    private fun handleStartResult(
        result: CallStartResult,
        callId: String,
        peerKey: String,
        peerName: String,
        engineType: CallEngineType
    ) {
        when (result) {
            is CallStartResult.Started -> {
                activeMode = result.mode
                if (result.mode == CallMode.VOICE_NOTE || result.mode == CallMode.WALKIE_TALKIE) {
                    cancelRingTimeout()
                    startDurationTimer()
                    stateStore.update(CallUiState.Connected(callId, peerKey, peerName, result.mode))
                } else {
                    // For WebRTC: Connecting state. Connected state will be set by ICE callback.
                    stateStore.update(CallUiState.Negotiating(callId, peerKey, peerName, result.mode))
                }
            }
            is CallStartResult.Fallback -> {
                val fallback = engines.firstOrNull { it.capabilities.type == result.preferredEngine }
                if (fallback == null || fallback == activeEngine) {
                    stateStore.update(CallUiState.Unavailable(result.reason))
                    return
                }
                activeEngine = fallback
                scope.launch {
                    val contact = db.contactDao().getContact(peerKey)
                    if (contact == null) {
                        stateStore.update(CallUiState.Unavailable("Contact not found."))
                        return@launch
                    }
                    val routeContext = adaptiveRouter.buildContext(contact)
                    val fallbackResult = fallback.startOutgoing(callId, contact, routeContext)
                    handleStartResult(fallbackResult, callId, peerKey, peerName, fallback.capabilities.type)
                }
            }
            is CallStartResult.Failed -> {
                stateStore.update(CallUiState.Unavailable(result.reason))
            }
        }
    }

    // ──────────────────────── Duration Timer ────────────────────────

    private fun startDurationTimer() {
        callStartTimeMs = System.currentTimeMillis()
        durationJob = scope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                stateStore.updateConnectedState(durationSeconds = seconds)
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    // ──────────────────────── Ring Timeout ────────────────────────

    private fun startRingTimeout() {
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            Log.d(TAG, "Ring timeout — ending call")
            endCall("No answer")
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    // Start duration timer when WebRTC reports Connected
    init {
        scope.launch {
            stateStore.state.collect { state ->
                if (state is CallUiState.Connected && durationJob == null) {
                    startDurationTimer()
                }
            }
        }
    }
}
