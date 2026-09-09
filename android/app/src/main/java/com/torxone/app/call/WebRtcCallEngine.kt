package com.torxone.app.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production WebRTC call engine.
 * Uses WebRtcClient for real-time audio with SRTP encryption,
 * ICE NAT traversal, Opus codec, echo cancellation, single-flight ICE restarts,
 * and periodic rolling quality monitoring.
 */
class WebRtcCallEngine(
    private val context: Context,
    private val signaling: CallSignalingHandler,
    private val stateStore: CallStateStore,
    private val callAudioManager: CallAudioManager
) : CallEngine {
    companion object {
        private const val TAG = "WebRtcCallEngine"
        const val RECONNECT_GRACE_MS = 15_000L
        const val TRANSIENT_WAIT_MS = 2_000L
    }

    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.WEBRTC,
        supportsLiveAudio = true,
        supportsLiveVideo = true,
        supportsAsyncSegments = false,
        supportedTransports = setOf(
            Transport.NEARBY_DIRECT,
            Transport.NEARBY_RELAY,
            Transport.TOR
        )
    )

    var diagnostics: CallConnectionDiagnostics? = null
    private var client: WebRtcClient? = null
    private var activeCallId: String? = null
    private var activePeerKey: String? = null
    private var activePeerName: String = ""
    private var activeMode: CallMode = CallMode.AUDIO
    
    private var engineScope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var qualityMonitor: CallQualityMonitor? = null
    private val iceRestartInProgress = AtomicBoolean(false)
    
    private var isIceConnected = false
    private var isMediaReceived = false
    /** True only after ICE + remote media have established a real call. */
    private var hasEstablishedCall = false
    /** True after call has been accepted (startOutgoing/startIncoming completed). */
    private var hasAcceptedCall = false
    private var isCleaningUp = false
    private val isRemoteDescriptionSet = AtomicBoolean(false)
    private val queuedIceCandidates = java.util.concurrent.ConcurrentLinkedQueue<AstraIceCandidate>()
    private val appliedIceKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile
    var currentGeneration: Long = 0L

    override fun isAvailable(context: CallRouteContext): Boolean = true

    override suspend fun startOutgoing(
        callId: String,
        contact: ContactEntity,
        context: CallRouteContext
    ): CallStartResult {
        return withContext(Dispatchers.IO) {
            try {
                activeCallId = callId
                hasAcceptedCall = true
                activePeerKey = contact.signingPublicKey
                activePeerName = contact.name
                activeMode = CallMode.AUDIO

                val rtcClient = createAndInitClient()
                client = rtcClient

                rtcClient.createPeerConnection()
                rtcClient.startAudioSession()
                callAudioManager.startCallAudio()
                rtcClient.setMicEnabled(!callAudioManager.isMuted)

                val offer = rtcClient.createOffer()
                diagnostics?.markOfferSent()
                signaling.sendOffer(contact.signingPublicKey, callId, CallMode.AUDIO, offer, currentGeneration)

                Log.d(TAG, "Outgoing call offer sent: $callId")
                CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start outgoing call", e)
                diagnostics?.markFailed("WebRTC init failed: ${e.message}")
                cleanup()
                CallStartResult.Failed("WebRTC initialization failed: ${e.message}")
            }
        }
    }

    override suspend fun acceptIncoming(
        callId: String,
        contact: ContactEntity,
        offer: AstraSessionDescription
    ): CallStartResult {
        return withContext(Dispatchers.IO) {
            try {
                activeCallId = callId
                hasAcceptedCall = true
                activePeerKey = contact.signingPublicKey
                activePeerName = contact.name
                activeMode = CallMode.AUDIO

                val rtcClient = createAndInitClient()
                client = rtcClient

                rtcClient.createPeerConnection()
                rtcClient.startAudioSession()
                callAudioManager.startCallAudio()
                rtcClient.setMicEnabled(!callAudioManager.isMuted)

                rtcClient.setRemoteDescription(offer)
                isRemoteDescriptionSet.set(true)
                flushQueuedIceCandidates()
                val answer = rtcClient.createAnswer()
                diagnostics?.markAnswerSent()
                signaling.sendAnswer(contact.signingPublicKey, callId, CallMode.AUDIO, answer, currentGeneration)

                Log.d(TAG, "Incoming call accepted, answer sent: $callId")
                CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept incoming call", e)
                diagnostics?.markFailed("WebRTC accept failed: ${e.message}")
                cleanup()
                CallStartResult.Failed("WebRTC accept failed: ${e.message}")
            }
        }
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) {
        val rtcClient = client ?: return
        diagnostics?.record("REMOTE_DESCRIPTION_SET")
        rtcClient.setRemoteDescription(description)
        isRemoteDescriptionSet.set(true)
        flushQueuedIceCandidates()
    }

    override suspend fun handleRenegotiationOffer(offer: AstraSessionDescription, peerKey: String, callId: String) {
        val rtcClient = client ?: return
        Log.d(TAG, "Processing renegotiation offer for call $callId")
        diagnostics?.record("RENEGOTIATION_PROCESSING")
        try {
            rtcClient.setRemoteDescriptionSuspend(offer)
            isRemoteDescriptionSet.set(true)
            flushQueuedIceCandidates()
            val answer = rtcClient.createAnswer()
            signaling.sendAnswer(peerKey, callId, activeMode, answer, currentGeneration)
            Log.d(TAG, "Renegotiation answer dispatched for call $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed responding to renegotiation offer", e)
        }
    }

    override fun handleIceCandidate(candidate: AstraIceCandidate) {
        val key = "${candidate.sdpMid}_${candidate.sdpMLineIndex}_${candidate.sdp}"
        if (!appliedIceKeys.add(key)) {
            Log.d(TAG, "[ICE] Duplicate candidate ignored ($key)")
            return
        }
        val rtcClient = client
        if (rtcClient != null && isRemoteDescriptionSet.get()) {
            rtcClient.addIceCandidate(candidate)
        } else {
            if (queuedIceCandidates.size < 100) {
                queuedIceCandidates.add(candidate)
                Log.d(TAG, "[ICE] Queued candidate mid=${candidate.sdpMid} (remote SDP pending, queueSize=${queuedIceCandidates.size})")
            } else {
                Log.w(TAG, "[ICE] Dropped candidate mid=${candidate.sdpMid} (queue full: 100)")
            }
        }
    }

    private fun flushQueuedIceCandidates() {
        val rtcClient = client ?: return
        var flushedCount = 0
        while (true) {
            val c = queuedIceCandidates.poll() ?: break
            rtcClient.addIceCandidate(c)
            flushedCount++
        }
        if (flushedCount > 0) {
            Log.d(TAG, "[ICE] Flushed $flushedCount queued candidates to WebRTC client")
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        client?.setMicEnabled(enabled)
    }

    override fun end() {
        Log.d(TAG, "Ending WebRTC call")
        cleanup()
    }

    private fun createAndInitClient(): WebRtcClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineScope = scope
        isCleaningUp = false
        isIceConnected = false
        isMediaReceived = false
        hasEstablishedCall = false
        hasAcceptedCall = false
        iceRestartInProgress.set(false)

        val rtcClient = WebRtcClient(
            context = context,
            iceServerProvider = DefaultIceServerProvider(),
            onIceCandidate = { candidate ->
                if (isCleaningUp) return@WebRtcClient
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                diagnostics?.markIceCandidateSent(candidate.sdpMid)
                engineScope?.launch(Dispatchers.IO) {
                    signaling.sendIceCandidate(peerKey, callId, activeMode, candidate, currentGeneration)
                }
            },
            onConnected = {
                reconnectJob?.cancel()
                reconnectJob = null
                iceRestartInProgress.set(false)
                isIceConnected = true
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "ICE connected: $callId")
                diagnostics?.markIceConnected()
                if (isMediaReceived) {
                    checkFullyConnected()
                } else {
                    stateStore.update(CallUiState.MediaConnecting(callId, peerKey, activePeerName, activeMode))
                }
            },
            onDisconnected = {
                startStagedRecovery("ICE Disconnected")
            },
            onReconnecting = {
                startStagedRecovery("WebRTC IceReconnecting")
            },
            onRemoteTrackReceived = {
                isMediaReceived = true
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "Media received: $callId")
                diagnostics?.markRemoteTrackReceived()
                diagnostics?.markAudioReady()
                if (isIceConnected) {
                    checkFullyConnected()
                } else {
                    stateStore.update(CallUiState.MediaConnecting(callId, peerKey, activePeerName, activeMode))
                }
            },
            onIceChecking = {
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                diagnostics?.markIceChecking()
                stateStore.update(CallUiState.IceConnecting(callId, peerKey, activePeerName, activeMode))
            },
            onIceGatheringChange = { gatheringState ->
                if (gatheringState == org.webrtc.PeerConnection.IceGatheringState.GATHERING) {
                    diagnostics?.markIceGatheringStart()
                } else if (gatheringState == org.webrtc.PeerConnection.IceGatheringState.COMPLETE) {
                    diagnostics?.markIceGatheringComplete(0)
                }
            }
        )

        qualityMonitor = CallQualityMonitor(
            scope = scope,
            statsProvider = { cb -> rtcClient.getStats(cb) },
            onQualityUpdate = { quality, stats ->
                diagnostics?.recordQualitySnapshot(stats.roundTripMs, stats.packetLossPercent, stats.jitterMs)
                stateStore.updateConnectedState(quality = quality, stats = stats)
            }
        )

        rtcClient.initialize()
        return rtcClient
    }

    private fun startStagedRecovery(reason: String) {
        if (isCleaningUp) return
        if (!hasAcceptedCall) {
            Log.d(TAG, "Ignoring ICE recovery before call is accepted: $reason")
            return
        }
        val callId = activeCallId ?: return
        val peerKey = activePeerKey ?: return

        Log.d(TAG, "Starting staged recovery ($reason) for call $callId")
        diagnostics?.markReconnecting(reason)
        qualityMonitor?.stop()
        isIceConnected = false

        stateStore.update(
            CallUiState.Reconnecting(
                callId = callId,
                peerKey = peerKey,
                peerName = activePeerName,
                mode = activeMode,
                isMuted = callAudioManager.isMuted,
                isSpeaker = callAudioManager.isSpeaker
            )
        )

        if (reconnectJob?.isActive == true) return

        reconnectJob = engineScope?.launch {
            val startMonotonic = SystemClock.elapsedRealtime()

            // Stage 1 (0-2s): Transient wait for self-healing
            delay(TRANSIENT_WAIT_MS)
            if (!isActive || isIceConnected) {
                Log.d(TAG, "Self-healing resolved connection without full ICE restart")
                return@launch
            }

            // Stage 2 (2-5s): Single-flight ICE restart
            if (iceRestartInProgress.compareAndSet(false, true)) {
                try {
                    Log.i(TAG, "Executing single-flight ICE restart for call $callId")
                    diagnostics?.record("ICE_RESTART_TRIGGERED")
                    val newOffer = client?.performIceRestart()
                    if (newOffer != null) {
                        currentGeneration++
                        signaling.sendOffer(peerKey, callId, activeMode, newOffer, currentGeneration)
                        diagnostics?.record("ICE_RESTART_OFFER_SENT")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed performing ICE restart: ${e.message}", e)
                    iceRestartInProgress.set(false)
                }
            }

            // Stage 3 (5-15s): Wait within RECONNECT_GRACE_MS
            while (isActive && !isIceConnected) {
                val elapsed = SystemClock.elapsedRealtime() - startMonotonic
                if (elapsed >= RECONNECT_GRACE_MS) {
                    Log.e(TAG, "Reconnection grace period ($RECONNECT_GRACE_MS ms) expired")
                    iceRestartInProgress.set(false)
                    withContext(Dispatchers.Main) {
                        cleanup()
                        stateStore.update(CallUiState.Ended("Connection lost"))
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    fun triggerNetworkHandover() {
        if (!hasEstablishedCall) {
            Log.d(TAG, "Ignoring network handover before call is fully established")
            return
        }
        val peerKey = activePeerKey ?: return
        val callId = activeCallId ?: return
        Log.d(TAG, "Network handover triggered for call $callId")
        diagnostics?.record("NETWORK_HANDOVER")
        qualityMonitor?.stop()
        isIceConnected = false

        stateStore.update(
            CallUiState.Reconnecting(
                callId = callId,
                peerKey = peerKey,
                peerName = activePeerName,
                mode = activeMode,
                isMuted = callAudioManager.isMuted,
                isSpeaker = callAudioManager.isSpeaker
            )
        )

        if (!iceRestartInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "ICE restart already in progress, coalescing handover request")
            return
        }

        engineScope?.launch {
            try {
                Log.i(TAG, "Executing handover single-flight ICE restart")
                val newOffer = client?.performIceRestart()
                if (newOffer != null) {
                    currentGeneration++
                    signaling.sendOffer(peerKey, callId, activeMode, newOffer, currentGeneration)
                    diagnostics?.record("HANDOVER_RESTART_OFFER_SENT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed ICE restart on network handover", e)
                iceRestartInProgress.set(false)
            }
        }
    }

    private fun checkFullyConnected() {
        if (isIceConnected && isMediaReceived) {
            val callId = activeCallId ?: return
            val peerKey = activePeerKey ?: return
            Log.d(TAG, "Call fully connected (ICE + Media): $callId")
            diagnostics?.markFullyConnected()
            hasEstablishedCall = true

            // Restore mute and speaker settings
            callAudioManager.restoreAudioState()
            client?.setMicEnabled(!callAudioManager.isMuted)

            stateStore.update(CallUiState.Connected(
                callId = callId,
                peerKey = peerKey,
                peerName = activePeerName,
                mode = activeMode,
                isMuted = callAudioManager.isMuted,
                isSpeaker = callAudioManager.isSpeaker
            ))

            // Start rolling quality monitor
            qualityMonitor?.start()
        }
    }

    private fun cleanup() {
        if (isCleaningUp) return
        isCleaningUp = true
        qualityMonitor?.stop()
        qualityMonitor = null
        iceRestartInProgress.set(false)
        isRemoteDescriptionSet.set(false)
        queuedIceCandidates.clear()
        appliedIceKeys.clear()
        currentGeneration = 0L
        hasEstablishedCall = false
        hasAcceptedCall = false
        engineScope?.cancel()
        engineScope = null
        reconnectJob?.cancel()
        reconnectJob = null
        client?.close()
        client = null
        callAudioManager.stopCallAudio()
        activeCallId = null
        activePeerKey = null
        activePeerName = ""
        isIceConnected = false
        isMediaReceived = false
    }
}
