package com.torxone.app.media

import android.content.Context
import com.torxone.app.calls.*
import com.torxone.app.connection.Connection
import com.torxone.app.data.dao.ContactDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Separate silent WebRTC lane. Voice/video calls retain their own manager and resources. */
class FileRtcClient(
    context: Context,
    signaling: CallSignaling,
    localIdentity: () -> String?,
    private val contacts: ContactDao,
    private val connectionForQueue: (String) -> Connection?,
    private val acceptFrame: suspend (String, ByteArray) -> Boolean,
    authorizeOffer: suspend (String) -> Boolean,
    private val relayOnlyProvider: suspend () -> Boolean = { false }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val manager = CallManager(signaling, scope = scope, localIdentityIdProvider = localIdentity)
    val handler = CallHandler(manager, contacts, authorizeDataOffer = authorizeOffer)
    private val rtc = WebRtcClient(context, manager)
    private val sendGate = FileRtcSendGate()
    private val connectionDeadline = FileRtcConnectionDeadline(manager, scope)
    private var prepareJob: Job? = null
    private val retryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var idleJob: Job? = null
    @Volatile private var lastActivity = 0L

    init {
        rtc.onMediaFrameReceived = { relationship, frame ->
            lastActivity = android.os.SystemClock.elapsedRealtime()
            acceptFrame(relationship, frame)
        }
        manager.callEventListener = object : CallEventListener {
            override fun onCreateOffer(session: CallSession) {
                prepare(session) {
                    rtc.createOffer { sdp -> scope.launch { manager.onLocalOfferReady(session.callId, sdp) } }
                }
            }
            override fun onIncomingCall(session: CallSession, sdpOffer: String) {
                if (session.type != CallType.DATA) {
                    scope.launch { manager.declineCall(session.callId) }
                    return
                }
                prepare(session) {
                    rtc.createAnswer(sdpOffer) { sdp ->
                        scope.launch {
                            val active = manager.activeCall.value
                            if (active?.callId == session.callId && active.state == CallState.INCOMING_RINGING)
                                manager.onLocalAnswerReady(session.callId, sdp)
                        }
                    }
                }
            }
            override fun onCreateAnswer(session: CallSession) {}
            override fun onRemoteAnswer(session: CallSession, sdpAnswer: String) { rtc.setRemoteAnswer(sdpAnswer) }
            override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
                rtc.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
            override fun onCallConnected(session: CallSession) {
                connectionDeadline.cancel()
                startIdleWatch(session.callId)
            }
            override fun onCallEnded(session: CallSession) {
                connectionDeadline.cancel()
                prepareJob?.cancel()
                prepareJob = null
                idleJob?.cancel()
                rtc.release()
            }
            override fun onRestartRequested(session: CallSession) {
                rtc.createOffer(iceRestart = true) { sdp -> scope.launch { manager.onLocalRestartOfferReady(session.callId, sdp) } }
            }
            override fun onRestartOffer(session: CallSession, sdpOffer: String) {
                rtc.createAnswer(sdpOffer) { sdp -> scope.launch { manager.onLocalRestartAnswerReady(session.callId, sdp) } }
            }
            override fun onMuteChanged(muted: Boolean) {}
            override fun onSpeakerChanged(speaker: Boolean) {}
            override fun onCameraChanged(enabled: Boolean) {}
            override fun onSwitchCamera() {}
        }
    }

    private fun prepare(session: CallSession, action: () -> Unit) {
        connectionDeadline.start(session.callId)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            try {
                val relayOnly = relayOnlyProvider()
                val active = manager.activeCall.value
                if (active?.callId != session.callId || active.state !in setOf(
                        CallState.OUTGOING_PREPARING, CallState.INCOMING_RINGING)) return@launch
                rtc.initialize()
                rtc.createPeerConnection(session.callId, relayOnly)
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                prepareJob = null
                manager.onCallFailed(session.callId, "File connection is unavailable; using Tor")
            }
        }
    }

    private fun startIdleWatch(callId: String) {
        idleJob?.cancel()
        lastActivity = android.os.SystemClock.elapsedRealtime()
        idleJob = scope.launch {
            while (isActive) {
                delay(30_000)
                if (android.os.SystemClock.elapsedRealtime() - lastActivity >= 120_000) {
                    idleJob = null
                    manager.hangUp(callId)
                    return@launch
                }
            }
        }
    }

    suspend fun send(queue: String, frame: ByteArray): Boolean = sendGate.sendWhenIdle {
        if (relayOnlyProvider() && com.torxone.app.BuildConfig.TORX_TURN_URLS.isBlank()) return@sendWhenIdle false
        val connection = connectionForQueue(queue) ?: return@sendWhenIdle false
        val now = android.os.SystemClock.elapsedRealtime()
        var session = manager.activeCall.value
        if (session?.state != CallState.CONNECTED && (retryAfter[queue] ?: 0) > now) return@sendWhenIdle false
        if (session == null) {
            if ((retryAfter[queue] ?: 0) > now) return@sendWhenIdle false
            val contact = contacts.getByRelationshipId(connection.relationshipId) ?: return@sendWhenIdle false
            session = withContext(Dispatchers.Main.immediate) {
                manager.startOutgoingCall(contact.conversationId, connection.relationshipId, contact.remoteIdentityId, CallType.DATA)
            } ?: return@sendWhenIdle false
        }
        if (session.relationshipId != connection.relationshipId) return@sendWhenIdle false
        val callId = session.callId
        lastActivity = now
        val ready = withTimeoutOrNull(8_000) {
            manager.activeCall.first { it?.callId != callId || it.state in setOf(CallState.CONNECTED, CallState.FAILED, CallState.ENDED, CallState.BUSY) }
        }
        if (ready?.callId != callId || ready.state != CallState.CONNECTED) {
            retryAfter[queue] = android.os.SystemClock.elapsedRealtime() + 60_000
            return@sendWhenIdle false
        }
        val accepted = rtc.sendDedicatedMedia(connection.relationshipId, frame)
        if (accepted) retryAfter.remove(queue)
        accepted
    }
}
