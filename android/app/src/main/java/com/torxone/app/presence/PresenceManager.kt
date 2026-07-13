package com.torxone.app.presence

import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PresenceState(
    val contactKey: String,
    val activity: String,
    val label: String,
    val updatedAt: Long,
    val expiresAt: Long
)

class PresenceManager(
    private val scope: CoroutineScope,
    private val messageRouter: MessageRouter
) {
    private val _presence = MutableStateFlow<Map<String, PresenceState>>(emptyMap())
    val presence: StateFlow<Map<String, PresenceState>> = _presence.asStateFlow()
    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen.asStateFlow()

    private var cleanupJob: Job? = null

    fun sendPresence(contactKey: String, activity: String, label: String, ttlMs: Long = 8_000L) {
        if (contactKey.isBlank() || activity.isBlank()) return
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("astraType", "presence")
            .put("version", 1)
            .put("activity", activity)
            .put("label", label)
            .put("updatedAt", now)
            .put("expiresAt", now + ttlMs)
            .toString()

        scope.launch(Dispatchers.IO) {
            messageRouter.sendRawPayload(contactKey, payload, MeshProtocol.TYPE_PRESENCE)
        }
    }

    fun handlePresencePacket(raw: String, senderKey: String) {
        runCatching {
            val json = JSONObject(raw)
            if (json.optString("astraType") != "presence") return
            val now = System.currentTimeMillis()
            val state = PresenceState(
                contactKey = senderKey,
                activity = json.optString("activity", "active"),
                label = json.optString("label", "Online"),
                updatedAt = json.optLong("updatedAt", now),
                expiresAt = json.optLong("expiresAt", now + 8_000L)
            )
            _lastSeen.value = _lastSeen.value + (senderKey to state.updatedAt)
            if (state.activity == "offline") {
                _presence.value = _presence.value - senderKey
                return
            }
            _presence.value = _presence.value + (senderKey to state)
            ensureCleanup()
        }
    }

    private fun ensureCleanup() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                val expired = _presence.value.filterValues { it.expiresAt <= now }
                if (expired.isNotEmpty()) {
                    _lastSeen.value = _lastSeen.value + expired.mapValues { it.value.updatedAt }
                }
                val active = _presence.value.filterValues { it.expiresAt > now }
                _presence.value = active
                if (active.isEmpty()) {
                    cleanupJob = null
                    return@launch
                }
            }
        }
    }
}
