package com.torxone.app.media

import android.content.Context
import com.torxone.app.calls.*
import com.torxone.app.connection.Connection
import com.torxone.app.data.dao.ContactDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutex = Mutex()
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
                    rtc.createAnswer(sdpOffer) { sdp -> scope.launch { manager.onLocalAnswerReady(session.callId, sdp) } }
                }
            }
            override fun onCreateAnswer(session: CallSession) {}
            override fun onRemoteAnswer(session: CallSession, sdpAnswer: String) { rtc.setRemoteAnswer(sdpAnswer) }
            override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
                rtc.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
            override fun onCallConnected(session: CallSession) { startIdleWatch(session.callId) }
            override fun onCallEnded(session: CallSession) { idleJob?.cancel(); rtc.release() }
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
        scope.launch {
        try {
            val relayOnly = relayOnlyProvider()
            if (manager.activeCall.value?.callId != session.callId) return@launch
            rtc.initialize()
            rtc.createPeerConnection(session.callId, relayOnly)
            action()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
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

    suspend fun send(queue: String, frame: ByteArray): Boolean = mutex.withLock {
        if (relayOnlyProvider() && com.torxone.app.BuildConfig.TORX_TURN_URLS.isBlank()) return@withLock false
        val connection = connectionForQueue(queue) ?: return@withLock false
        val now = android.os.SystemClock.elapsedRealtime()
        var session = manager.activeCall.value
        if (session?.state != CallState.CONNECTED && (retryAfter[queue] ?: 0) > now) return@withLock false
        if (session == null) {
            if ((retryAfter[queue] ?: 0) > now) return@withLock false
            val contact = contacts.getByRelationshipId(connection.relationshipId) ?: return@withLock false
            session = withContext(Dispatchers.Main.immediate) {
                manager.startOutgoingCall(contact.conversationId, connection.relationshipId, contact.remoteIdentityId, CallType.DATA)
            } ?: return@withLock false
        }
        if (session.relationshipId != connection.relationshipId) return@withLock false
        val callId = session.callId
        lastActivity = now
        val ready = withTimeoutOrNull(8_000) {
            manager.activeCall.first { it?.callId != callId || it.state in setOf(CallState.CONNECTED, CallState.FAILED, CallState.ENDED, CallState.BUSY) }
        }
        if (ready?.callId != callId || ready.state != CallState.CONNECTED) {
            retryAfter[queue] = android.os.SystemClock.elapsedRealtime() + 60_000
            return@withLock false
        }
        val accepted = rtc.sendDedicatedMedia(connection.relationshipId, frame)
        if (accepted) retryAfter.remove(queue)
        accepted
    }
}
