package com.torxone.app.network

import android.util.Log
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.data.MessageEntity
import com.torxone.app.data.ReactionOutboxEntity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import com.torxone.app.group.GroupCryptoManager
import com.torxone.app.group.GroupPermission

enum class Transport { NEARBY_DIRECT, NEARBY_RELAY, TOR, FAILED, PENDING }

data class SendResult(val success: Boolean, val transport: Transport, val error: String? = null)

/**
 * Routes messages: Nearby (direct) → Nearby mesh relay → Tor (.onion).
 * Includes ACK-based delivery confirmation and automatic retry for failed sends.
 *
 * Important: Double Ratchet state is serialized per contact. Tor socket ownership
 * and reconnect handling remain centralized in TorManager.
 */
class MessageRouter(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val nearbyManager: NearbyConnectionManager,
    private val torManager: TorManager,
    val sessionManager: com.torxone.app.security.session.SessionManager? = null
) {
    companion object {
        private const val TAG = "MessageRouter"
        private const val RETRY_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES = 40
        private const val RELAY_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RELAY_CACHE_SIZE = 512
    }

    var identity: Identity? = null
    var mySigningKeyHex: String = ""
    var myOnionAddress: String = ""

    // TorX Agent 2.0 components
    var torXAgent: com.torxone.app.agent.TorXAgent? = null
    var deliveryTracker: com.torxone.app.agent.DeliveryTracker? = null
    var chatMessageService: com.torxone.app.service.ChatMessageService? = null
        get() {
            if (field == null && torXAgent != null) {
                field = com.torxone.app.service.ChatMessageService(
                    scope = scope,
                    db = db,
                    torXAgent = torXAgent!!,
                    sessionCryptoService = sessionManager,
                    identityProvider = { identity },
                    onionAddressProvider = { myOnionAddress }
                )
            }
            return field
        }

    private val recentRelayFingerprints = LinkedHashMap<String, Long>()
    private val pendingSessionPayloads = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ──────────────────────── INCOMING HANDLERS ────────────────────────

    fun handleNearbyPayload(endpointId: String, raw: String) {
        val json = MeshProtocol.parse(raw) ?: run {
            Log.w(TAG, "[NEARBY] Dropped unparseable payload from $endpointId (len=${raw.length})")
            return
        }

        val type = json.optString("type")
        Log.d(TAG, "[NEARBY] Received payload type=$type from $endpointId")

        when (type) {
            MeshProtocol.TYPE_HELLO -> handleHello(endpointId, json.optString("contact"))
            MeshProtocol.TYPE_SESSION_MSG -> scope.launch(Dispatchers.IO) { handleSessionMessage(endpointId, json) }
            MeshProtocol.TYPE_MSG -> scope.launch(Dispatchers.IO) { handleEncrypted(json, endpointId, MeshProtocol.TYPE_MSG) }
            MeshProtocol.TYPE_MEDIA_OFFER,
            MeshProtocol.TYPE_MEDIA_CHUNK,
            MeshProtocol.TYPE_MEDIA_ACK,
            MeshProtocol.TYPE_MEDIA_COMPLETE,
            MeshProtocol.TYPE_CALL_OFFER,
            MeshProtocol.TYPE_CALL_ANSWER,
            MeshProtocol.TYPE_ICE_CANDIDATE,
            MeshProtocol.TYPE_CALL_ACK,
            MeshProtocol.TYPE_CALL_END,
            MeshProtocol.TYPE_REACTION,
            MeshProtocol.TYPE_POLL_VOTE,
            MeshProtocol.TYPE_PRESENCE,
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK,
            MeshProtocol.TYPE_MUSIC_NOTE,
            MeshProtocol.TYPE_GROUP_INVITE,
            MeshProtocol.TYPE_GROUP_JOIN,
            MeshProtocol.TYPE_GROUP_UPDATE,
            MeshProtocol.TYPE_GROUP_LEAVE,
            MeshProtocol.TYPE_GROUP_KEY,
            MeshProtocol.TYPE_GROUP_SYNC_REQUEST,
            MeshProtocol.TYPE_GROUP_SYNC_RESPONSE,
            MeshProtocol.TYPE_GROUP_KEY_REQUEST,
            MeshProtocol.TYPE_GROUP_JOIN_REQUEST,
            MeshProtocol.TYPE_GROUP_INVITE_LINK,
            MeshProtocol.TYPE_GROUP_MESSAGE,
            MeshProtocol.TYPE_MUSIC_SYNC -> scope.launch(Dispatchers.IO) { handleEncrypted(json, endpointId, json.optString("type")) }
            MeshProtocol.TYPE_RELAY -> scope.launch(Dispatchers.IO) { handleRelay(endpointId, json) }
            MeshProtocol.TYPE_ACK -> scope.launch(Dispatchers.IO) { handleAck(json, endpointId) }
            MeshProtocol.TYPE_READ -> scope.launch(Dispatchers.IO) { handleRead(json, endpointId) }
            MeshProtocol.TYPE_PING -> handlePing(json, endpointId)
            MeshProtocol.TYPE_PONG -> handlePong(json)
        }
    }

    fun handleTorPayload(raw: String) {
        val json = MeshProtocol.parse(raw) ?: return
        Log.d(TAG, "[TOR] Received payload type=${json.optString("type")}")
        when (json.optString("type")) {
            MeshProtocol.TYPE_SESSION_MSG -> scope.launch(Dispatchers.IO) { handleSessionMessage(null, json) }
            MeshProtocol.TYPE_MSG -> scope.launch(Dispatchers.IO) { handleEncrypted(json, null, MeshProtocol.TYPE_MSG) }
            MeshProtocol.TYPE_MEDIA_OFFER,
            MeshProtocol.TYPE_MEDIA_CHUNK,
            MeshProtocol.TYPE_MEDIA_ACK,
            MeshProtocol.TYPE_MEDIA_COMPLETE,
            MeshProtocol.TYPE_CALL_OFFER,
            MeshProtocol.TYPE_CALL_ANSWER,
            MeshProtocol.TYPE_ICE_CANDIDATE,
            MeshProtocol.TYPE_CALL_ACK,
            MeshProtocol.TYPE_CALL_END,
            MeshProtocol.TYPE_REACTION,
            MeshProtocol.TYPE_POLL_VOTE,
            MeshProtocol.TYPE_PRESENCE,
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK,
            MeshProtocol.TYPE_MUSIC_NOTE,
            MeshProtocol.TYPE_GROUP_INVITE,
            MeshProtocol.TYPE_GROUP_JOIN,
            MeshProtocol.TYPE_GROUP_UPDATE,
            MeshProtocol.TYPE_GROUP_LEAVE,
            MeshProtocol.TYPE_GROUP_KEY,
            MeshProtocol.TYPE_GROUP_SYNC_REQUEST,
            MeshProtocol.TYPE_GROUP_SYNC_RESPONSE,
            MeshProtocol.TYPE_GROUP_KEY_REQUEST,
            MeshProtocol.TYPE_GROUP_JOIN_REQUEST,
            MeshProtocol.TYPE_GROUP_INVITE_LINK,
            MeshProtocol.TYPE_GROUP_MESSAGE,
            MeshProtocol.TYPE_MUSIC_SYNC -> scope.launch(Dispatchers.IO) { handleEncrypted(json, null, json.optString("type")) }
            MeshProtocol.TYPE_RELAY -> scope.launch(Dispatchers.IO) { handleRelay(null, json) }
            MeshProtocol.TYPE_ACK -> scope.launch(Dispatchers.IO) { handleAck(json, null) }
            MeshProtocol.TYPE_READ -> scope.launch(Dispatchers.IO) { handleRead(json, null) }
            MeshProtocol.TYPE_PING -> handlePing(json, null)
            MeshProtocol.TYPE_PONG -> handlePong(json)
        }
    }

    internal fun handlePing(json: JSONObject, viaEndpoint: String?) {
        val timestamp = json.optLong("timestamp", 0)
        val fromOnion = json.optString("from", "")
        if (timestamp > 0) {
            val pongWire = MeshProtocol.encodePong(timestamp)
            if (viaEndpoint != null) {
                nearbyManager.sendRaw(viaEndpoint, pongWire)
            } else if (fromOnion.isNotBlank()) {
                scope.launch(Dispatchers.IO) { torManager.sendToOnion(fromOnion, pongWire, null) }
            }
        }
    }

    internal fun handlePong(json: JSONObject) {
        val timestamp = json.optLong("timestamp", 0)
        if (timestamp > 0) {
            val latency = System.currentTimeMillis() - timestamp
            Log.d(TAG, "[PONG] Latency: ${latency}ms")
            torManager.setLastPing(latency)
        }
    }

    // ──────────────────────── SEND MESSAGE ────────────────────────

    suspend fun sendMessage(
        contactKey: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null,
        replyToType: String? = null
    ): SendResult = withContext(Dispatchers.IO) {
        val service = chatMessageService
        if (service != null) {
            return@withContext service.sendMessage(contactKey, text, replyToId, replyToText, replyToSender, replyToType)
        }
        SendResult(false, Transport.FAILED, "Chat service not initialized")
    }

    suspend fun sendGroupMessage(groupId: String, text: String, replyToId: String? = null): SendResult = withContext(Dispatchers.IO) {
        val service = chatMessageService
        if (service != null) {
            return@withContext service.sendGroupMessage(groupId, text, replyToId)
        }
        SendResult(false, Transport.FAILED, "Chat service not initialized")
    }

    fun getBestTransport(contact: ContactEntity): Transport {
        val connected = nearbyManager.connectedEndpoints.value
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) return Transport.NEARBY_DIRECT
        if (contact.onionAddress.isNotBlank() && torManager.isTorReady.value) return Transport.TOR
        if (connected.isNotEmpty()) return Transport.NEARBY_RELAY
        return Transport.FAILED
    }

    suspend fun sendRawPayload(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): SendResult = withContext(Dispatchers.IO) {
        val service = chatMessageService
        if (service != null) {
            return@withContext service.sendRawPayload(contactKey, rawText, messageType)
        }
        SendResult(false, Transport.FAILED, "Chat service not initialized")
    }

    suspend fun buildEncryptedWireFrame(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): String? = withContext(Dispatchers.IO) {
        chatMessageService?.buildEncryptedWireFrame(contactKey, rawText, messageType)
    }

    suspend fun toggleReaction(contactKey: String, targetMessageId: String, emoji: String): SendResult = withContext(Dispatchers.IO) {
        if (db.messageDao().getMessageById(targetMessageId)?.conversationType == "group") {
            val actor = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
            val target = db.messageDao().getMessageById(targetMessageId) ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")
            val current = parseReactionMap(target.reactionsJson)
            val action = if (current[actor]?.contains(emoji) == true) "remove" else "set"
            applyReactionToMessage(targetMessageId, actor, emoji, action)
            return@withContext sendGroupAction(contactKey, "REACTION", JSONObject().put("targetMessageId", targetMessageId).put("emoji", emoji).put("reactionAction", action))
        }
        val actorKey = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
        if (actorKey.isBlank()) return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        if (emoji.isBlank()) return@withContext SendResult(false, Transport.FAILED, "Reaction is blank")
        val target = db.messageDao().getMessageById(targetMessageId) ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")
        val current = parseReactionMap(target.reactionsJson)
        val mine = current[actorKey]?.firstOrNull()
        val action = if (mine == emoji) "remove" else "set"
        applyReactionToMessage(targetMessageId, actorKey, emoji, action)
        val reaction = ReactionOutboxEntity(reactionId = UUID.randomUUID().toString(), contactKey = contactKey, targetMessageId = targetMessageId, emoji = emoji, action = action, createdAt = System.currentTimeMillis())
        sendReactionPacket(reaction)
    }

    suspend fun editGroupMessage(groupId: String, messageId: String, text: String): SendResult = withContext(Dispatchers.IO) {
        val target = db.messageDao().getMessageById(messageId) ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")
        val actor = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
        if (target.conversationType != "group" || target.senderKey != actor) return@withContext SendResult(false, Transport.FAILED, "Only your own message can be edited")
        db.messageDao().updateMessageText(messageId, text.trim())
        sendGroupAction(groupId, "EDIT", JSONObject().put("targetMessageId", messageId).put("text", text.trim()))
    }

    suspend fun deleteGroupMessage(groupId: String, messageId: String): SendResult = withContext(Dispatchers.IO) {
        val target = db.messageDao().getMessageById(messageId) ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")
        val actor = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
        val group = db.groupDao().getGroup(groupId) ?: return@withContext SendResult(false, Transport.FAILED, "Group not found")
        if (target.senderKey != actor && group.creatorKey != actor) return@withContext SendResult(false, Transport.FAILED, "No permission")
        db.messageDao().deleteMessage(messageId)
        sendGroupAction(groupId, "DELETE", JSONObject().put("targetMessageId", messageId))
    }

    suspend fun sendGroupPoll(groupId: String, question: String, options: List<String>, multipleChoice: Boolean = false): SendResult = sendGroupAction(groupId, "POLL", JSONObject().put("question", question.trim()).put("options", JSONArray().apply { options.forEach { put(it.trim()) } }).put("multipleChoice", multipleChoice))
    suspend fun sendGroupPollVote(groupId: String, targetMessageId: String, optionIndex: Int): SendResult { val actor = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }; applyPollVote(targetMessageId, actor, optionIndex); return sendGroupAction(groupId, "POLL_VOTE", JSONObject().put("targetMessageId", targetMessageId).put("optionIndex", optionIndex)) }
    suspend fun sendGroupReadReceipt(groupId: String, messageId: String): SendResult = sendGroupAction(groupId, "READ", JSONObject().put("targetMessageId", messageId))

    private suspend fun sendGroupAction(groupId: String, action: String, body: JSONObject): SendResult = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val group = db.groupDao().getGroup(groupId) ?: return@withContext SendResult(false, Transport.FAILED, "Group not found")
        val actor = CryptoManager.toHex(identity.signingPublicKey)
        if (!GroupPermission.canSendMessages(group, db.groupDao().getGroupMember(groupId, actor))) return@withContext SendResult(false, Transport.FAILED, "No permission")
        val key = db.groupKeyDao().getLatestKey(groupId) ?: return@withContext SendResult(false, Transport.FAILED, "Group key unavailable")
        val id = UUID.randomUUID().toString()
        val inner = body.put("action", action).put("messageId", id).put("senderKey", actor).put("timestamp", System.currentTimeMillis())
        if (action == "POLL") { val poll = JSONObject().put("type", "poll").put("question", inner.optString("question")).put("options", inner.optJSONArray("options") ?: JSONArray()).put("multipleChoice", inner.optBoolean("multipleChoice")); db.messageDao().insertMessage(MessageEntity(messageId = id, contactKey = groupId, conversationType = "group", senderKey = actor, text = "[Poll:JSON]$poll", messageType = "POLL", timestamp = System.currentTimeMillis(), direction = "sent", status = "pending")) }
        val encrypted = GroupCryptoManager.encrypt(key, inner.toString())
        val wire = JSONObject().put("type", MeshProtocol.TYPE_GROUP_MESSAGE).put("schemaVersion", 2).put("groupId", groupId).put("keyVersion", key.keyVersion).put("ciphertext", encrypted.ciphertextBase64).put("iv", encrypted.ivBase64).toString()
        val agent = torXAgent
        var delivered = false
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != actor }.forEach { member ->
            if (agent != null) {
                val wireFrame = buildEncryptedWireFrame(member.memberKey, wire, MeshProtocol.TYPE_GROUP_MESSAGE)
                if (wireFrame != null) {
                    agent.queueForDelivery(
                        recipientKey = member.memberKey,
                        messageId = "${id}_${member.memberKey.take(8)}",
                        messageType = com.torxone.app.agent.EnvelopeType.GROUP_MESSAGE,
                        encryptedPayload = wireFrame
                    )
                    delivered = true
                } else if (sendRawPayload(member.memberKey, wire, MeshProtocol.TYPE_GROUP_MESSAGE).success) {
                    delivered = true
                }
            } else if (sendRawPayload(member.memberKey, wire, MeshProtocol.TYPE_GROUP_MESSAGE).success) {
                delivered = true
            }
        }
        SendResult(delivered, if (delivered) Transport.NEARBY_RELAY else Transport.FAILED, if (delivered) null else "No group members are reachable")
    }

    // ──────────────────────── RETRY LOOP ────────────────────────

    fun ensureRetryLoopRunning() {
        // TorX One 2.0: Delivery loops and retries are managed by TorXAgent
        torXAgent?.triggerProcessing()
    }

    fun setBackgroundRetryInterval(intervalMs: Long) {
        // TorX One 2.0: Centrally paced by TorXAgent delivery loops
    }

    fun retryPendingNow() {
        torXAgent?.triggerProcessing()
    }

    // ──────────────────────── RECEIPT DISPATCH & OUTBOX ────────────────────────

    fun queueAndDispatchReceipt(
        messageId: String,
        recipientKey: String,
        type: String, // "ack" or "read"
        viaEndpoint: String? = null,
        senderOnion: String? = null
    ) {
        if (messageId.isBlank() || recipientKey.isBlank()) return
        val tracker = deliveryTracker
        if (tracker != null) {
            if (type == "read") {
                tracker.sendReadReceipt(messageId, recipientKey)
            } else {
                tracker.sendAck(messageId, recipientKey, viaEndpoint, senderOnion)
            }
            return
        }
        val agent = torXAgent
        if (agent != null) {
            val wire = if (type == "read") {
                MeshProtocol.encodeRead(messageId, mySigningKeyHex, recipientKey, myOnionAddress.ifBlank { null })
            } else {
                MeshProtocol.encodeAck(messageId, mySigningKeyHex, recipientKey, myOnionAddress.ifBlank { null })
            }
            scope.launch(Dispatchers.IO) {
                agent.queueForDelivery(
                    recipientKey = recipientKey,
                    messageId = "${type}_$messageId",
                    messageType = if (type == "read") com.torxone.app.agent.EnvelopeType.READ else com.torxone.app.agent.EnvelopeType.ACK,
                    encryptedPayload = wire
                )
            }
        }
    }


    // ──────────────────────── ACK HANDLING ────────────────────────

    internal suspend fun handleAck(json: JSONObject, viaEndpoint: String?) {
        if (forwardReceiptIfNeeded(json, viaEndpoint)) return
        val messageId = json.optString("msgId")
        val senderKey = json.optString("from", "").trim().lowercase()
        val toKey = json.optString("to", "").trim().lowercase()
        if (messageId.isBlank() || senderKey.isBlank()) return
        if (toKey.isNotBlank() && toKey != mySigningKeyHex) {
            Log.w(TAG, "[ACK] Dropped ACK addressed to $toKey (my key=$mySigningKeyHex)")
            return
        }
        val existing = db.messageDao().getMessageById(messageId) ?: return
        if (existing.direction != "sent" || existing.contactKey.trim().lowercase() != senderKey) {
            Log.w(TAG, "[ACK] Dropped ACK from wrong contact ($senderKey vs ${existing.contactKey})")
            return
        }

        db.messageDao().updateSentMessageStatus(messageId, existing.contactKey, "delivered")
        pendingSessionPayloads.remove(messageId)
        Log.i(TAG, "[ACK] id=$messageId received=true")
        Log.i(TAG, "[MSG] id=$messageId state=DELIVERED")

        val senderOnion = json.optString("senderOnion", "")
        if (senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
        }
    }

    internal fun sendAck(messageId: String, senderKey: String, viaEndpoint: String?, senderOnion: String? = null) {
        val tracker = deliveryTracker
        if (tracker != null) {
            tracker.sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            return
        }
        queueAndDispatchReceipt(messageId, senderKey, "ack", viaEndpoint, senderOnion)
    }

    internal suspend fun handleRead(json: JSONObject, viaEndpoint: String?) {
        if (forwardReceiptIfNeeded(json, viaEndpoint)) return
        val messageId = json.optString("msgId")
        val senderKey = json.optString("from", "").trim().lowercase()
        val toKey = json.optString("to", "").trim().lowercase()
        if (messageId.isBlank() || senderKey.isBlank()) return
        if (toKey.isNotBlank() && toKey != mySigningKeyHex) {
            Log.w(TAG, "[READ] Dropped READ addressed to $toKey (my key=$mySigningKeyHex)")
            return
        }
        val existing = db.messageDao().getMessageById(messageId) ?: return
        if (existing.direction != "sent" || existing.contactKey.trim().lowercase() != senderKey) {
            Log.w(TAG, "[READ] Dropped READ from wrong contact ($senderKey vs ${existing.contactKey})")
            return
        }

        db.messageDao().updateSentMessageStatus(messageId, existing.contactKey, "read")
        pendingSessionPayloads.remove(messageId)
        Log.i(TAG, "[READ] id=$messageId received=true")
        Log.i(TAG, "[MSG] id=$messageId state=SEEN")

        val senderOnion = json.optString("senderOnion", "")
        if (senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
        }
    }

    fun sendReadReceipt(messageId: String, senderKey: String) {
        val tracker = deliveryTracker
        if (tracker != null) {
            tracker.sendReadReceipt(messageId, senderKey)
            return
        }
        queueAndDispatchReceipt(messageId, senderKey, "read")
    }

    private fun forwardReceiptIfNeeded(json: JSONObject, viaEndpoint: String?): Boolean {
        val destination = json.optString("to", "").trim().lowercase()
        if (destination.isBlank() || destination == mySigningKeyHex) return false
        val ttl = json.optInt("ttl", MeshProtocol.DEFAULT_TTL)
        if (ttl <= 1) return true
        val forwarded = JSONObject(json.toString()).put("ttl", ttl - 1).toString()
        nearbyManager.connectedEndpoints.value.filter { it != viaEndpoint }.forEach { runCatching { nearbyManager.sendRaw(it, forwarded) } }
        return true
    }

    fun sendMediaAck(messageId: String, senderKey: String) {
        if (messageId.isBlank()) return
        sendAck(messageId, senderKey, null)
    }

    // ──────────────────────── HELLO EXCHANGE ────────────────────────

    fun broadcastHello(endpointId: String) {
        val identity = identity ?: return
        val contactString = CryptoManager.createContactString(identity, myOnionAddress.ifBlank { null })
        nearbyManager.sendRaw(endpointId, MeshProtocol.encodeHello(contactString))
    }

    internal fun handleHello(endpointId: String, contactString: String) {
        if (contactString.isBlank()) return
        val parsed = CryptoManager.parseContactString(contactString) ?: return
        scope.launch(Dispatchers.IO) {
            db.contactDao().insertContact(ContactEntity(signingPublicKey = CryptoManager.toHex(parsed.signingPublicKey), encryptionPublicKey = CryptoManager.toHex(parsed.encryptionPublicKey), name = parsed.name, endpointId = endpointId, onionAddress = parsed.onionAddress ?: "", isConnected = true))
            com.torxone.app.service.TorXOneService.getInstance()?.profileSyncManager?.broadcastLocalProfile(CryptoManager.toHex(parsed.signingPublicKey))
        }
    }

    // ──────────────────────── RELAY ────────────────────────

    internal suspend fun handleRelay(fromEndpointId: String?, json: JSONObject) {
        val dest = json.optString("dest", "").trim().lowercase()
        val ttl = json.optInt("ttl", 0)
        val innerType = json.optString("innerType", MeshProtocol.TYPE_MSG)
        val messageId = json.optString("msgId", "")
        if (dest == mySigningKeyHex) {
            if (innerType == MeshProtocol.TYPE_SESSION_MSG) {
                val wireStr = json.optString("sessionWire", "")
                handleSessionMessage(fromEndpointId, if (wireStr.isNotBlank()) JSONObject(wireStr) else json)
            } else handleEncrypted(json, fromEndpointId, innerType)
            return
        }
        if (ttl <= 1) return
        val fingerprint = if (innerType == MeshProtocol.TYPE_SESSION_MSG) "relay_session:$dest:$messageId" else { val payload = MeshProtocol.parseEncrypted(json) ?: return; "${payload.fromSigningKey}:${payload.toSigningKey}:${payload.nonceHex}:${payload.signatureHex}" }
        if (!rememberRelayFingerprint(fingerprint)) return
        json.put("ttl", ttl - 1)
        val wire = json.toString()
        nearbyManager.connectedEndpoints.value.filter { it != fromEndpointId }.forEach { runCatching { nearbyManager.sendRaw(it, wire) } }
    }

    // ──────────────────────── DECRYPTION ────────────────────────

    internal suspend fun handleSessionMessage(viaEndpoint: String?, json: JSONObject): Boolean {
        val to = json.optString("to", "").trim().lowercase()
        val ttl = json.optInt("ttl", 3)
        val fromKey = json.optString("from", "").trim().lowercase()
        val messageId = json.optString("msgId", "")
        val senderOnion = json.optString("senderOnion", "")
        if (to == mySigningKeyHex) {
            val sm = sessionManager ?: run {
                Log.w(TAG, "[SESSION_RX] sessionManager is null; cannot decrypt message")
                return false
            }
            val contact = db.contactDao().getContact(fromKey)
            if (contact != null && senderOnion.isNotBlank() && contact.onionAddress != senderOnion) db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))

            // Fast deduplication: If message is already in DB, resend ACK and skip decryption
            if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
                Log.d(TAG, "[SESSION_RX] Duplicate message $messageId already in DB from ${contact?.name ?: fromKey}; sending ACK")
                sendAck(messageId, fromKey, viaEndpoint, senderOnion)
                return true
            }

            val decrypted = try { 
                sm.decrypt(fromKey, json) 
            } catch (e: Exception) { 
                Log.w(TAG, "[SESSION_RX] Decrypt failed from ${contact?.name ?: fromKey}: ${e.message}")
                if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
                    sendAck(messageId, fromKey, viaEndpoint, senderOnion)
                    return true
                }
                return false 
            }
            Log.i(TAG, "[RX] id=$messageId decrypted=true")
            dispatchDecryptedMessage(contact, fromKey, decrypted.plaintext, decrypted.messageType, messageId, viaEndpoint, senderOnion)
            return true
        }
        if (viaEndpoint != null && ttl > 1) {
            val fingerprint = "relay_session:$to:$messageId"
            if (!rememberRelayFingerprint(fingerprint)) return false
            json.put("ttl", ttl - 1)
            val relayWire = MeshProtocol.encodeSessionRelay(dest = to, from = fromKey, sessionWireJson = json.toString(), ttl = ttl - 1, messageId = messageId, senderOnion = senderOnion.ifBlank { null })
            nearbyManager.connectedEndpoints.value.filter { it != viaEndpoint }.forEach { runCatching { nearbyManager.sendRaw(it, relayWire) } }
            return true
        }
        return false
    }

    internal suspend fun handleEncrypted(json: JSONObject, viaEndpoint: String?, messageType: String) {
        val payload = MeshProtocol.parseEncrypted(json) ?: return
        val identity = identity ?: return
        val messageId = json.optString("msgId", "")
        val senderOnion = json.optString("senderOnion", "")
        if (payload.toSigningKey != mySigningKeyHex || payload.fromSigningKey == mySigningKeyHex) return
        val senderKey = payload.fromSigningKey
        val contact = db.contactDao().getContact(senderKey) ?: return
        if (senderOnion.isNotBlank() && contact.onionAddress != senderOnion) db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
        if (messageType == MeshProtocol.TYPE_MSG) {
            val existingSession = db.sessionDao().getSession(senderKey)
            if (existingSession != null && existingSession.state == "ACTIVE") return
        }
        if (contact.encryptionPublicKey.isBlank()) return
        val ciphertext = CryptoManager.fromHexOrNull(payload.ciphertextHex) ?: return
        val nonce = CryptoManager.fromHexOrNull(payload.nonceHex, 24) ?: return
        val signature = CryptoManager.fromHexOrNull(payload.signatureHex, 64) ?: return
        val senderSigPub = CryptoManager.fromHexOrNull(senderKey, 32) ?: return
        val senderEncPub = CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) ?: return
        if (!CryptoManager.verify(ciphertext + nonce, signature, senderSigPub)) return
        if (identity.encryptionSecretKey.isEmpty()) {
            db.pendingEncryptedPayloadDao().insert(com.torxone.app.data.PendingEncryptedPayload(messageId = if (messageId.isNotBlank()) messageId else UUID.randomUUID().toString(), fromSigningKey = senderKey, rawJson = json.toString(), receivedAt = System.currentTimeMillis()))
            if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            com.torxone.app.service.NotificationHelper.showDeferredMessageNotification(com.torxone.app.service.TorXOneService.getInstance()!!, contact)
            return
        }
        val plaintext = try { CryptoManager.decryptMessage(ciphertext, nonce, senderEncPub, identity.encryptionSecretKey) } catch (e: Exception) { return }
        Log.i(TAG, "[RX] id=$messageId decrypted=true")
        dispatchDecryptedMessage(contact, senderKey, plaintext, messageType, messageId, viaEndpoint, senderOnion)
    }

    private suspend fun dispatchDecryptedMessage(contact: ContactEntity?, senderKey: String, plaintext: String, messageType: String, messageId: String, viaEndpoint: String?, senderOnion: String?) {
        val service = com.torxone.app.service.TorXOneService.getInstance()
        if (contact != null && messageType != MeshProtocol.TYPE_PROFILE_UPDATE && messageType != MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO && messageType != MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK) service?.profileSyncManager?.syncWithContactSoon(senderKey)
        if (messageType == MeshProtocol.TYPE_MEDIA_CHUNK || messageType == MeshProtocol.TYPE_MEDIA_OFFER || messageType == MeshProtocol.TYPE_MEDIA_ACK || messageType == MeshProtocol.TYPE_MEDIA_COMPLETE) { service?.mediaTransferManager?.handleMediaPacket(messageType, plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_CALL_OFFER || messageType == MeshProtocol.TYPE_CALL_ANSWER || messageType == MeshProtocol.TYPE_ICE_CANDIDATE || messageType == MeshProtocol.TYPE_CALL_END || messageType == MeshProtocol.TYPE_CALL_ACK) { service?.callManager?.handleSignal(messageType, plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_PROFILE_UPDATE || messageType == MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO || messageType == MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK) { service?.profileSyncManager?.handleProfilePacket(messageType, plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_REACTION) { handleReactionPacket(plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_POLL_VOTE) { handlePollVotePacket(plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_PRESENCE) { service?.presenceManager?.handlePresencePacket(plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_MUSIC_NOTE) { service?.musicNoteManager?.handleMusicNotePacket(plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_MUSIC_SYNC) { service?.listenTogetherManager?.handleSyncPacket(plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_GROUP_INVITE || messageType == MeshProtocol.TYPE_GROUP_JOIN || messageType == MeshProtocol.TYPE_GROUP_UPDATE || messageType == MeshProtocol.TYPE_GROUP_LEAVE || messageType == MeshProtocol.TYPE_GROUP_KEY || messageType == MeshProtocol.TYPE_GROUP_SYNC_REQUEST || messageType == MeshProtocol.TYPE_GROUP_SYNC_RESPONSE || messageType == MeshProtocol.TYPE_GROUP_KEY_REQUEST) { service?.groupManager?.handleGroupPacket(messageType, plaintext, senderKey); return }
        if (messageType == MeshProtocol.TYPE_GROUP_MESSAGE) { handleGroupMessage(plaintext, senderKey, messageId, viaEndpoint, senderOnion); return }

        // Session messages for ordinary direct chats still require a known
        // contact for UI metadata and notification policy. Group messages
        // are authorized by GroupMemberEntity instead and were dispatched
        // above without that unrelated ContactEntity requirement.
        if (contact == null) return

        val chatPayload = decodeChatMessagePayload(plaintext)
        if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
            sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            return
        }
        val isActiveConversation = com.torxone.app.service.ActiveConversationTracker.isActive(senderKey)
        val initialStatus = if (isActiveConversation) "read" else "delivered"
        val effectiveMessageId = if (messageId.isNotBlank()) messageId else UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = effectiveMessageId,
                contactKey = senderKey,
                text = chatPayload.text,
                timestamp = chatPayload.sentAt ?: System.currentTimeMillis(),
                direction = "received",
                status = initialStatus,
                replyToId = chatPayload.replyToId,
                replyToText = chatPayload.replyToText,
                replyToSender = chatPayload.replyToSender,
                replyToType = chatPayload.replyToType
            )
        )
        Log.i(TAG, "[RX] id=$effectiveMessageId persisted=true")
        if (service != null) {
            val isMuted = contact.muteUntil == -1L || (contact.muteUntil > 0L && System.currentTimeMillis() < contact.muteUntil)
            if (isActiveConversation) com.torxone.app.service.NotificationHelper.clearContactNotifications(service, senderKey)
            else if (!isMuted) { val unreadMsgs = db.messageDao().getUnreadMessagesSync(senderKey); com.torxone.app.service.NotificationHelper.showMessageNotification(service, contact, unreadMsgs) }
        }
        // ONLY AFTER DB persistence: emit ACK (and READ if currently active)
        if (messageId.isNotBlank()) {
            sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            if (isActiveConversation) {
                sendReadReceipt(messageId, senderKey)
            }
        }
    }

    private suspend fun handleGroupMessage(jsonStr: String, senderKey: String, messageId: String, viaEndpoint: String?, senderOnion: String?) {
        val json = JSONObject(jsonStr)
        if (json.optInt("schemaVersion", 0) != 2) return
        val groupId = json.optString("groupId")
        val keyVersion = json.optInt("keyVersion", 0)
        val ciphertext = json.optString("ciphertext")
        val iv = json.optString("iv")
        if (groupId.isBlank() || keyVersion <= 0 || ciphertext.isBlank() || iv.isBlank()) return
        val group = db.groupDao().getGroup(groupId) ?: return
        val myKey = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
        if (db.groupDao().getGroupMember(groupId, myKey) == null) return
        val senderMember = db.groupDao().getGroupMember(groupId, senderKey)
        if (senderMember == null || senderMember.role == "invited") return
        val key = db.groupKeyDao().getKey(groupId, keyVersion) ?: run { com.torxone.app.service.TorXOneService.getInstance()?.groupManager?.requestMissingKey(groupId, group.creatorKey, keyVersion); return }
        if (db.groupKeyDao().getLatestKey(groupId)?.keyVersion != keyVersion) return
        val inner = try { JSONObject(GroupCryptoManager.decrypt(key, ciphertext, iv)) } catch (_: Exception) { return }
        val innerSenderKey = inner.optString("senderKey")
        val innerMessageId = inner.optString("messageId")
        if (innerSenderKey != senderKey || innerMessageId.isBlank()) return
        when (inner.optString("action")) {
            "REACTION" -> { val target = inner.optString("targetMessageId"); val emoji = inner.optString("emoji"); if (target.isNotBlank() && emoji.isNotBlank()) applyReactionToMessage(target, innerSenderKey, emoji, inner.optString("reactionAction", "set")); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
            "EDIT" -> { val target = db.messageDao().getMessageById(inner.optString("targetMessageId")); if (target != null && target.conversationType == "group" && target.senderKey == innerSenderKey) db.messageDao().updateMessageText(target.messageId, inner.optString("text")); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
            "DELETE" -> { val target = db.messageDao().getMessageById(inner.optString("targetMessageId")); if (target != null && target.conversationType == "group" && (target.senderKey == innerSenderKey || group.creatorKey == innerSenderKey)) db.messageDao().deleteMessage(target.messageId); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
            "POLL" -> { val poll = JSONObject().put("question", inner.optString("question")).put("options", inner.optJSONArray("options") ?: JSONArray()).put("multipleChoice", inner.optBoolean("multipleChoice")); db.messageDao().insertMessage(MessageEntity(messageId = innerMessageId, contactKey = groupId, conversationType = "group", senderKey = innerSenderKey, text = "[Poll:JSON]$poll", messageType = "POLL", timestamp = inner.optLong("timestamp", System.currentTimeMillis()), direction = "received", status = "delivered")); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
            "POLL_VOTE" -> { val target = inner.optString("targetMessageId"); if (target.isNotBlank()) applyPollVote(target, innerSenderKey, inner.optInt("optionIndex", -1)); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
            "READ" -> { val target = db.messageDao().getMessageById(inner.optString("targetMessageId")); if (target != null && target.conversationType == "group") db.messageDao().markMessageRead(target.messageId); if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
        }
        val chatPayload = decodeChatMessagePayload(inner.toString())
        if (db.messageDao().getMessageById(innerMessageId) != null) { sendAck(messageId, senderKey, viaEndpoint, senderOnion); return }
        db.messageDao().insertMessage(MessageEntity(messageId = innerMessageId, contactKey = groupId, conversationType = "group", senderKey = innerSenderKey, text = chatPayload.text, timestamp = chatPayload.sentAt ?: inner.optLong("timestamp", System.currentTimeMillis()), direction = "received", status = "delivered", replyToId = chatPayload.replyToId, replyToText = chatPayload.replyToText, replyToSender = chatPayload.replyToSender, replyToType = chatPayload.replyToType))
        val service = com.torxone.app.service.TorXOneService.getInstance()
        if (service != null) {
            val isActive = com.torxone.app.service.ActiveConversationTracker.isActive(groupId)
            if (isActive) com.torxone.app.service.NotificationHelper.clearContactNotifications(service, groupId)
            else { val unreadMsgs = db.messageDao().getUnreadMessagesSync(groupId, "group"); val grp = db.groupDao().getGroup(groupId); val isMuted = grp?.muteUntil == -1L || (grp?.muteUntil ?: 0L) > System.currentTimeMillis(); if (grp != null && !isMuted) com.torxone.app.service.NotificationHelper.showMessageNotification(service, ContactEntity(signingPublicKey = groupId, encryptionPublicKey = "", name = grp.name), unreadMsgs, "group") }
        }
        if (messageId.isNotBlank()) sendAck(messageId, senderKey, viaEndpoint, senderOnion)
    }

    // ──────────────────────── ENCRYPTION ────────────────────────

    private fun buildEncryptedPayload(identity: Identity, contact: ContactEntity, text: String): MeshProtocol.EncryptedPayload? {
        return try {
            if (contact.encryptionPublicKey.isBlank()) return null
            val encPub = CryptoManager.fromHex(contact.encryptionPublicKey)
            val (ciphertext, nonce) = CryptoManager.encryptMessage(text, encPub, identity.encryptionSecretKey)
            val signature = CryptoManager.sign(ciphertext + nonce, identity.signingSecretKey)
            MeshProtocol.EncryptedPayload(fromSigningKey = mySigningKeyHex, toSigningKey = contact.signingPublicKey, ciphertextHex = CryptoManager.toHex(ciphertext), nonceHex = CryptoManager.toHex(nonce), signatureHex = CryptoManager.toHex(signature))
        } catch (e: Exception) { Log.e(TAG, "[CRYPTO] buildEncryptedPayload failed", e); null }
    }

    private suspend fun sendReactionPacket(reaction: ReactionOutboxEntity): SendResult {
        val actorKey = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }
        val payload = JSONObject().put("astraType", "reaction").put("version", 1).put("reactionId", reaction.reactionId).put("targetMessageId", reaction.targetMessageId).put("emoji", reaction.emoji).put("action", reaction.action).put("actorKey", actorKey).put("createdAt", reaction.createdAt).toString()
        return sendRawPayload(reaction.contactKey, payload, MeshProtocol.TYPE_REACTION)
    }
    private fun handleReactionPacket(raw: String, senderKey: String) { runCatching { val json = JSONObject(raw); applyReactionToMessage(json.getString("targetMessageId"), senderKey, json.getString("emoji"), json.optString("action", "add")) }.onFailure { Log.w(TAG, "Invalid reaction packet", it) } }
    private fun applyReactionToMessage(messageId: String, actorKey: String, emoji: String, action: String) { if (messageId.isBlank() || actorKey.isBlank() || emoji.isBlank()) return; val message = db.messageDao().getMessageById(messageId) ?: return; val reactions = parseReactionMap(message.reactionsJson); val actorReactions = reactions[actorKey]?.toMutableSet() ?: linkedSetOf(); when (action) { "remove" -> actorReactions.remove(emoji); "set" -> { actorReactions.clear(); actorReactions.add(emoji) }; else -> { actorReactions.clear(); actorReactions.add(emoji) } }; if (actorReactions.isEmpty()) reactions.remove(actorKey) else reactions[actorKey] = actorReactions.toList(); db.messageDao().updateReactions(messageId, encodeReactionMap(reactions)) }
    private fun parseReactionMap(raw: String?): MutableMap<String, List<String>> { val result = linkedMapOf<String, List<String>>(); if (raw.isNullOrBlank()) return result; return runCatching { val json = JSONObject(raw); json.keys().forEach { actor -> val value = json.get(actor); result[actor] = when (value) { is JSONArray -> List(value.length()) { index -> value.optString(index) }.filter { it.isNotBlank() }; is String -> listOf(value).filter { it.isNotBlank() }; else -> emptyList() } }; result }.getOrElse { result } }
    private fun encodeReactionMap(reactions: Map<String, List<String>>): String? { if (reactions.isEmpty()) return null; val json = JSONObject(); reactions.forEach { (actor, emojis) -> val array = JSONArray(); emojis.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }; if (array.length() > 0) json.put(actor, array) }; return if (json.length() == 0) null else json.toString() }

    private fun handlePollVotePacket(raw: String, senderKey: String) { runCatching { val json = JSONObject(raw); applyPollVote(json.getString("targetMessageId"), senderKey, json.getInt("optionIndex")) }.onFailure { Log.w(TAG, "Invalid poll vote packet", it) } }
    private fun applyPollVote(messageId: String, voterKey: String, optionIndex: Int) { val message = db.messageDao().getMessageById(messageId) ?: return; val votes = parseReactionMap(message.reactionsJson); votes[voterKey] = listOf(optionIndex.toString()); db.messageDao().updateReactions(messageId, encodeReactionMap(votes)) }
    suspend fun sendPollVote(contactKey: String, targetMessageId: String, optionIndex: Int): SendResult { val actorKey = mySigningKeyHex.ifBlank { identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty() }; if (actorKey.isBlank()) return SendResult(false, Transport.FAILED, "Not logged in"); applyPollVote(targetMessageId, actorKey, optionIndex); val payload = JSONObject().put("astraType", "poll_vote").put("targetMessageId", targetMessageId).put("optionIndex", optionIndex).put("voterKey", actorKey).toString(); return sendRawPayload(contactKey, payload, MeshProtocol.TYPE_POLL_VOTE) }

    private data class ChatMessagePayload(val text: String, val sentAt: Long? = null, val replyToId: String? = null, val replyToText: String? = null, val replyToSender: String? = null, val replyToType: String? = null)

    private fun encodeChatMessagePayload(text: String, sentAt: Long, replyToId: String?, replyToText: String?, replyToSender: String?, replyToType: String?): String {
        return JSONObject().apply {
            put("astraType", "chat_message")
            put("version", 2)
            put("sentAt", sentAt)
            put("text", text)
            replyToId?.takeIf { it.isNotBlank() }?.let { put("reply", JSONObject().put("originalMessageId", it).put("originalSender", replyToSender ?: "").put("originalType", replyToType ?: "TEXT").put("originalPreview", replyToText ?: "")) }
        }.toString()
    }

    private fun decodeChatMessagePayload(raw: String): ChatMessagePayload {
        return runCatching {
            val json = JSONObject(raw)
            if (json.optString("astraType") != "chat_message") return@runCatching ChatMessagePayload(raw)
            val reply = json.optJSONObject("reply")
            ChatMessagePayload(text = json.optString("text", ""), sentAt = json.optLong("sentAt", 0L).takeIf { it > 0L }, replyToId = reply?.optString("originalMessageId")?.takeIf { it.isNotBlank() }, replyToSender = reply?.optString("originalSender")?.takeIf { it.isNotBlank() }, replyToType = reply?.optString("originalType")?.takeIf { it.isNotBlank() }, replyToText = reply?.optString("originalPreview")?.takeIf { it.isNotBlank() })
        }.getOrElse { ChatMessagePayload(raw) }
    }

    private fun rememberRelayFingerprint(fingerprint: String): Boolean { val now = System.currentTimeMillis(); val iterator = recentRelayFingerprints.iterator(); while (iterator.hasNext()) { val entry = iterator.next(); if (now - entry.value > RELAY_CACHE_TTL_MS) iterator.remove() }; if (recentRelayFingerprints.containsKey(fingerprint)) return false; recentRelayFingerprints[fingerprint] = now; while (recentRelayFingerprints.size > MAX_RELAY_CACHE_SIZE) { val firstKey = recentRelayFingerprints.keys.firstOrNull() ?: break; recentRelayFingerprints.remove(firstKey) }; return true }

    suspend fun processEncryptedBacklog() {
        val identity = identity ?: return
        if (identity.encryptionSecretKey.isEmpty()) return
        val pending = db.pendingEncryptedPayloadDao().getAll()
        if (pending.isEmpty()) return
        for (payloadRow in pending) {
            try { val json = JSONObject(payloadRow.rawJson); val type = json.optString("type", MeshProtocol.TYPE_MSG); val innerType = if (type == MeshProtocol.TYPE_RELAY) json.optString("innerType", MeshProtocol.TYPE_MSG) else type; handleEncrypted(json, null, innerType); db.pendingEncryptedPayloadDao().delete(payloadRow.messageId) }
            catch (e: Exception) { Log.e(TAG, "[DEFERRED] Failed to process payload ${payloadRow.messageId}", e); db.pendingEncryptedPayloadDao().delete(payloadRow.messageId) }
        }
    }
}
