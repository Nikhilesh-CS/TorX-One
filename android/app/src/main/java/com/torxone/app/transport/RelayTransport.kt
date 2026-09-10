package com.torxone.app.transport

import android.util.Log
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.SettingsManager
import com.torxone.app.identity.IdentityManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TorX One 2.0 — Offline Relay Transport
 *
 * Connects to a TorX zero-knowledge store-and-forward relay server
 * (relay/server.js) over WebSocket.
 *
 * Key features:
 * 1. Ed25519 challenge-response cryptographic authentication using local identity
 * 2. Zero-knowledge buffering: relay only routes by recipient public key and holds encrypted blobs
 * 3. Automatic queue flushing when device connects
 * 4. Priority 6 fallback when direct Nearby or direct Tor connections are unavailable
 * 5. Dynamic reconnection with exponential backoff
 */
class RelayTransport(
    private val identityManager: IdentityManager,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope
) : Transport {

    companion object {
        private const val TAG = "RelayTransport"
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    override val name: String = "Offline Relay"
    override val type: TransportType = TransportType.OFFLINE_RELAY

    var connectionManager: com.torxone.app.connection.ConnectionManager? = null

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _statusText = MutableStateFlow("Disconnected")
    override val statusText: StateFlow<String> = _statusText

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websocket
        .build()

    private var webSocket: WebSocket? = null
    private var incomingListener: TransportIncomingListener? = null
    private var isStarted = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var settingsJob: Job? = null

    private var currentUrl: String = SettingsManager.DEFAULT_RELAY_URL
    private var isEnabled: Boolean = true

    // Tracks in-flight sends awaiting {"type":"sent", "id": "..."}
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override fun start() {
        if (isStarted) return
        isStarted = true
        Log.i(TAG, "[START] Starting Offline Relay transport")

        // Listen for setting changes
        settingsJob = scope.launch(Dispatchers.IO) {
            launch {
                settingsManager.relayEnabledFlow.collectLatest { enabled ->
                    isEnabled = enabled
                    if (!enabled) {
                        Log.i(TAG, "[SETTINGS] Relay disabled by user")
                        disconnect()
                    } else if (isStarted && webSocket == null) {
                        connect()
                    }
                }
            }
            launch {
                settingsManager.relayServerUrlFlow.collectLatest { url ->
                    val clean = url.trim()
                    if (clean != currentUrl) {
                        currentUrl = clean
                        Log.i(TAG, "[SETTINGS] Relay URL updated: $clean")
                        if (isEnabled && isStarted) {
                            disconnect()
                            connect()
                        }
                    }
                }
            }
        }

        connect()
    }

    override fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.i(TAG, "[STOP] Stopping Offline Relay transport")
        settingsJob?.cancel()
        settingsJob = null
        disconnect()
    }

    override fun setIncomingListener(listener: TransportIncomingListener?) {
        this.incomingListener = listener
    }

    override fun getReachablePeers(): Set<String> {
        // Relay can reach any known peer key as long as connected
        return emptySet()
    }

    override suspend fun send(
        destination: String,
        payload: String,
        metadata: TransportMetadata?
    ): TransportResult {
        val ws = webSocket
        if (!isAvailable.value || ws == null) {
            return TransportResult.failure(type, "Relay not connected")
        }

        val sendId = metadata?.envelopeId ?: UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingAcks[sendId] = deferred

        val startNs = System.nanoTime()

        return try {
            val targetQueue = destination.trim()
            val msg = JSONObject().apply {
                put("type", "message")
                put("queueId", targetQueue)
                put("to", targetQueue) // backward compatibility with legacy relay inspect
                put("payload", payload)
                // Also provide ciphertext hex for backward compatibility with older relay/inspect tools
                put("ciphertext", CryptoManager.toHex(payload.toByteArray(Charsets.UTF_8)))
                put("nonce", "")
            }

            val enqueued = ws.send(msg.toString())
            if (!enqueued) {
                pendingAcks.remove(sendId)
                return TransportResult.failure(type, "WebSocket queue full or failed")
            }

            val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) {
                deferred.await()
            }

            if (acked == true) {
                val latency = (System.nanoTime() - startNs) / 1_000_000
                Log.i(TAG, "[SEND] Relayed to ${destination.take(12)}… via relay (${latency}ms)")
                TransportResult.success(type, latency)
            } else {
                TransportResult.failure(type, "Relay ack timeout (${ACK_TIMEOUT_MS}ms)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SEND] Error sending via relay: ${e.message}", e)
            TransportResult.failure(type, e.message ?: "Relay send error")
        } finally {
            pendingAcks.remove(sendId)
        }
    }

    private fun connect() {
        if (!isEnabled || !isStarted) return
        reconnectJob?.cancel()

        val identity = identityManager.loadIdentity()
        if (identity == null) {
            _statusText.value = "Waiting for identity"
            Log.d(TAG, "[CONNECT] Postponing relay connection until identity is loaded")
            return
        }

        val url = currentUrl.ifBlank { SettingsManager.DEFAULT_RELAY_URL }
        _statusText.value = "Connecting..."
        Log.i(TAG, "[CONNECT] Connecting to relay: $url")

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Log.e(TAG, "[CONNECT] Invalid relay URL: $url", e)
            _statusText.value = "Invalid URL"
            return
        }

        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    private fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "App closed connection")
        } catch (e: Exception) {
            Log.w(TAG, "[DISCONNECT] Error closing websocket: ${e.message}")
        }
        webSocket = null
        _isAvailable.value = false
        _statusText.value = if (isEnabled) "Disconnected" else "Disabled"
        pendingAcks.values.forEach { it.complete(false) }
        pendingAcks.clear()
    }

    private fun scheduleReconnect() {
        if (!isEnabled || !isStarted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            reconnectAttempt++
            val backoff = (1_000L * (1L shl reconnectAttempt.coerceAtMost(5))).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            _statusText.value = "Reconnecting in ${backoff / 1000}s"
            Log.d(TAG, "[RECONNECT] Reconnecting in ${backoff}ms (attempt $reconnectAttempt)")
            delay(backoff)
            if (isStarted && isEnabled) {
                connect()
            }
        }
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "[WS] Connected to relay, awaiting auth challenge")
                _statusText.value = "Authenticating..."
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[WS] Closing: $code $reason")
                _isAvailable.value = false
                _statusText.value = "Closing"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[WS] Closed: $code $reason")
                _isAvailable.value = false
                _statusText.value = "Disconnected"
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "[WS] Failure: ${t.message}")
                _isAvailable.value = false
                _statusText.value = "Connection error"
                scheduleReconnect()
            }
        }
    }

    private fun handleRelayMessage(ws: WebSocket, text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "[WS] Dropped malformed message from relay: $text")
            return
        }

        when (val msgType = json.optString("type")) {
            "challenge" -> {
                handleChallenge(ws, json)
            }
            "auth_ok" -> {
                handleAuthOk()
            }
            "message" -> {
                handleIncomingRelayedMessage(ws, json)
            }
            "sent" -> {
                val id = json.optString("id")
                Log.d(TAG, "[WS] Message send acknowledged by relay: $id")
                // Complete any pending send waiting for ack
                pendingAcks.remove(id)?.complete(true)
                    ?: pendingAcks.values.firstOrNull()?.complete(true)
            }
            "error" -> {
                val errorMsg = json.optString("message", "Unknown relay error")
                Log.w(TAG, "[WS] Relay returned error: $errorMsg")
            }
            else -> {
                Log.d(TAG, "[WS] Unhandled message type: $msgType")
            }
        }
    }

    private fun handleChallenge(ws: WebSocket, json: JSONObject) {
        val nonceHex = json.optString("nonce")
        if (nonceHex.isBlank()) {
            Log.e(TAG, "[AUTH] Missing nonce in challenge")
            return
        }

        val identity = identityManager.loadIdentity()
        if (identity == null) {
            Log.e(TAG, "[AUTH] No identity available to sign challenge")
            return
        }

        try {
            val nonceBytes = CryptoManager.fromHex(nonceHex)
            val signature = CryptoManager.sign(nonceBytes, identity.signingSecretKey)
            val myPubKeyHex = CryptoManager.toHex(identity.signingPublicKey)

            val authMessage = JSONObject().apply {
                put("type", "auth")
                put("publicKey", myPubKeyHex)
                put("signature", CryptoManager.toHex(signature))
            }

            ws.send(authMessage.toString())
            Log.d(TAG, "[AUTH] Dispatched auth response for ${myPubKeyHex.take(12)}…")
        } catch (e: Exception) {
            Log.e(TAG, "[AUTH] Failed to sign challenge: ${e.message}", e)
        }
    }

    private fun handleAuthOk() {
        reconnectAttempt = 0
        _isAvailable.value = true
        _statusText.value = "Connected"
        Log.i(TAG, "[AUTH] Successfully authenticated with relay.")
        scope.launch(Dispatchers.IO) {
            syncActiveSubscriptions()
        }
    }

    /**
     * Subscribe to a specific queue ID on the connected relay.
     */
    fun subscribeQueue(queueId: String) {
        val ws = webSocket
        if (ws != null && _isAvailable.value) {
            val clean = queueId.trim()
            if (clean.isNotBlank()) {
                val msg = JSONObject().apply {
                    put("type", "subscribe")
                    put("queueId", clean)
                }
                ws.send(msg.toString())
                Log.d(TAG, "[WS] Subscribed to queue: ${clean.take(8)}…")
            }
        }
    }

    /**
     * Sync active receive queue subscriptions with the relay.
     */
    suspend fun syncActiveSubscriptions() {
        val ws = webSocket ?: return
        if (!_isAvailable.value) return
        val manager = connectionManager ?: return
        val queues = manager.getActiveRecvQueueIds()
        if (queues.isNotEmpty()) {
            val msg = JSONObject().apply {
                put("type", "subscribe")
                put("queues", org.json.JSONArray(queues))
            }
            ws.send(msg.toString())
            Log.i(TAG, "[WS] Subscribed to ${queues.size} active receive queue(s)")
        }
    }

    private fun handleIncomingRelayedMessage(ws: WebSocket, json: JSONObject) {
        val id = json.optString("id")
        val queueId = json.optString("queueId")
        val payload = json.optString("payload")
        val ciphertextHex = json.optString("ciphertext")

        // Acknowledge receipt back to relay server so it doesn't hold it
        if (id.isNotBlank()) {
            val ackJson = JSONObject().apply {
                put("type", "ack")
                put("id", id)
                if (queueId.isNotBlank()) put("queueId", queueId)
            }
            ws.send(ackJson.toString())
        }

        // Extract the envelope JSON string
        val wireString = if (payload.isNotBlank()) {
            payload
        } else if (ciphertextHex.isNotBlank()) {
            try {
                String(CryptoManager.fromHex(ciphertextHex), Charsets.UTF_8)
            } catch (e: Exception) {
                ciphertextHex
            }
        } else {
            Log.w(TAG, "[INCOMING] Message missing payload and ciphertext")
            return
        }

        val source = if (queueId.isNotBlank()) queueId else json.optString("from", "")
        Log.i(TAG, "[INCOMING] Received envelope on queue ${source.take(12)}… via relay (len=${wireString.length})")
        incomingListener?.onPayloadReceived(
            sourceAddress = source,
            payload = wireString,
            transportType = TransportType.OFFLINE_RELAY
        )
    }
}
