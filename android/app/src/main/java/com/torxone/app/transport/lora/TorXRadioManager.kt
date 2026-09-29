package com.torxone.app.transport.lora

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface TorXRadioDiscovery {
    val candidates: Flow<TorXRadioCandidate>
    suspend fun start()
    suspend fun stop()
}

interface TorXRadioLink {
    val method: RadioConnectionMethod
    val incomingFrames: Flow<ByteArray>
    suspend fun connect(candidate: TorXRadioCandidate): TorXRadioCapabilities
    suspend fun send(frame: ByteArray): Boolean
    suspend fun disconnect()
}

fun interface TorXRadioLinkFactory { fun create(candidate: TorXRadioCandidate): TorXRadioLink }

/** Owns discovery, protocol recognition, trust, automatic reconnect and the live phone-radio link. */
class TorXRadioManager(
    private val scope: CoroutineScope,
    private val discovery: TorXRadioDiscovery,
    private val linkFactory: TorXRadioLinkFactory,
    private val isTrustedIdentity: (deviceId: String, identityKey: ByteArray) -> Boolean,
    private val trustIdentity: (deviceId: String, identityKey: ByteArray) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow<RadioConnectionState>(RadioConnectionState.Idle)
    val state: StateFlow<RadioConnectionState> = _state.asStateFlow()
    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames = _incomingFrames.asSharedFlow()
    private var discoveryJob: Job? = null
    private var incomingJob: Job? = null
    private var activeLink: TorXRadioLink? = null
    private var connectingId: String? = null
    private var pendingPairing: Pair<TorXRadioCandidate, TorXRadioCapabilities>? = null

    suspend fun start() {
        if (discoveryJob != null) return
        _state.value = RadioConnectionState.Scanning
        discoveryJob = scope.launch {
            discovery.candidates.collect { candidate ->
                if (candidate.looksLikeTorXRadio() && activeLink == null && connectingId == null) connect(candidate)
            }
        }
        discovery.start()
    }

    suspend fun stop() {
        discovery.stop(); discoveryJob?.cancel(); discoveryJob = null
        incomingJob?.cancel(); incomingJob = null
        activeLink?.disconnect(); activeLink = null; connectingId = null
        _state.value = RadioConnectionState.Idle
    }

    suspend fun connect(candidate: TorXRadioCandidate) {
        if (activeLink != null || connectingId != null) return
        connectingId = candidate.stableId
        _state.value = RadioConnectionState.Recognized(candidate)
        val link = linkFactory.create(candidate)
        _state.value = RadioConnectionState.Connecting(candidate)
        try {
            val capabilities = link.connect(candidate)
            if (!isTrustedIdentity(capabilities.deviceId, capabilities.identityKey)) {
                discovery.stop()
                link.disconnect()
                pendingPairing = candidate to capabilities
                _state.value = RadioConnectionState.NeedsPairing(capabilities)
                return
            }
            activeLink = link
            incomingJob = scope.launch { link.incomingFrames.collect { _incomingFrames.emit(it.copyOf()) } }
            _state.value = RadioConnectionState.Ready(capabilities, link.method)
            discovery.stop()
        } catch (error: Throwable) {
            runCatching { link.disconnect() }
            _state.value = RadioConnectionState.Failed(error.message ?: "Radio connection failed")
        } finally {
            connectingId = null
        }
    }

    /** Called after the user verifies the physical radio during its one-time pairing flow. */
    suspend fun approvePairing() {
        val pending = pendingPairing ?: error("No TorX Radio is waiting for pairing")
        trustIdentity(pending.second.deviceId, pending.second.identityKey)
        pendingPairing = null
        connect(pending.first)
    }

    suspend fun send(frame: ByteArray): Boolean = activeLink?.send(frame) == true
}
