package com.torxone.app.call

import android.content.Context
import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class CallManager(
    private val context: Context,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter
) {
    companion object {
        private const val TAG = "CallManager"
    }

    val stateStore = CallStateStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signaling = CallSignalingHandler(messageRouter)
    private val permissions = AudioVideoPermissionManager(context)
    private val engines = listOf(
        LanAudioEngine(context),
        BluetoothWalkieTalkieEngine(),
        WebRtcCallEngine(),
        VoiceNoteCallEngine(messageRouter),
        DisabledCallEngine("No call engine is available for this route.")
    )
    private val adaptiveRouter = AdaptiveCallRouter(messageRouter, engines)
    private var activeEngine: CallEngine? = null
    private var activeCallId: String? = null
    private var activePeerKey: String? = null
    private var activeMode: CallMode = CallMode.AUDIO
    private var pendingOffer: AstraSessionDescription? = null

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
        endCall("Call declined")
    }

    fun endCall(reason: String = "Call ended") {
        activeEngine?.end()
        activeEngine = null
        activeCallId = null
        activePeerKey = null
        pendingOffer = null
        stateStore.update(CallUiState.Ended(reason))
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
        val peerName = contact?.name ?: "TorX One contact"
        val offer = AstraSessionDescription("offer", signal.sdp ?: return)
        activeCallId = signal.callId
        activePeerKey = senderKey
        activeMode = signal.mode
        pendingOffer = offer
        stateStore.update(CallUiState.Ringing(signal.callId, senderKey, peerName, CallDirection.INCOMING, signal.mode))
    }

    private suspend fun handleAnswer(signal: CallSignal, senderKey: String) {
        val contact = db.contactDao().getContact(senderKey)
        val callId = signal.callId
        val answer = AstraSessionDescription("answer", signal.sdp ?: return)
        activeEngine?.handleRemoteDescription(answer)
        stateStore.update(CallUiState.Connecting(callId, senderKey, contact?.name ?: "TorX One contact", signal.mode))
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
                val state = if (result.mode == CallMode.VOICE_NOTE || result.mode == CallMode.WALKIE_TALKIE) {
                    CallUiState.Connected(callId, peerKey, peerName, result.mode)
                } else {
                    CallUiState.Connecting(callId, peerKey, peerName, result.mode)
                }
                stateStore.update(state)
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
}
