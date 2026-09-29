package com.torxone.app.transport.halow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface HaLowGatewayDiscovery {
    val candidates: Flow<HaLowGatewayCandidate>
    suspend fun start()
    suspend fun stop()
}

interface HaLowGatewayLink {
    val incomingFrames: Flow<ByteArray>
    suspend fun connect(candidate: HaLowGatewayCandidate): HaLowGatewayCapabilities
    suspend fun send(frame: ByteArray): Boolean
    suspend fun disconnect()
}

fun interface HaLowGatewayLinkFactory { fun create(candidate: HaLowGatewayCandidate): HaLowGatewayLink }

class HaLowGatewayManager(
    private val scope: CoroutineScope,
    private val discovery: HaLowGatewayDiscovery,
    private val linkFactory: HaLowGatewayLinkFactory,
    private val isTrusted: (String, ByteArray) -> Boolean,
    private val saveTrust: (String, ByteArray) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow<HaLowConnectionState>(HaLowConnectionState.Idle)
    val state: StateFlow<HaLowConnectionState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val incomingFrames = _incoming.asSharedFlow()
    private var discoveryJob: Job? = null
    private var receiveJob: Job? = null
    private var link: HaLowGatewayLink? = null
    private var pending: Pair<HaLowGatewayCandidate, HaLowGatewayCapabilities>? = null
    private var connecting = false

    suspend fun start() {
        if (discoveryJob != null) return
        _state.value = HaLowConnectionState.Discovering
        discoveryJob = scope.launch {
            discovery.candidates.collect { if (link == null && !connecting) connect(it) }
        }
        discovery.start()
    }

    suspend fun connect(candidate: HaLowGatewayCandidate) {
        if (link != null || connecting) return
        connecting = true
        _state.value = HaLowConnectionState.Connecting(candidate)
        val newLink = linkFactory.create(candidate)
        try {
            val capabilities = newLink.connect(candidate)
            if (!isTrusted(capabilities.gatewayId, capabilities.identityKey)) {
                discovery.stop(); newLink.disconnect(); pending = candidate to capabilities
                _state.value = HaLowConnectionState.NeedsPairing(capabilities)
                return
            }
            link = newLink
            receiveJob = scope.launch { newLink.incomingFrames.collect { _incoming.emit(it.copyOf()) } }
            discovery.stop()
            _state.value = HaLowConnectionState.Ready(capabilities)
        } catch (error: Throwable) {
            runCatching { newLink.disconnect() }
            _state.value = HaLowConnectionState.Failed(error.message ?: "HaLow gateway connection failed")
        } finally { connecting = false }
    }

    suspend fun approvePairing() {
        val value = pending ?: error("No HaLow gateway is waiting for pairing")
        saveTrust(value.second.gatewayId, value.second.identityKey); pending = null; connect(value.first)
    }

    suspend fun send(frame: ByteArray): Boolean = link?.send(frame) == true
}
