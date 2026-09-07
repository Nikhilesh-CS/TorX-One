package com.torxone.app.call

import android.content.Context
import android.util.Log
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * Production WebRTC call engine. Replaces the disabled stub.
 * Uses WebRtcClient for real-time audio with SRTP encryption,
 * ICE NAT traversal, Opus codec, and echo cancellation.
 */
class WebRtcCallEngine(
    private val context: Context,
    private val signaling: CallSignalingHandler,
    private val stateStore: CallStateStore,
    private val audioRouteManager: AudioRouteManager
) : CallEngine {
    companion object {
        private const val TAG = "WebRtcCallEngine"
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

    private var client: WebRtcClient? = null
    private var activeCallId: String? = null
    private var activePeerKey: String? = null
    private var activeMode: CallMode = CallMode.AUDIO
    
    private var engineScope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    
    private var isIceConnected = false
    private var isMediaReceived = false
    private var isCleaningUp = false

    override fun isAvailable(context: CallRouteContext): Boolean {
        // WebRTC is now always available — the native library is bundled
        return true
    }

    override suspend fun startOutgoing(
        callId: String,
        contact: ContactEntity,
        context: CallRouteContext
    ): CallStartResult {
        return withContext(Dispatchers.IO) {
            try {
                activeCallId = callId
                activePeerKey = contact.signingPublicKey
                activeMode = CallMode.AUDIO

                val rtcClient = createAndInitClient()
                client = rtcClient

                rtcClient.createPeerConnection()
                rtcClient.startAudioSession()
                audioRouteManager.startCallAudio()

                val offer = rtcClient.createOffer()
                signaling.sendOffer(contact.signingPublicKey, callId, CallMode.AUDIO, offer)

                Log.d(TAG, "Outgoing call offer sent: $callId")
                CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start outgoing call", e)
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
                activePeerKey = contact.signingPublicKey
                activeMode = CallMode.AUDIO

                val rtcClient = createAndInitClient()
                client = rtcClient

                rtcClient.createPeerConnection()
                rtcClient.startAudioSession()
                audioRouteManager.startCallAudio()

                rtcClient.setRemoteDescription(offer)
                val answer = rtcClient.createAnswer()
                signaling.sendAnswer(contact.signingPublicKey, callId, CallMode.AUDIO, answer)

                Log.d(TAG, "Incoming call accepted, answer sent: $callId")
                CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept incoming call", e)
                cleanup()
                CallStartResult.Failed("WebRTC accept failed: ${e.message}")
            }
        }
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) {
        client?.setRemoteDescription(description)
    }

    override suspend fun handleRenegotiationOffer(offer: AstraSessionDescription, peerKey: String, callId: String) {
        Log.d(TAG, "Handling ICE restart renegotiation offer from $peerKey")
        client?.setRemoteDescriptionSuspend(offer)
        val answer = client?.createAnswer() ?: return
        signaling.sendAnswer(peerKey, callId, activeMode, answer)
    }

    override fun handleIceCandidate(candidate: AstraIceCandidate) {
        client?.addIceCandidate(candidate)
    }

    fun setMicEnabled(enabled: Boolean) {
        client?.setMicEnabled(enabled)
    }

    override fun end() {
        Log.d(TAG, "Ending call")
        cleanup()
    }

    private fun createAndInitClient(): WebRtcClient {
        isCleaningUp = false
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rtcClient = WebRtcClient(
            context = context,
            iceServerProvider = DefaultIceServerProvider(),
            onIceCandidate = { candidate ->
                if (isCleaningUp) return@WebRtcClient
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                engineScope?.launch(Dispatchers.IO) {
                    signaling.sendIceCandidate(peerKey, callId, activeMode, candidate)
                }
            },
            onConnected = {
                reconnectJob?.cancel()
                reconnectJob = null
                isIceConnected = true
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "ICE connected: $callId")
                if (isMediaReceived) {
                    checkFullyConnected()
                } else {
                    stateStore.update(CallUiState.IceConnecting(callId, peerKey, "", activeMode))
                }
            },
            onDisconnected = {
                if (isCleaningUp) return@WebRtcClient
                Log.d(TAG, "Call disconnected, triggering reconnect")
                stateStore.update(CallUiState.Reconnecting(activeCallId ?: "", activePeerKey ?: "", "", activeMode))
                isIceConnected = false
                // WebRtcClient will fire onReconnecting next if it was truly a disconnect
            },
            onReconnecting = {
                if (isCleaningUp) return@WebRtcClient
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "Call reconnecting (ICE Restart): $callId")
                stateStore.update(CallUiState.Reconnecting(callId, peerKey, "", activeMode))
                isIceConnected = false
                
                if (reconnectJob?.isActive == true) return@WebRtcClient
                
                reconnectJob = engineScope?.launch {
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        try {
                            val newOffer = client?.performIceRestart()
                            if (newOffer != null) {
                                signaling.sendOffer(peerKey, callId, activeMode, newOffer)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to perform ICE restart", e)
                        }
                        
                        // Wait for a long interval to prevent overlapping negotiations
                        // If it succeeds, the onConnected callback cancels this job
                        kotlinx.coroutines.delay(10000)
                        
                        if (System.currentTimeMillis() - startTime > 30000) {
                            Log.e(TAG, "Reconnection timed out")
                            withContext(Dispatchers.Main) {
                                cleanup()
                                stateStore.update(CallUiState.Ended("Connection lost"))
                            }
                            break
                        }
                    }
                }
            },
            onRemoteTrackReceived = {
                isMediaReceived = true
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "Media received: $callId")
                if (isIceConnected) {
                    checkFullyConnected()
                } else {
                    stateStore.update(CallUiState.MediaConnecting(callId, peerKey, "", activeMode))
                }
            }
        )
        rtcClient.initialize()
        return rtcClient
    }

    private fun checkFullyConnected() {
        if (isIceConnected && isMediaReceived) {
            val callId = activeCallId ?: return
            val peerKey = activePeerKey ?: return
            Log.d(TAG, "Call fully connected (ICE + Media): $callId")
            stateStore.update(CallUiState.Connected(
                callId = callId,
                peerKey = peerKey,
                peerName = "", // Will be updated by CallManager
                mode = activeMode
            ))
        }
    }

    private fun cleanup() {
        if (isCleaningUp) return
        isCleaningUp = true
        engineScope?.cancel()
        engineScope = null
        reconnectJob?.cancel()
        reconnectJob = null
        client?.close()
        client = null
        audioRouteManager.stopCallAudio()
        activeCallId = null
        activePeerKey = null
        isIceConnected = false
        isMediaReceived = false
    }
}
