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

    private var retryJob: Job? = null
    @Volatile
    private var retryIntervalMs: Long = RETRY_INTERVAL_MS
    private var retryBackoffMs: Long = RETRY_INTERVAL_MS
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
                scope.launch(Dispatchers.IO) { sendTorFrame(fromOnion, pongWire) }
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
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey)
            ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")

        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) {
            return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        }

        val sentAt = System.currentTimeMillis()
        val wireText = encodeChatMessagePayload(
            text = text,
            sentAt = sentAt,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            replyToType = replyToType
        )

        // Publish the local row before the potentially expensive crypto step so
        // the conversation immediately renders the message as SENDING.
        val messageId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = messageId,
                contactKey = contactKey,
                text = text,
                timestamp = sentAt,
                direction = "sent",
                status = "sending",
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSender = replyToSender,
                replyToType = replyToType
            )
        )
        Log.i(TAG, "[MSG] id=$messageId state=SENDING")

        val sessionPayload = try {
            sessionManager?.encrypt(contact, wireText, MeshProtocol.TYPE_MSG, messageId)
        } catch (e: Exception) {
            Log.w(TAG, "[SEND] Session encryption error, falling back to legacy: ${e.message}")
            null
        }

        val legacyPayload = if (sessionPayload == null) {
            buildEncryptedPayload(identity, contact, wireText)
                ?: run {
                    db.messageDao().updateMessageStatus(messageId, "failed")
                    return@withContext SendResult(false, Transport.FAILED, "Encryption failed")
                }
        } else null
        val wireJson = sessionPayload?.wireJsonString
            ?: MeshProtocol.encodeDirectMessage(legacyPayload!!, messageId, myOnionAddress, MeshProtocol.TYPE_MSG)

        // TorX Agent 2.0 Delivery Queue: persist-before-send crash safety + auto retry
        val agent = torXAgent
        if (agent != null) {
            agent.queueForDelivery(
                recipientKey = contactKey,
                messageId = messageId,
                messageType = com.torxone.app.agent.EnvelopeType.MSG,
                encryptedPayload = wireJson
            )
            return@withContext SendResult(true, Transport.PENDING)
        }

        // Fallback: Legacy outbox path
        db.messageOutboxDao().insertOutbox(
            com.torxone.app.data.MessageOutboxEntity(
                messageId = messageId,
                contactKey = contactKey,
                wireJson = wireJson,
                createdAt = sentAt,
                retryCount = 0,
                nextRetryAt = sentAt + 3_000L
            )
        )

        val result = if (sessionPayload != null) {
            pendingSessionPayloads[messageId] = sessionPayload.wireJsonString
            attemptDeliverySession(contact, sessionPayload.wireJsonString, messageId)
        } else {
            attemptDelivery(contact, legacyPayload!!, messageId)
        }

        if (result.success) {
            db.messageDao().updateSentMessageStatus(messageId, contactKey, "sent", result.transport.name)
            Log.i(TAG, "[SEND] id=$messageId transport=${result.transport} state=SENT awaitingAck=true")
            ensureRetryLoopRunning()
        } else {
            Log.w(TAG, "[SEND] id=$messageId delivery failed: ${result.error}. Outbox retry active.")
            ensureRetryLoopRunning()
        }
        result
    }

    private suspend fun attemptDeliverySession(
        contact: ContactEntity,
        wireJson: String,
        messageId: String
    ): SendResult {
        val connected = nearbyManager.connectedEndpoints.value
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
            Log.d(TAG, "[NEARBY-SESSION] Sending direct to ${contact.endpointId}")
            val ok = try { nearbyManager.sendRaw(contact.endpointId, wireJson) }
            catch (e: Exception) { Log.w(TAG, "[NEARBY-SESSION] Send failed: ${e.message}"); false }
            if (ok) return SendResult(true, Transport.NEARBY_DIRECT)
        }

        val onion = contact.onionAddress
        if (onion.isNotBlank() && torManager.isTorReady.value) {
            Log.d(TAG, "[TOR-SESSION] Sending session message to $onion")
            val ok = sendTorFrame(onion, wireJson, messageId)
            if (ok) return SendResult(true, Transport.TOR)
            Log.w(TAG, "[TOR-SESSION] Delivery failed to $onion")
        }

        if (connected.isNotEmpty()) {
            val relayWire = MeshProtocol.encodeSessionRelay(
                dest = contact.signingPublicKey,
                from = mySigningKeyHex,
                sessionWireJson = wireJson,
                ttl = 3,
                messageId = messageId,
                senderOnion = myOnionAddress.ifBlank { null }
            )
            val relayed = connected.any { endpoint ->
                runCatching { nearbyManager.sendRaw(endpoint, relayWire) }.getOrDefault(false)
            }
            if (relayed) return SendResult(true, Transport.NEARBY_RELAY)
        }

        return SendResult(false, Transport.FAILED, "Peer offline — move closer or wait for Tor")
    }

    private suspend fun attemptDelivery(
        contact: ContactEntity,
        payload: MeshProtocol.EncryptedPayload,
        messageId: String,
        messageType: String = MeshProtocol.TYPE_MSG
    ): SendResult {
        val connected = nearbyManager.connectedEndpoints.value
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
                val ok = try {
                nearbyManager.sendRaw(contact.endpointId, MeshProtocol.encodeDirectMessage(payload, messageId, null, messageType))
            } catch (e: Exception) { Log.w(TAG, "[NEARBY] Direct send failed: ${e.message}"); false }
            if (ok) return SendResult(true, Transport.NEARBY_DIRECT)
        }

        val onion = contact.onionAddress
        if (onion.isNotBlank() && torManager.isTorReady.value) {
            val wire = MeshProtocol.encodeDirectMessage(payload, messageId, myOnionAddress, messageType)
            if (sendTorFrame(onion, wire, messageId)) return SendResult(true, Transport.TOR)
        }

        if (connected.isNotEmpty()) {
            val wire = MeshProtocol.encodeRelayMessage(
                payload = payload,
                messageId = messageId,
                senderOnion = myOnionAddress,
                type = MeshProtocol.TYPE_RELAY,
                innerType = messageType
            )
            val relayed = connected.any { endpoint ->
                runCatching { nearbyManager.sendRaw(endpoint, wire) }.getOrDefault(false)
            }
            if (relayed) return SendResult(true, Transport.NEARBY_RELAY)
        }

        return SendResult(false, Transport.FAILED, "Peer offline — move closer or wait for Tor")
    }

    /** Send a chat/control frame over a reusable Tor socket. */
    private suspend fun sendTorFrame(onionHost: String, payload: String, messageId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!torManager.isTorReady.value) return@withContext false
        Log.d(TAG, "[TOR-OUT] id=${messageId ?: "control"} connecting to $onionHost")
        val sent = torManager.sendToOnion(onionHost, payload, messageId)
        Log.d(TAG, "[TOR-OUT] id=${messageId ?: "control"} result=${if (sent) "written" else "failed"} peer=$onionHost")
        sent
    }

    suspend fun sendGroupMessage(groupId: String, text: String, replyToId: String? = null): SendResult = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val myKey = CryptoManager.toHex(identity.signingPublicKey)
        val group = db.groupDao().getGroup(groupId) ?: return@withContext SendResult(false, Transport.FAILED, "Group not found")
        val localMembership = db.groupDao().getGroupMember(groupId, myKey)
        if (!GroupPermission.canSendMessages(group, localMembership)) return@withContext SendResult(false, Transport.FAILED, "You do not have permission to send in this group")
        val messageId = UUID.randomUUID().toString()
        val entity = MessageEntity(messageId = messageId, contactKey = groupId, conversationType = "group", senderKey = myKey, direction = "sent", status = "pending", text = text.trim(), timestamp = System.currentTimeMillis(), replyToId = replyToId)
        db.messageDao().insertMessage(entity)

        var groupKey = db.groupKeyDao().getLatestKey(groupId)
        if (groupKey == null && (group.creatorKey == myKey || group.myRole == "owner")) {
            val newKey = com.torxone.app.data.GroupKeyEntity(groupId = groupId, keyVersion = maxOf(1, group.currentKeyVersion), aesKeyBase64 = GroupCryptoManager.newKeyBase64(), distributedAt = System.currentTimeMillis())
            db.groupKeyDao().insertKey(newKey)
            groupKey = newKey
        }
        if (groupKey == null) {
            com.torxone.app.service.TorXOneService.getInstance()?.groupManager?.requestMissingKey(groupId, group.creatorKey, 1)
            return@withContext SendResult(false, Transport.PENDING, "Waiting for group key distribution")
        }

        val replyTarget = replyToId?.let { db.messageDao().getMessageById(it) }
        val innerPayload = JSONObject().apply {
            put("astraType", "chat_message")
            put("version", 1)
            put("type", "TEXT")
            put("messageId", messageId)
            put("senderKey", myKey)
            put("timestamp", System.currentTimeMillis())
            put("text", text.trim())
            replyTarget?.let { target -> put("reply", JSONObject().put("originalMessageId", target.messageId).put("originalSender", target.senderKey ?: "").put("originalType", target.messageType ?: "TEXT").put("originalPreview", target.text.take(500))) }
            put("mentions", JSONArray().apply { Regex("@([A-Za-z0-9_]{1,64})").findAll(text).forEach { match -> put(JSONObject().put("label", match.groupValues[1]).put("start", match.range.first).put("length", match.value.length)) } })
        }
        val encrypted = GroupCryptoManager.encrypt(groupKey, innerPayload.toString())
        val finalPayload = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_MESSAGE)
            put("schemaVersion", 2)
            put("groupId", groupId)
            put("keyVersion", groupKey.keyVersion)
            put("ciphertext", encrypted.ciphertextBase64)
            put("iv", encrypted.ivBase64)
        }
        val members = db.groupDao().getGroupMembersSync(groupId)
        var sentToAnyMember = false
        var failedRecipients = 0
        members.filter { it.role != "invited" }.forEach { member ->
            if (member.memberKey != myKey) {
                val result = runCatching { sendRawPayload(member.memberKey, finalPayload.toString(), MeshProtocol.TYPE_GROUP_MESSAGE) }.getOrElse { error -> SendResult(false, Transport.FAILED, error.message ?: "Group delivery failed") }
                if (result.success) sentToAnyMember = true else {
                    failedRecipients++
                    db.groupSyncDao().upsertPending(com.torxone.app.data.PendingGroupEventEntity(eventId = messageId, groupId = groupId, recipientKey = member.memberKey, payload = finalPayload.toString(), eventType = MeshProtocol.TYPE_GROUP_MESSAGE, createdAt = System.currentTimeMillis(), nextRetryAt = System.currentTimeMillis() + 30_000L, expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L))
                }
            }
        }
        val finalStatus = if (sentToAnyMember || members.count { it.role != "invited" } <= 1) "sent" else "pending"
        db.messageDao().updateMessageStatus(messageId, finalStatus)
        if (!sentToAnyMember && members.count { it.role != "invited" } > 1) SendResult(true, Transport.PENDING, "Queued for offline delivery") else SendResult(true, if (failedRecipients > 0) Transport.NEARBY_RELAY else Transport.NEARBY_DIRECT)
    }

    fun getBestTransport(contact: ContactEntity): Transport {
        val connected = nearbyManager.connectedEndpoints.value
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) return Transport.NEARBY_DIRECT
        if (contact.onionAddress.isNotBlank() && torManager.isTorReady.value) return Transport.TOR
        if (connected.isNotEmpty()) return Transport.NEARBY_RELAY
        return Transport.FAILED
    }

    suspend fun sendRawPayload(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): SendResult = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey) ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")
        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        val payload = buildEncryptedPayload(identity, contact, rawText) ?: return@withContext SendResult(false, Transport.FAILED, "Encryption failed")
        val messageId = UUID.randomUUID().toString()
        attemptDelivery(contact, payload, messageId, messageType)
    }

    suspend fun buildEncryptedWireFrame(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): String? = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext null
        val contact = db.contactDao().getContact(contactKey) ?: return@withContext null
        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) return@withContext null
        val payload = buildEncryptedPayload(identity, contact, rawText) ?: return@withContext null
        val messageId = UUID.randomUUID().toString()
        MeshProtocol.encodeDirectMessage(payload, messageId, myOnionAddress, messageType)
    }

    fun openCallTransportSession(callId: String, contact: ContactEntity, transport: Transport): com.torxone.app.call.CallTransportSession {
        return com.torxone.app.call.CallTransportSession(callId = callId, peerKey = contact.signingPublicKey, transport = transport, endpointId = contact.endpointId.takeIf { it.isNotBlank() }, onionHost = contact.onionAddress.takeIf { it.isNotBlank() }, nearbySender = { endpoint, frame -> runCatching { nearbyManager.sendRaw(endpoint, frame) }.getOrDefault(false) }, torSocketFactory = { host, port, timeout -> torManager.createTorSocket(host, port, timeout) })
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
        val result = sendReactionPacket(reaction)
        if (!result.success) { db.reactionOutboxDao().insertReaction(reaction); ensureRetryLoopRunning() }
        result
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
        var delivered = false
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != actor }.forEach { if (sendRawPayload(it.memberKey, wire, MeshProtocol.TYPE_GROUP_MESSAGE).success) delivered = true }
        SendResult(delivered, if (delivered) Transport.NEARBY_RELAY else Transport.FAILED, if (delivered) null else "No group members are reachable")
    }

    // ──────────────────────── RETRY LOOP ────────────────────────

    fun ensureRetryLoopRunning() {
        if (retryJob?.isActive == true) return
        retryBackoffMs = RETRY_INTERVAL_MS
        retryJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "[RETRY] Starting retry loop")
            var currentDelay = 1_000L
            while (isActive) {
                try { retryPendingMessages() } catch (e: Exception) { Log.e(TAG, "[RETRY] Error in retry loop", e) }
                if (!isActive) break
                delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(retryIntervalMs)
            }
        }
    }

    private suspend fun retryPendingMessages() {
        drainReceiptOutbox()

        val now = System.currentTimeMillis()
        val pendingOutbox = db.messageOutboxDao().getPendingOutboxMessages(now, limit = 50)
        val pendingReactions = db.reactionOutboxDao().getPendingReactions()
        val pendingReceipts = db.receiptOutboxDao().getPendingReceipts(now, limit = 10)

        if (pendingOutbox.isEmpty() && pendingReactions.isEmpty() && pendingReceipts.isEmpty()) {
            retryJob?.cancel()
            return
        }
        retryPendingReactions(pendingReactions)

        for (outboxMsg in pendingOutbox) {
            val localMsg = db.messageDao().getMessageById(outboxMsg.messageId)
            // If already confirmed delivered or read or deleted, clean up outbox
            if (localMsg == null || localMsg.status == "delivered" || localMsg.status == "read") {
                db.messageOutboxDao().deleteOutbox(outboxMsg.messageId)
                continue
            }

            if (outboxMsg.retryCount >= MAX_RETRIES) {
                db.messageDao().updateMessageStatus(outboxMsg.messageId, "failed")
                db.messageOutboxDao().deleteOutbox(outboxMsg.messageId)
                Log.w(TAG, "[MSG] id=${outboxMsg.messageId} state=FAILED")
                continue
            }

            val contact = db.contactDao().getContact(outboxMsg.contactKey)
            if (contact == null) {
                continue
            }

            // Retries use the already encrypted wire frame and do not mutate
            // ratchet state, so they must not wait behind a conversation's
            // encryption path.
            // Transmit EXACT stored wireJson - NEVER RE-ENCRYPT
            val result = attemptDeliverySession(contact, outboxMsg.wireJson, outboxMsg.messageId)
            val newAttempt = outboxMsg.retryCount + 1
            val backoff = (3_000L * (1L shl outboxMsg.retryCount.coerceAtMost(5))).coerceAtMost(60_000L)
            db.messageOutboxDao().updateRetry(outboxMsg.messageId, now + backoff)

            if (result.success) {
                db.messageDao().updateSentMessageStatus(outboxMsg.messageId, outboxMsg.contactKey, "sent", result.transport.name)
                Log.i(TAG, "[RETRY] id=${outboxMsg.messageId} attempt=$newAttempt transport=${result.transport} result=success")
            } else {
                db.messageDao().incrementRetryCount(outboxMsg.messageId)
                Log.w(TAG, "[RETRY] id=${outboxMsg.messageId} attempt=$newAttempt transport=${result.transport} result=failed (${result.error})")
            }
        }
    }

    private suspend fun drainReceiptOutbox() {
        val now = System.currentTimeMillis()
        val pending = db.receiptOutboxDao().getPendingReceipts(now, limit = 50)
        if (pending.isEmpty()) return
        for (receipt in pending) {
            val contact = db.contactDao().getContact(receipt.recipientKey)
            val ok = dispatchReceipt(receipt, null, contact?.onionAddress)
            if (ok) {
                db.receiptOutboxDao().deleteReceipt(receipt.id)
                Log.i(TAG, "[RECEIPT_RETRY] id=${receipt.messageId} attempt=${receipt.retryCount + 1} result=success")
            } else {
                val newCount = receipt.retryCount + 1
                if (newCount >= MAX_RETRIES) {
                    db.receiptOutboxDao().deleteReceipt(receipt.id)
                } else {
                    val backoff = (3_000L * (1L shl receipt.retryCount.coerceAtMost(5))).coerceAtMost(60_000L)
                    db.receiptOutboxDao().updateRetry(receipt.id, now + backoff)
                }
                Log.w(TAG, "[RECEIPT_RETRY] id=${receipt.messageId} attempt=$newCount result=failed")
            }
        }
    }

    fun setBackgroundRetryInterval(intervalMs: Long) { retryIntervalMs = intervalMs.coerceIn(2_000L, 120_000L) }
    fun retryPendingNow() { scope.launch(Dispatchers.IO) { runCatching { retryPendingMessages() }.onFailure { Log.e(TAG, "[RETRY] Immediate retry failed", it) } } }

    private suspend fun retryPendingReactions(pending: List<ReactionOutboxEntity>) {
        pending.forEach { reaction -> val result = sendReactionPacket(reaction); if (result.success) db.reactionOutboxDao().deleteReaction(reaction.reactionId) else db.reactionOutboxDao().incrementRetry(reaction.reactionId) }
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
        scope.launch(Dispatchers.IO) {
            val existing = db.receiptOutboxDao().getPendingReceiptsForRecipient(recipientKey)
                .firstOrNull { it.messageId == messageId && it.type == type }
            val receipt = existing ?: com.torxone.app.data.ReceiptOutboxEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                recipientKey = recipientKey,
                type = type,
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                nextRetryAt = System.currentTimeMillis()
            ).also {
                db.receiptOutboxDao().insertReceipt(it)
                if (type == "ack") {
                    Log.i(TAG, "[ACK] id=$messageId queued=true")
                } else {
                    Log.i(TAG, "[READ] id=$messageId queued=true")
                }
            }

            val delivered = dispatchReceipt(receipt, viaEndpoint, senderOnion)
            if (delivered) {
                db.receiptOutboxDao().deleteReceipt(receipt.id)
                Log.i(TAG, "[RECEIPT] id=${receipt.messageId} transportAccepted=true")
            } else {
                Log.w(TAG, "[RECEIPT] id=${receipt.messageId} transportAccepted=false retryQueued=true")
                ensureRetryLoopRunning()
            }
        }
    }

    private suspend fun dispatchReceipt(
        receipt: com.torxone.app.data.ReceiptOutboxEntity,
        viaEndpoint: String? = null,
        senderOnion: String? = null
    ): Boolean {
        val wire = if (receipt.type == "read") {
            MeshProtocol.encodeRead(receipt.messageId, mySigningKeyHex, receipt.recipientKey, myOnionAddress)
        } else {
            MeshProtocol.encodeAck(receipt.messageId, mySigningKeyHex, receipt.recipientKey, myOnionAddress)
        }
        val contact = db.contactDao().getContact(receipt.recipientKey)
        val connected = nearbyManager.connectedEndpoints.value

        // 1. Direct Nearby to recipient
        if (viaEndpoint != null && connected.contains(viaEndpoint) && contact?.endpointId == viaEndpoint) {
            val ok = runCatching { nearbyManager.sendRaw(viaEndpoint, wire) }.getOrDefault(false)
            if (ok) return true
        }
        if (contact?.endpointId?.isNotBlank() == true && connected.contains(contact.endpointId)) {
            val ok = runCatching { nearbyManager.sendRaw(contact.endpointId, wire) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Tor
        val onion = if (!senderOnion.isNullOrBlank()) senderOnion else contact?.onionAddress
        if (!onion.isNullOrBlank() && torManager.isTorReady.value) {
            val ok = sendTorFrame(onion, wire, receipt.messageId)
            if (ok) return true
        }

        // 3. Mesh relay broadcast to any connected endpoints
        if (connected.isNotEmpty()) {
            var anySent = false
            connected.forEach { endpoint ->
                if (runCatching { nearbyManager.sendRaw(endpoint, wire) }.getOrDefault(false)) {
                    anySent = true
                }
            }
            if (anySent) return true
        }

        return false
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
        db.messageOutboxDao().deleteOutbox(messageId)
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
        db.messageOutboxDao().deleteOutbox(messageId)
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
        val ackWire = MeshProtocol.encodeAck(messageId, mySigningKeyHex, senderKey, myOnionAddress)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val contact = db.contactDao().getContact(senderKey) ?: return@runCatching
                val connected = nearbyManager.connectedEndpoints.value
                if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) nearbyManager.sendRaw(contact.endpointId, ackWire)
                else if (!contact.onionAddress.isNullOrBlank() && torManager.isTorReady.value) sendTorFrame(contact.onionAddress, ackWire)
            }
        }
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
        val senderOnion = json.optString("senderOnion", "")
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

    internal suspend fun handleSessionMessage(viaEndpoint: String?, json: JSONObject) {
        val to = json.optString("to", "").trim().lowercase()
        val ttl = json.optInt("ttl", 3)
        val fromKey = json.optString("from", "").trim().lowercase()
        val messageId = json.optString("msgId", "")
        val senderOnion = json.optString("senderOnion", "")
        if (to == mySigningKeyHex) {
            val sm = sessionManager ?: return
            val contact = db.contactDao().getContact(fromKey)
            if (contact != null && senderOnion.isNotBlank() && contact.onionAddress != senderOnion) db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))

            // Fast deduplication: If message is already in DB, resend ACK and skip decryption
            if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
                Log.d(TAG, "[SESSION_RX] Duplicate message $messageId already in DB from ${contact?.name ?: fromKey}; sending ACK")
                sendAck(messageId, fromKey, viaEndpoint, senderOnion)
                return
            }

            val decrypted = try { 
                sm.decrypt(fromKey, json) 
            } catch (e: Exception) { 
                Log.w(TAG, "[SESSION_RX] Decrypt failed from ${contact?.name ?: fromKey}: ${e.message}")
                if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
                    sendAck(messageId, fromKey, viaEndpoint, senderOnion)
                }
                return 
            }
            Log.i(TAG, "[RX] id=$messageId decrypted=true")
            dispatchDecryptedMessage(contact, fromKey, decrypted.plaintext, decrypted.messageType, messageId, viaEndpoint, senderOnion)
            return
        }
        if (viaEndpoint != null && ttl > 1) {
            val fingerprint = "relay_session:$to:$messageId"
            if (!rememberRelayFingerprint(fingerprint)) return
            json.put("ttl", ttl - 1)
            val relayWire = MeshProtocol.encodeSessionRelay(dest = to, from = fromKey, sessionWireJson = json.toString(), ttl = ttl - 1, messageId = messageId, senderOnion = senderOnion.ifBlank { null })
            nearbyManager.connectedEndpoints.value.filter { it != viaEndpoint }.forEach { runCatching { nearbyManager.sendRaw(it, relayWire) } }
        }
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
