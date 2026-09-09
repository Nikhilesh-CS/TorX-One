package com.torxone.app.call

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CallManager(
    private val context: Context,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter,
    enginesOverride: List<CallEngine>? = null,
    scopeOverride: CoroutineScope? = null,
    ringtoneManagerOverride: CallRingtoneManager? = null
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RING_TIMEOUT_MS = 45_000L
        private const val CONNECT_ESTABLISHMENT_TIMEOUT_MS = 25_000L
        private const val TERMINATED_CALL_TTL_MS = 2 * 60 * 1000L
    }

    val stateStore = CallStateStore()
    val callAudioManager: CallAudioManager = CallAudioManager(context)
    val audioRouteManager: AudioRouteManager by lazy { AudioRouteManager(context) }
    val ringtoneManager: CallRingtoneManager = ringtoneManagerOverride ?: CallRingtoneManager(context)
    private val scope = scopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signaling = CallSignalingHandler(messageRouter, db, scope)
    private val permissions = AudioVideoPermissionManager(context)

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var toneGenerator: ToneGenerator? = null
    private var ringbackJob: Job? = null

    private val webRtcEngine by lazy { WebRtcCallEngine(context, signaling, stateStore, callAudioManager) }
    private val voiceNoteEngine by lazy { VoiceNoteCallEngine(messageRouter) }
    private val engines: List<CallEngine> = enginesOverride ?: listOf(webRtcEngine, voiceNoteEngine)
    private val adaptiveRouter = AdaptiveCallRouter(messageRouter, engines)

    private var activeEngine: CallEngine? = null
    private var activeCallId: String? = null
    private var activePeerKey: String? = null
    private var activeMode: CallMode = CallMode.AUDIO
    private var pendingOffer: AstraSessionDescription? = null
    private var durationJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var callStartMonotonicMs: Long = 0L
    private val terminatedCalls = ConcurrentHashMap<String, Long>()
    @Volatile
    private var callGeneration: Long = 0L
    private data class SignalOrderingState(var generation: Long = 0L, var lastSeq: Int = 0)
    private val signalLocks = ConcurrentHashMap<String, Mutex>()
    private val signalOrderingStates = ConcurrentHashMap<String, SignalOrderingState>()
    private val earlyIceCandidates = ConcurrentHashMap<String, MutableList<AstraIceCandidate>>()
    private val processedSignalIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val outOfOrderSignals = ConcurrentHashMap<String, java.util.concurrent.ConcurrentSkipListMap<Int, Pair<String, CallSignal>>>()

    private fun rememberSignalId(signalId: String) {
        synchronized(processedSignalIds) {
            if (processedSignalIds.size >= 1000) {
                val it = processedSignalIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            processedSignalIds.add(signalId)
        }
    }

    private var activeDiagnostics: CallConnectionDiagnostics? = null
    private var transportSession: CallTransportSession? = null
    private val networkMonitor = CallNetworkMonitor(context) {
        (activeEngine as? WebRtcCallEngine)?.triggerNetworkHandover()
    }

    private fun acquireWakeLocks() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "torxone:call_active"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
                Log.d(TAG, "Acquired PARTIAL_WAKE_LOCK for call")
            }
            if (proximityWakeLock == null && powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "torxone:call_proximity"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
                Log.d(TAG, "Acquired PROXIMITY_SCREEN_OFF_WAKE_LOCK for call")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            proximityWakeLock?.let {
                if (it.isHeld) it.release()
            }
            proximityWakeLock = null
            Log.d(TAG, "Released wake locks for call")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks", e)
        }
    }

    private fun startRingbackTone() {
        stopRingbackTone()
        ringbackJob = scope.launch(Dispatchers.Default) {
            try {
                val generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                toneGenerator = generator
                while (isActive) {
                    generator.startTone(ToneGenerator.TONE_SUP_RINGTONE, 2000)
                    delay(4000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed playing ringback tone", e)
            }
        }
    }

    private fun stopRingbackTone() {
        ringbackJob?.cancel()
        ringbackJob = null
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (e: Exception) {}
        toneGenerator = null
    }

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
                scope.launch {
                    messageRouter.sendMessage(peerKey, "📞 Outgoing call (Peer offline)", replyToId = null)
                }
                return@launch
            }
            val callId = UUID.randomUUID().toString()
            val generation = ++callGeneration
            activeCallId = callId
            activePeerKey = peerKey
            val diagnostics = CallConnectionDiagnostics(callId, CallDirection.OUTGOING, peerKey)
            activeDiagnostics = diagnostics
            val session = runCatching { messageRouter.openCallTransportSession(callId, contact, routeContext.transport) }.getOrNull()
            transportSession = session
            signaling.activeSession = session
            acquireWakeLocks()
            startRingbackTone()
            stateStore.update(CallUiState.Ringing(callId, peerKey, contact.name, CallDirection.OUTGOING, CallMode.AUDIO))
            startRingTimeout()

            val selected = adaptiveRouter.selectAudioEngine(routeContext)
            if (selected == null) {
                stopRingbackTone()
                releaseWakeLocks()
                session?.close()
                transportSession = null
                signaling.activeSession = null
                diagnostics.markFailed("No compatible engine")
                stateStore.update(CallUiState.Unavailable("No compatible call engine is available for ${routeContext.transport}."))
                return@launch
            }

            if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                Log.d(TAG, "Call $callId cancelled before startOutgoing (gen $generation vs $callGeneration)")
                session?.close()
                return@launch
            }

            signaling.resetSequence(callId)
            activeEngine = selected
            (selected as? WebRtcCallEngine)?.diagnostics = diagnostics
            (selected as? WebRtcCallEngine)?.currentGeneration = generation
            val result = selected.startOutgoing(callId, contact, routeContext)
            if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                Log.d(TAG, "Call $callId cancelled during startOutgoing (gen $generation vs $callGeneration)")
                selected.end()
                session?.close()
                return@launch
            }
            handleStartResult(result, callId, peerKey, contact.name, selected.capabilities.type, generation, selected)
        }
    }

    fun acceptIncomingCall() {
        ringtoneManager.stop()
        cancelRingTimeout()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
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
            stateStore.update(CallUiState.Accepted(callId, peerKey, contact.name, activeMode))
            val generation = ++callGeneration
            acquireWakeLocks()
            val routeContext = adaptiveRouter.buildContext(contact)
            val selected = adaptiveRouter.selectAudioEngine(routeContext)
            if (selected == null) {
                releaseWakeLocks()
                stateStore.update(CallUiState.Unavailable("No compatible call engine is available for ${routeContext.transport}."))
                return@launch
            }

            if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                Log.d(TAG, "Call $callId cancelled before acceptIncoming (gen $generation vs $callGeneration)")
                return@launch
            }

            activeEngine = selected
            (selected as? WebRtcCallEngine)?.diagnostics = activeDiagnostics
            (selected as? WebRtcCallEngine)?.currentGeneration = generation
            val result = selected.acceptIncoming(callId, contact, offer)
            if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                Log.d(TAG, "Call $callId cancelled during acceptIncoming (gen $generation vs $callGeneration)")
                selected.end()
                return@launch
            }
            val early = earlyIceCandidates.remove(callId)
            if (!early.isNullOrEmpty()) {
                Log.d(TAG, "Draining ${early.size} early ICE candidates for call $callId")
                for (cand in early) {
                    selected.handleIceCandidate(cand)
                }
            }
            handleStartResult(result, callId, peerKey, contact.name, selected.capabilities.type, generation, selected)
        }
    }

    fun rejectIncomingCall() {
        ringtoneManager.stop()
        cancelRingTimeout()
        stopRingbackTone()
        releaseWakeLocks()
        endCall("Call declined")
    }

    fun endCall(reason: String = "Call ended") {
        ringtoneManager.stop()
        callGeneration++
        cancelRingTimeout()
        cancelConnectEstablishmentTimeout()
        networkMonitor.stop()
        transportSession?.close()
        transportSession = null
        signaling.activeSession = null
        activeDiagnostics?.let {
            if (stateStore.state.value !is CallUiState.Connected) {
                it.markFailed(reason)
            }
        }
        activeDiagnostics = null
        stopRingbackTone()
        releaseWakeLocks()
        callAudioManager.stopCallAudio()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        com.torxone.app.service.NotificationHelper.clearOngoingCall(context)
        stopDurationTimer()
        val duration = if (callStartMonotonicMs > 0) {
            ((android.os.SystemClock.elapsedRealtime() - callStartMonotonicMs) / 1000).toInt()
        } else 0
        val endingCallId = activeCallId
        val endingPeerKey = activePeerKey
        if (endingCallId != null) {
            rememberTerminated(endingCallId)
            earlyIceCandidates.remove(endingCallId)
            outOfOrderSignals.remove(endingCallId)
            signalOrderingStates.remove(endingCallId)
            signaling.clearCallSignals(endingCallId)
            if (endingPeerKey != null) {
                scope.launch { signaling.sendEnd(endingPeerKey, endingCallId, activeMode, reason, callGeneration) }
            }
        }
        activeEngine?.end()
        activeEngine = null
        activeCallId = null
        activePeerKey = null
        pendingOffer = null
        callStartMonotonicMs = 0L
        stateStore.update(CallUiState.Ended(reason, duration))
        scheduleEndedReset()
    }

    fun toggleMute() {
        val muted = callAudioManager.toggleMute()
        (activeEngine as? WebRtcCallEngine)?.setMicEnabled(!muted)
        stateStore.updateConnectedState(isMuted = muted)
    }

    fun toggleSpeaker() {
        val speaker = callAudioManager.toggleSpeaker()
        stateStore.updateConnectedState(isSpeaker = speaker)
    }

    fun handleSignal(packetType: String, rawPayload: String, senderKey: String) {
        scope.launch {
            runCatching {
                val signal = signaling.parse(rawPayload)
                val callLock = signalLocks.computeIfAbsent(signal.callId) { Mutex() }
                callLock.withLock { handleSignalLocked(packetType, signal, senderKey) }
            }.onFailure { e ->
                if (e is SecurityException) Log.w(TAG, "Rejected call signal $packetType: ${e.message}")
                else Log.e(TAG, "Failed to handle call signal $packetType", e)
            }
        }
    }

    private suspend fun handleSignalLocked(packetType: String, signal: CallSignal, senderKey: String) {
        if (packetType == com.torxone.app.network.MeshProtocol.TYPE_CALL_ACK) {
            signaling.handleAck(signal)
            return
        }

        val callId = signal.callId
        if (isTerminated(callId)) {
            Log.d(TAG, "Signal $packetType received for terminated call $callId, re-ACKing and ignoring")
            signaling.sendAck(senderKey, callId, signal.signalId, signal.generation, signal.seq, "TERMINATED_ACK")
            return
        }

        val duplicate = synchronized(processedSignalIds) { processedSignalIds.contains(signal.signalId) }
        if (duplicate) {
            val ackType = when (packetType) {
                com.torxone.app.network.MeshProtocol.TYPE_CALL_OFFER -> "OFFER_ACK"
                com.torxone.app.network.MeshProtocol.TYPE_CALL_ANSWER -> "ANSWER_ACK"
                com.torxone.app.network.MeshProtocol.TYPE_ICE_CANDIDATE -> "ICE_ACK"
                com.torxone.app.network.MeshProtocol.TYPE_CALL_END -> "END_ACK"
                else -> "CALL_ACK"
            }
            signaling.sendAck(senderKey, callId, signal.signalId, signal.generation, signal.seq, ackType)
            return
        }

        val ordering = signalOrderingStates.computeIfAbsent(callId) { SignalOrderingState() }
        if (signal.generation > 0L && signal.generation < ordering.generation) {
            signaling.sendAck(senderKey, callId, signal.signalId, signal.generation, signal.seq, "STALE_GEN_ACK")
            return
        }
        if (signal.generation > ordering.generation) {
            ordering.generation = signal.generation
            ordering.lastSeq = 0
            outOfOrderSignals.remove(callId)
        }

        if (packetType == com.torxone.app.network.MeshProtocol.TYPE_CALL_END) {
            rememberSignalId(signal.signalId)
            ordering.lastSeq = maxOf(ordering.lastSeq, signal.seq)
            handleRemoteEnd(signal, senderKey)
            return
        }

        if (signal.generation == ordering.generation && signal.seq > 0) {
            if (signal.seq <= ordering.lastSeq) {
                signaling.sendAck(senderKey, callId, signal.signalId, signal.generation, signal.seq, "DUP_SEQ_ACK")
                return
            }
            if (signal.seq > ordering.lastSeq + 1) {
                val buffer = outOfOrderSignals.getOrPut(callId) { java.util.concurrent.ConcurrentSkipListMap() }
                if (buffer.size < 100) buffer[signal.seq] = Pair(packetType, signal)
                return
            }
        }

        processSignal(packetType, signal, senderKey)
        val buffer = outOfOrderSignals[callId]
        if (buffer != null) {
            while (true) {
                val nextSeq = ordering.lastSeq + 1
                val nextEntry = buffer.remove(nextSeq) ?: break
                processSignal(nextEntry.first, nextEntry.second, senderKey)
            }
        }
    }

    private suspend fun processSignal(packetType: String, signal: CallSignal, senderKey: String) {
        rememberSignalId(signal.signalId)
        if (signal.seq > 0) {
            signalOrderingStates.computeIfAbsent(signal.callId) { SignalOrderingState() }.lastSeq = signal.seq
        }
        when (packetType) {
            com.torxone.app.network.MeshProtocol.TYPE_CALL_OFFER -> handleOffer(signal, senderKey)
            com.torxone.app.network.MeshProtocol.TYPE_CALL_ANSWER -> handleAnswer(signal, senderKey)
            com.torxone.app.network.MeshProtocol.TYPE_ICE_CANDIDATE -> handleIce(signal, senderKey)
            com.torxone.app.network.MeshProtocol.TYPE_CALL_END -> handleRemoteEnd(signal, senderKey)
        }
    }

    private suspend fun handleOffer(signal: CallSignal, senderKey: String) {
        if (isTerminated(signal.callId)) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_TERMINATED_CALL_SIGNAL,
                signal.callId, senderKey, "offer for terminated call"
            )
            return
        }

        val normalizedSenderKey = senderKey.trim()
        if (normalizedSenderKey.isBlank()) {
            Log.w(TAG, "Dropping CALL_OFFER with blank sender key for call ${signal.callId}")
            return
        }

        val contact = db.contactDao().getContact(normalizedSenderKey)
        val peerName = contact?.name ?: "Unknown Contact"
        val offer = AstraSessionDescription("offer", signal.sdp ?: return)

        if (activeCallId == signal.callId) {
            if (activePeerKey != null && activePeerKey != normalizedSenderKey) {
                CallSecurityLogger.logSecurityEvent(
                    CallSecurityLogger.EVENT_WRONG_PEER_REJECTED,
                    signal.callId,
                    normalizedSenderKey,
                    "CALL_OFFER peer mismatch for active call"
                )
                return
            }

            val currentState = stateStore.state.value
            val canRenegotiate = (currentState is CallUiState.Connected ||
                currentState is CallUiState.Reconnecting ||
                currentState is CallUiState.IceConnecting ||
                currentState is CallUiState.MediaConnecting ||
                currentState is CallUiState.Accepted) &&
                activeEngine != null &&
                offer.description != pendingOffer?.description

            if (canRenegotiate) {
                Log.d(TAG, "Received renegotiation offer for active call: ${signal.callId}")
                activeDiagnostics?.record("RENEGOTIATION_OFFER_RECEIVED")
                pendingOffer = offer
                activeEngine?.handleRenegotiationOffer(offer, normalizedSenderKey, signal.callId, signal.generation)
                signaling.sendAck(
                    peerKey = normalizedSenderKey,
                    callId = signal.callId,
                    ackSignalId = signal.signalId,
                    generation = signal.generation,
                    seq = signal.seq,
                    ackType = "OFFER_ACK"
                )
            } else {
                Log.d(TAG, "Dropping duplicate CALL_OFFER for active call ${signal.callId}; state=${currentState::class.simpleName}")
                activeDiagnostics?.record("DUPLICATE_OFFER_DROPPED")
                signaling.sendAck(
                    peerKey = normalizedSenderKey,
                    callId = signal.callId,
                    ackSignalId = signal.signalId,
                    generation = signal.generation,
                    seq = signal.seq,
                    ackType = "OFFER_ACK"
                )
            }
            return
        }

        if (activeCallId != null) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_BUSY_COLLISION_DROPPED,
                signal.callId,
                normalizedSenderKey,
                "busy with active call $activeCallId"
            )
            scope.launch {
                runCatching {
                    signaling.sendEnd(normalizedSenderKey, signal.callId, signal.mode, "Busy")
                }
            }
            return
        }

        callGeneration = if (signal.generation > 0L) signal.generation else (callGeneration + 1)
        activeCallId = signal.callId
        activePeerKey = normalizedSenderKey
        activeMode = signal.mode
        pendingOffer = offer

        val diagnostics = CallConnectionDiagnostics(signal.callId, CallDirection.INCOMING, normalizedSenderKey)
        activeDiagnostics = diagnostics
        diagnostics.markOfferReceived()

        if (contact != null) {
            val routeContext = adaptiveRouter.buildContext(contact)
            val session = runCatching { messageRouter.openCallTransportSession(signal.callId, contact, routeContext.transport) }.getOrNull()
            transportSession = session
            signaling.activeSession = session
        }

        acquireWakeLocks()
        startRingTimeout()
        ringtoneManager.start()
        stateStore.update(CallUiState.Ringing(signal.callId, normalizedSenderKey, peerName, CallDirection.INCOMING, signal.mode))
        com.torxone.app.service.NotificationHelper.showIncomingCall(context, signal.callId, normalizedSenderKey, peerName)

        // ACK the OFFER as successfully accepted/registered by signaling layer
        signaling.sendAck(
            peerKey = normalizedSenderKey,
            callId = signal.callId,
            ackSignalId = signal.signalId,
            generation = signal.generation,
            seq = signal.seq,
            ackType = "OFFER_ACK"
        )
    }

    private suspend fun handleAnswer(signal: CallSignal, senderKey: String) {
        if (signal.callId != activeCallId || senderKey != activePeerKey || isTerminated(signal.callId)) return
        cancelRingTimeout()
        stopRingbackTone()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        val contact = db.contactDao().getContact(senderKey)
        val callId = signal.callId
        val answer = AstraSessionDescription("answer", signal.sdp ?: return)
        activeDiagnostics?.markAnswerReceived()
        activeEngine?.handleRemoteDescription(answer)
        val peerName = contact?.name ?: "Unknown Contact"
        stateStore.update(CallUiState.Accepted(callId, senderKey, peerName, signal.mode))
        startConnectEstablishmentTimeout(callId)

        // ACK the ANSWER as successfully accepted and registered
        signaling.sendAck(
            peerKey = senderKey,
            callId = signal.callId,
            ackSignalId = signal.signalId,
            generation = signal.generation,
            seq = signal.seq,
            ackType = "ANSWER_ACK"
        )
    }

    private suspend fun handleIce(signal: CallSignal, senderKey: String) {
        if (signal.callId != activeCallId || isTerminated(signal.callId)) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_STALE_SIGNAL_DISCARDED,
                signal.callId, senderKey, "ICE candidate for wrong/terminated call"
            )
            return
        }
        if (senderKey != activePeerKey) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_WRONG_PEER_REJECTED,
                signal.callId, senderKey, "ICE from wrong peer"
            )
            return
        }
        val candidateText = signal.candidate ?: return
        val mid = signal.sdpMid ?: return
        val index = signal.sdpMLineIndex ?: return
        val candidate = AstraIceCandidate(mid, index, candidateText)

        val engine = activeEngine
        if (engine != null) {
            activeDiagnostics?.markIceCandidateReceived(mid)
            engine.handleIceCandidate(candidate)
        } else {
            Log.d(TAG, "Queueing early ICE candidate for call ${signal.callId} before engine accept")
            val list = earlyIceCandidates.getOrPut(signal.callId) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(list) {
                if (list.size < 100 && list.none { it.sdpMid == mid && it.sdpMLineIndex == index && it.sdp == candidateText }) {
                    list.add(candidate)
                }
            }
        }

        // ACK the ICE candidate as accepted/queued
        signaling.sendAck(
            peerKey = senderKey,
            callId = signal.callId,
            ackSignalId = signal.signalId,
            generation = signal.generation,
            seq = signal.seq,
            ackType = "ICE_ACK"
        )
    }

    private suspend fun handleRemoteEnd(signal: CallSignal, senderKey: String) {
        ringtoneManager.stop()
        if (senderKey != activePeerKey || signal.callId != activeCallId) {
            rememberTerminated(signal.callId)
            earlyIceCandidates.remove(signal.callId)
            outOfOrderSignals.remove(signal.callId)
            signalOrderingStates.remove(signal.callId)
            signaling.clearCallSignals(signal.callId)
            signaling.sendAck(
                peerKey = senderKey,
                callId = signal.callId,
                ackSignalId = signal.signalId,
                generation = signal.generation,
                seq = signal.seq,
                ackType = "END_ACK"
            )
            return
        }
        val isIncomingRinging = (stateStore.state.value as? CallUiState.Ringing)?.direction == CallDirection.INCOMING
        val reason = if (isIncomingRinging) "Caller cancelled" else (signal.reason ?: "Remote ended call")
        if (isIncomingRinging) {
            scope.launch {
                messageRouter.sendMessage(senderKey, "📞 Missed call (Caller cancelled)")
            }
        }
        callGeneration++
        cancelRingTimeout()
        cancelConnectEstablishmentTimeout()
        networkMonitor.stop()
        transportSession?.close()
        transportSession = null
        signaling.activeSession = null
        activeDiagnostics?.let {
            if (stateStore.state.value !is CallUiState.Connected) {
                it.markFailed(reason)
            }
        }
        activeDiagnostics = null
        stopRingbackTone()
        releaseWakeLocks()
        callAudioManager.stopCallAudio()
        com.torxone.app.service.NotificationHelper.clearIncomingCall(context)
        com.torxone.app.service.NotificationHelper.clearOngoingCall(context)
        stopDurationTimer()
        rememberTerminated(signal.callId)
        earlyIceCandidates.remove(signal.callId)
        outOfOrderSignals.remove(signal.callId)
        signalOrderingStates.remove(signal.callId)
        signaling.clearCallSignals(signal.callId)
        activeEngine?.end()
        activeEngine = null
        activeCallId = null
        activePeerKey = null
        pendingOffer = null
        callStartMonotonicMs = 0L
        stateStore.update(CallUiState.Ended(reason, 0))
        scheduleEndedReset()

        // ACK the CALL_END signal
        signaling.sendAck(
            peerKey = senderKey,
            callId = signal.callId,
            ackSignalId = signal.signalId,
            generation = signal.generation,
            seq = signal.seq,
            ackType = "END_ACK"
        )
    }

    private fun scheduleEndedReset() {
        scope.launch {
            delay(1500)
            if (stateStore.state.value is CallUiState.Ended) {
                stateStore.reset()
                verifyCleanup()
            }
        }
    }

    fun verifyCleanup(): Boolean {
        val issues = mutableListOf<String>()
        if (activeEngine != null) issues.add("activeEngine is not null")
        if (transportSession != null) issues.add("transportSession is not null")
        if (activeDiagnostics != null) issues.add("activeDiagnostics is not null")
        if (durationJob?.isActive == true) issues.add("durationJob is still active")
        if (ringTimeoutJob?.isActive == true) issues.add("ringTimeoutJob is still active")
        if (connectTimeoutJob?.isActive == true) issues.add("connectTimeoutJob is still active")
        if (wakeLock?.isHeld == true) issues.add("wakeLock is still held")
        if (proximityWakeLock?.isHeld == true) issues.add("proximityWakeLock is still held")

        return if (issues.isEmpty()) {
            Log.i(TAG, "CLEANUP OK: All call resources successfully released")
            true
        } else {
            Log.w(TAG, "CLEANUP WARNING: ${issues.joinToString(", ")}")
            false
        }
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
        engineType: CallEngineType,
        generation: Long,
        engine: CallEngine
    ) {
        if (
            generation != callGeneration ||
            callId != activeCallId ||
            isTerminated(callId)
        ) {
            Log.d(TAG, "Dropping stale CallStartResult for call $callId (gen $generation vs $callGeneration, active=$activeCallId, terminated=${isTerminated(callId)})")
            engine.end()
            return
        }

        when (result) {
            is CallStartResult.Started -> {
                activeMode = result.mode
                if (result.mode == CallMode.VOICE_NOTE || result.mode == CallMode.WALKIE_TALKIE) {
                    cancelRingTimeout()
                    startDurationTimer()
                    stateStore.update(CallUiState.Connected(callId, peerKey, peerName, result.mode))
                } else {
                    stateStore.update(CallUiState.Negotiating(callId, peerKey, peerName, result.mode))
                    startConnectEstablishmentTimeout(callId)
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
                    if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                        Log.d(TAG, "Dropping fallback before start for call $callId (gen $generation vs $callGeneration)")
                        return@launch
                    }
                    val contact = db.contactDao().getContact(peerKey)
                    if (contact == null) {
                        stateStore.update(CallUiState.Unavailable("Contact not found."))
                        return@launch
                    }
                    if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                        Log.d(TAG, "Dropping fallback after contact fetch for call $callId")
                        return@launch
                    }
                    val routeContext = adaptiveRouter.buildContext(contact)
                    val fallbackResult = fallback.startOutgoing(callId, contact, routeContext)
                    if (generation != callGeneration || callId != activeCallId || isTerminated(callId)) {
                        Log.d(TAG, "Dropping fallback after startOutgoing for call $callId")
                        fallback.end()
                        return@launch
                    }
                    handleStartResult(fallbackResult, callId, peerKey, peerName, fallback.capabilities.type, generation, fallback)
                }
            }
            is CallStartResult.Failed -> {
                stateStore.update(CallUiState.Unavailable(result.reason))
            }
        }
    }

    private fun startDurationTimer() {
        if (callStartMonotonicMs == 0L) {
            callStartMonotonicMs = android.os.SystemClock.elapsedRealtime()
        }
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                val elapsedSec = ((android.os.SystemClock.elapsedRealtime() - callStartMonotonicMs) / 1000L).toInt()
                stateStore.updateConnectedState(durationSeconds = elapsedSec)
                (stateStore.state.value as? CallUiState.Connected)?.let { conn ->
                    com.torxone.app.service.NotificationHelper.showOngoingCall(
                        context, conn.callId, conn.peerKey, conn.peerName, elapsedSec
                    )
                }
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
        callStartMonotonicMs = 0L
    }

    private fun startRingTimeout() {
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            Log.d(TAG, "Ring timeout — ending call")
            ringtoneManager.stop()
            val peerKey = activePeerKey
            val isIncoming = (stateStore.state.value as? CallUiState.Ringing)?.direction == CallDirection.INCOMING
            val reason = if (isIncoming) "Missed call" else "No answer"
            endCall(reason)
            if (peerKey != null && isIncoming) {
                scope.launch {
                    messageRouter.sendMessage(peerKey, "📞 Missed call")
                }
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun startConnectEstablishmentTimeout(callId: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_ESTABLISHMENT_TIMEOUT_MS)
            val currentState = stateStore.state.value
            if (activeCallId == callId &&
                (currentState is CallUiState.Negotiating || currentState is CallUiState.IceConnecting || currentState is CallUiState.Accepted)) {
                Log.w(TAG, "Connection establishment timed out for call $callId")
                activeDiagnostics?.markFailed("Connection establishment timed out")
                endCall("Connection timed out")
            }
        }
    }

    private fun cancelConnectEstablishmentTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    init {
        scope.launch {
            stateStore.state.collect { state ->
                when (state) {
                    is CallUiState.Connected -> {
                        cancelConnectEstablishmentTimeout()
                        if (durationJob == null) {
                            startDurationTimer()
                        }
                        networkMonitor.start()
                        com.torxone.app.service.NotificationHelper.showOngoingCall(
                            context, state.callId, state.peerKey, state.peerName, state.callDurationSeconds
                        )
                    }
                    is CallUiState.Reconnecting -> {
                        com.torxone.app.service.NotificationHelper.showOngoingCall(
                            context, state.callId, state.peerKey, state.peerName, state.callDurationSeconds
                        )
                    }
                    is CallUiState.Ended,
                    is CallUiState.Idle,
                    is CallUiState.Unavailable -> {
                        com.torxone.app.service.NotificationHelper.clearOngoingCall(context)
                    }
                    else -> {}
                }
            }
        }
    }
}
