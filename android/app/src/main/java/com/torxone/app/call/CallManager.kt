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

class CallManager(
    private val context: Context,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RING_TIMEOUT_MS = 30_000L
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
        stopDurationTimer()
        val duration = if (callStartTimeMs > 0) {
            ((System.currentTimeMillis() - callStartTimeMs) / 1000).toInt()
        } else 0
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
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to handle call signal $packetType", e)
            }
        }
    }

    private suspend fun handleOffer(signal: CallSignal, senderKey: String) {
        val contact = db.contactDao().getContact(senderKey)
        val peerName = contact?.name ?: "Unknown Contact"
        val offer = AstraSessionDescription("offer", signal.sdp ?: return)
        activeCallId = signal.callId
        activePeerKey = senderKey
        activeMode = signal.mode
        pendingOffer = offer
        stateStore.update(CallUiState.Ringing(signal.callId, senderKey, peerName, CallDirection.INCOMING, signal.mode))
        startRingTimeout()
    }

    private suspend fun handleAnswer(signal: CallSignal, senderKey: String) {
        cancelRingTimeout()
        val contact = db.contactDao().getContact(senderKey)
        val callId = signal.callId
        val answer = AstraSessionDescription("answer", signal.sdp ?: return)
        activeEngine?.handleRemoteDescription(answer)
        val peerName = contact?.name ?: "Unknown Contact"
        stateStore.update(CallUiState.Accepted(callId, senderKey, peerName, signal.mode))
    }

    private fun handleIce(signal: CallSignal) {
        val candidateText = signal.candidate ?: return
        val mid = signal.sdpMid ?: return
        val index = signal.sdpMLineIndex ?: return
        activeEngine?.handleIceCandidate(AstraIceCandidate(mid, index, candidateText))
    }

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
