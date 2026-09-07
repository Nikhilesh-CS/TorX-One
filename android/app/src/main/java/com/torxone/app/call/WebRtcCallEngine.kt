package com.torxone.app.call

import android.content.Context
import android.util.Log
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
        val rtcClient = WebRtcClient(
            context = context,
            onIceCandidate = { candidate ->
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    signaling.sendIceCandidate(peerKey, callId, activeMode, candidate)
                }
            },
            onConnected = {
                val callId = activeCallId ?: return@WebRtcClient
                val peerKey = activePeerKey ?: return@WebRtcClient
                Log.d(TAG, "Call connected: $callId")
                stateStore.update(CallUiState.Connected(
                    callId = callId,
                    peerKey = peerKey,
                    peerName = "", // Will be updated by CallManager
                    mode = activeMode
                ))
            },
            onDisconnected = {
                Log.d(TAG, "Call disconnected")
                cleanup()
                stateStore.update(CallUiState.Ended("Connection lost"))
            }
        )
        rtcClient.initialize()
        return rtcClient
    }

    private fun cleanup() {
        client?.close()
        client = null
        audioRouteManager.stopCallAudio()
        activeCallId = null
        activePeerKey = null
    }
}
