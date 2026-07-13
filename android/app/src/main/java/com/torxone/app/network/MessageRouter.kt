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
import java.util.UUID

enum class Transport { NEARBY_DIRECT, NEARBY_RELAY, TOR, FAILED }

data class SendResult(val success: Boolean, val transport: Transport, val error: String? = null)

/**
 * Routes messages: Nearby (direct) → Nearby mesh relay → Tor (.onion).
 * Includes ACK-based delivery confirmation and automatic retry for failed sends.
 */
class MessageRouter(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val nearbyManager: NearbyConnectionManager,
    private val torManager: TorManager
) {
    companion object {
        private const val TAG = "MessageRouter"
        private const val RETRY_INTERVAL_MS = 30_000L // 30 seconds
        private const val MAX_RETRIES = 40
        private const val RELAY_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RELAY_CACHE_SIZE = 512
    }

    var identity: Identity? = null
    var mySigningKeyHex: String = ""
    var myOnionAddress: String = ""

    private var retryJob: Job? = null
    @Volatile
    private var retryIntervalMs: Long = RETRY_INTERVAL_MS
    private val recentRelayFingerprints = LinkedHashMap<String, Long>()

    // ──────────────────────── INCOMING HANDLERS ────────────────────────

    fun handleNearbyPayload(endpointId: String, raw: String) {
        val json = MeshProtocol.parse(raw) ?: return

        when (json.optString("type")) {
            MeshProtocol.TYPE_HELLO -> handleHello(endpointId, json.optString("contact"))
            MeshProtocol.TYPE_MSG -> scope.launch(Dispatchers.IO) { handleEncrypted(json, endpointId, MeshProtocol.TYPE_MSG) }
            MeshProtocol.TYPE_MEDIA_OFFER,
            MeshProtocol.TYPE_MEDIA_CHUNK,
            MeshProtocol.TYPE_MEDIA_ACK,
            MeshProtocol.TYPE_MEDIA_COMPLETE,
            MeshProtocol.TYPE_CALL_OFFER,
            MeshProtocol.TYPE_CALL_ANSWER,
            MeshProtocol.TYPE_ICE_CANDIDATE,
            MeshProtocol.TYPE_REACTION,
            MeshProtocol.TYPE_PRESENCE,
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK,
            MeshProtocol.TYPE_MUSIC_NOTE,
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
            MeshProtocol.TYPE_MSG -> scope.launch(Dispatchers.IO) { handleEncrypted(json, null, MeshProtocol.TYPE_MSG) }
            MeshProtocol.TYPE_MEDIA_OFFER,
            MeshProtocol.TYPE_MEDIA_CHUNK,
            MeshProtocol.TYPE_MEDIA_ACK,
            MeshProtocol.TYPE_MEDIA_COMPLETE,
            MeshProtocol.TYPE_CALL_OFFER,
            MeshProtocol.TYPE_CALL_ANSWER,
            MeshProtocol.TYPE_ICE_CANDIDATE,
            MeshProtocol.TYPE_REACTION,
            MeshProtocol.TYPE_PRESENCE,
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK,
            MeshProtocol.TYPE_MUSIC_NOTE,
            MeshProtocol.TYPE_MUSIC_SYNC -> scope.launch(Dispatchers.IO) { handleEncrypted(json, null, json.optString("type")) }
            MeshProtocol.TYPE_RELAY -> scope.launch(Dispatchers.IO) { handleRelay(null, json) }
            MeshProtocol.TYPE_ACK -> scope.launch(Dispatchers.IO) { handleAck(json, null) }
            MeshProtocol.TYPE_READ -> scope.launch(Dispatchers.IO) { handleRead(json, null) }
            MeshProtocol.TYPE_PING -> handlePing(json, null)
            MeshProtocol.TYPE_PONG -> handlePong(json)
        }
    }

    private fun handlePing(json: JSONObject, viaEndpoint: String?) {
        val timestamp = json.optLong("timestamp", 0)
        val fromOnion = json.optString("from", "")
        if (timestamp > 0) {
            val pongWire = MeshProtocol.encodePong(timestamp)
            if (viaEndpoint != null) {
                nearbyManager.sendRaw(viaEndpoint, pongWire)
            } else if (fromOnion.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    torManager.sendToOnion(fromOnion, pongWire)
                }
            }
        }
    }

    private fun handlePong(json: JSONObject) {
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

        val wireText = encodeChatMessagePayload(
            text = text,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            replyToType = replyToType
        )

        val payload = buildEncryptedPayload(identity, contact, wireText)
            ?: return@withContext SendResult(false, Transport.FAILED, "Encryption failed")

        val messageId = UUID.randomUUID().toString()

        // Save the message as PENDING first
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = messageId,
                contactKey = contactKey,
                text = text,
                timestamp = System.currentTimeMillis(),
                direction = "sent",
                status = "pending",
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSender = replyToSender,
                replyToType = replyToType
            )
        )
        Log.d(TAG, "[SEND] Message $messageId queued for $contactKey")

        // Try immediate delivery
        val result = attemptDelivery(contact, payload, messageId)

        if (result.success) {
            db.messageDao().updateMessageStatus(messageId, "sent", result.transport.name)
            Log.d(TAG, "[SEND] Message $messageId sent via ${result.transport}")
        } else {
            Log.w(TAG, "[SEND] Message $messageId delivery failed: ${result.error}. Will retry.")
            ensureRetryLoopRunning()
        }

        result
    }

    private fun attemptDelivery(
        contact: ContactEntity,
        payload: MeshProtocol.EncryptedPayload,
        messageId: String,
        messageType: String = MeshProtocol.TYPE_MSG
    ): SendResult {
        val connected = nearbyManager.connectedEndpoints.value

        // 1. Try direct Nearby
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
            Log.d(TAG, "[NEARBY] Sending direct to ${contact.endpointId}")
            nearbyManager.sendRaw(contact.endpointId, MeshProtocol.encodeDirectMessage(payload, messageId, null, messageType))
            return SendResult(true, Transport.NEARBY_DIRECT)
        }

        // 2. Try Nearby relay (flood to all connected peers)
        if (connected.isNotEmpty()) {
            Log.d(TAG, "[NEARBY] Relaying to ${connected.size} peers")
            val wire = MeshProtocol.encodeRelayMessage(
                payload = payload,
                messageId = messageId,
                senderOnion = myOnionAddress,
                type = MeshProtocol.TYPE_RELAY,
                innerType = messageType
            )
            connected.forEach { nearbyManager.sendRaw(it, wire) }
            return SendResult(true, Transport.NEARBY_RELAY)
        }

        // 3. Try Tor
        val onion = contact.onionAddress
        if (onion.isNotBlank() && torManager.isTorReady.value) {
            Log.d(TAG, "[TOR] Sending message: ${payload.ciphertextHex.take(20)}...")
            val ok = torManager.sendToOnion(onion, MeshProtocol.encodeDirectMessage(payload, messageId, myOnionAddress, messageType))
            if (ok) {
                Log.d(TAG, "[TOR] Message $messageId delivered to $onion")
                return SendResult(true, Transport.TOR)
            }
            Log.w(TAG, "[TOR] Delivery failed")
            return SendResult(false, Transport.TOR, "Tor delivery failed")
        }

        Log.w(TAG, "[SEND] No transport available for ${contact.name}")
        return SendResult(false, Transport.FAILED, "Peer offline — move closer or wait for Tor")
    }

    fun getBestTransport(contact: ContactEntity): Transport {
        val connected = nearbyManager.connectedEndpoints.value
        if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
            return Transport.NEARBY_DIRECT
        }
        if (connected.isNotEmpty()) {
            return Transport.NEARBY_RELAY
        }
        val onion = contact.onionAddress
        if (onion.isNotBlank() && torManager.isTorReady.value) {
            return Transport.TOR
        }
        return Transport.FAILED
    }

    suspend fun sendRawPayload(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): SendResult = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey)
            ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")

        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) {
            return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        }

        val payload = buildEncryptedPayload(identity, contact, rawText)
            ?: return@withContext SendResult(false, Transport.FAILED, "Encryption failed")

        val messageId = UUID.randomUUID().toString()
        attemptDelivery(contact, payload, messageId, messageType)
    }

    suspend fun toggleReaction(contactKey: String, targetMessageId: String, emoji: String): SendResult = withContext(Dispatchers.IO) {
        val actorKey = mySigningKeyHex.ifBlank {
            identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty()
        }
        if (actorKey.isBlank()) return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        if (emoji.isBlank()) return@withContext SendResult(false, Transport.FAILED, "Reaction is blank")

        val target = db.messageDao().getMessageById(targetMessageId)
            ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")

        val current = parseReactionMap(target.reactionsJson)
        val mine = current[actorKey]?.firstOrNull()
        val action = if (mine == emoji) "remove" else "set"
        applyReactionToMessage(targetMessageId, actorKey, emoji, action)

        val reaction = ReactionOutboxEntity(
            reactionId = UUID.randomUUID().toString(),
            contactKey = contactKey,
            targetMessageId = targetMessageId,
            emoji = emoji,
            action = action,
            createdAt = System.currentTimeMillis()
        )

        val result = sendReactionPacket(reaction)
        if (!result.success) {
            db.reactionOutboxDao().insertReaction(reaction)
            ensureRetryLoopRunning()
        }
        result
    }

    // ──────────────────────── RETRY LOOP ────────────────────────

    fun ensureRetryLoopRunning() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "[RETRY] Starting retry loop")
            while (isActive) {
                delay(retryIntervalMs)
                try {
                    retryPendingMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "[RETRY] Error in retry loop", e)
                }
            }
        }
    }

    private suspend fun retryPendingMessages() {
        val pending = db.messageDao().getPendingMessages()
        val pendingReactions = db.reactionOutboxDao().getPendingReactions()
        if (pending.isEmpty() && pendingReactions.isEmpty()) {
            Log.d(TAG, "[RETRY] No pending messages, stopping retry loop")
            retryJob?.cancel()
            return
        }

        retryPendingReactions(pendingReactions)

        Log.d(TAG, "[RETRY] Retrying ${pending.size} pending messages")
        for (msg in pending) {
            val identity = identity ?: continue
            val contact = db.contactDao().getContact(msg.contactKey) ?: continue

            val wireText = encodeChatMessagePayload(
                text = msg.text,
                replyToId = msg.replyToId,
                replyToText = msg.replyToText,
                replyToSender = msg.replyToSender,
                replyToType = msg.replyToType
            )
            val payload = buildEncryptedPayload(identity, contact, wireText) ?: continue
            val result = attemptDelivery(contact, payload, msg.messageId)

            if (result.success) {
                db.messageDao().updateMessageStatus(msg.messageId, "sent", result.transport.name)
                Log.d(TAG, "[RETRY] Message ${msg.messageId} resent successfully via ${result.transport}")
            } else {
                db.messageDao().incrementRetryCount(msg.messageId)
                val newCount = msg.retryCount + 1
                Log.w(TAG, "[RETRY] Message ${msg.messageId} retry #$newCount failed")
                if (newCount >= MAX_RETRIES) {
                    db.messageDao().updateMessageStatus(msg.messageId, "failed")
                    Log.e(TAG, "[RETRY] Message ${msg.messageId} permanently failed after $MAX_RETRIES retries")
                }
            }
        }
    }

    fun setBackgroundRetryInterval(intervalMs: Long) {
        retryIntervalMs = intervalMs.coerceIn(10_000L, 120_000L)
    }

    fun retryPendingNow() {
        scope.launch(Dispatchers.IO) {
            try {
                retryPendingMessages()
            } catch (e: Exception) {
                Log.e(TAG, "[RETRY] Immediate retry failed", e)
            }
        }
    }

    private suspend fun retryPendingReactions(pending: List<ReactionOutboxEntity>) {
        if (pending.isEmpty()) return
        Log.d(TAG, "[RETRY] Retrying ${pending.size} pending reactions")
        pending.forEach { reaction ->
            val result = sendReactionPacket(reaction)
            if (result.success) {
                db.reactionOutboxDao().deleteReaction(reaction.reactionId)
            } else {
                db.reactionOutboxDao().incrementRetry(reaction.reactionId)
            }
        }
    }

    // ──────────────────────── ACK HANDLING ────────────────────────

    private suspend fun handleAck(json: JSONObject, viaEndpoint: String?) {
        if (forwardReceiptIfNeeded(json, viaEndpoint)) return

        val messageId = json.optString("msgId")
        val senderKey = json.optString("from", "").trim().lowercase()
        if (messageId.isBlank()) return
        if (senderKey.isBlank()) return

        val existing = db.messageDao().getMessageById(messageId) ?: return
        if (existing.direction != "sent" || existing.contactKey != senderKey) {
            Log.w(TAG, "[ACK] Ignoring receipt from non-recipient for message $messageId")
            return
        }

        Log.d(TAG, "[TOR] ACK received for message $messageId")
        Log.d(TAG, "[TOR] Delivery complete")
        db.messageDao().updateSentMessageStatus(messageId, senderKey, "delivered")

        // Update sender's onion if provided in the ACK
        val senderOnion = json.optString("senderOnion", "")
        if (senderKey.isNotBlank() && senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) {
                Log.d(TAG, "[ACK] Updating sender's onion address to ${senderOnion.take(20)}...")
                db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
            }
        }
    }

    private fun sendAck(messageId: String, senderKey: String, viaEndpoint: String?, senderOnion: String? = null) {
        if (messageId.isBlank()) return
        val ackWire = MeshProtocol.encodeAck(messageId, mySigningKeyHex, senderKey, myOnionAddress)
        Log.d(TAG, "[ACK] Sending ACK for $messageId to $senderKey (viaEndpoint=$viaEndpoint, senderOnion=${senderOnion?.take(20)})")
        val contact = db.contactDao().getContact(senderKey)
        val connected = nearbyManager.connectedEndpoints.value

        // Send ACK back via the same transport it arrived on
        if (contact?.endpointId?.isNotBlank() == true && connected.contains(contact.endpointId)) {
            nearbyManager.sendRaw(contact.endpointId, ackWire)
        } else if (viaEndpoint != null) {
            nearbyManager.sendRaw(viaEndpoint, ackWire)
        } else if (connected.isNotEmpty()) {
            connected.forEach { nearbyManager.sendRaw(it, ackWire) }
        } else {
            // Came via Tor — send ACK back via Tor
            scope.launch(Dispatchers.IO) {
                try {
                    // Prefer the senderOnion from the wire (most reliable)
                    val onion = if (!senderOnion.isNullOrBlank()) {
                        senderOnion
                    } else {
                        contact?.onionAddress
                    }
                    if (!onion.isNullOrBlank() && torManager.isTorReady.value) {
                        val ok = torManager.sendToOnion(onion, ackWire)
                        Log.d(TAG, "[ACK] Tor ACK send result: $ok")
                    } else {
                        Log.w(TAG, "[ACK] Cannot send ACK via Tor — no onion address for sender")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[ACK] Error sending ACK via Tor", e)
                }
            }
        }
    }

    private suspend fun handleRead(json: JSONObject, viaEndpoint: String?) {
        if (forwardReceiptIfNeeded(json, viaEndpoint)) return

        val messageId = json.optString("msgId")
        val senderKey = json.optString("from", "").trim().lowercase()
        if (messageId.isBlank()) return
        if (senderKey.isBlank()) return

        val existing = db.messageDao().getMessageById(messageId) ?: return
        if (existing.direction != "sent" || existing.contactKey != senderKey) {
            Log.w(TAG, "[READ] Ignoring receipt from non-recipient for message $messageId")
            return
        }

        Log.d(TAG, "[READ] Received read receipt for message $messageId")
        db.messageDao().updateSentMessageStatus(messageId, senderKey, "read")

        // Update sender's onion if provided in the READ receipt
        val senderOnion = json.optString("senderOnion", "")
        if (senderKey.isNotBlank() && senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) {
                Log.d(TAG, "[READ] Updating sender's onion address to ${senderOnion.take(20)}...")
                db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
            }
        }
    }

    fun sendReadReceipt(messageId: String, senderKey: String) {
        if (messageId.isBlank()) return
        val readWire = MeshProtocol.encodeRead(messageId, mySigningKeyHex, senderKey, myOnionAddress)
        Log.d(TAG, "[READ] Sending READ receipt for $messageId to $senderKey")

        scope.launch(Dispatchers.IO) {
            try {
                val contact = db.contactDao().getContact(senderKey) ?: return@launch
                val connected = nearbyManager.connectedEndpoints.value
                
                if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
                    nearbyManager.sendRaw(contact.endpointId, readWire)
                } else if (connected.isNotEmpty()) {
                    connected.forEach { nearbyManager.sendRaw(it, readWire) }
                } else {
                    val onion = contact.onionAddress
                    if (!onion.isNullOrBlank() && torManager.isTorReady.value) {
                        torManager.sendToOnion(onion, readWire)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[READ] Error sending READ receipt", e)
            }
        }
    }

    private fun forwardReceiptIfNeeded(json: JSONObject, viaEndpoint: String?): Boolean {
        val destination = json.optString("to", "").trim().lowercase()
        if (destination.isBlank() || destination == mySigningKeyHex) return false

        val ttl = json.optInt("ttl", MeshProtocol.DEFAULT_TTL)
        if (ttl <= 1) return true

        val forwarded = JSONObject(json.toString())
            .put("ttl", ttl - 1)
            .toString()
        nearbyManager.connectedEndpoints.value
            .filter { it != viaEndpoint }
            .forEach { nearbyManager.sendRaw(it, forwarded) }
        return true
    }

    fun sendMediaAck(messageId: String, senderKey: String) {
        if (messageId.isBlank()) return
        val ackWire = MeshProtocol.encodeAck(messageId, mySigningKeyHex, senderKey, myOnionAddress)
        Log.d(TAG, "[MEDIA_ACK] Sending ACK for $messageId to $senderKey")

        scope.launch(Dispatchers.IO) {
            try {
                val contact = db.contactDao().getContact(senderKey) ?: return@launch
                val connected = nearbyManager.connectedEndpoints.value
                
                if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
                    nearbyManager.sendRaw(contact.endpointId, ackWire)
                } else {
                    val onion = contact.onionAddress
                    if (!onion.isNullOrBlank() && torManager.isTorReady.value) {
                        torManager.sendToOnion(onion, ackWire)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[MEDIA_ACK] Error sending MEDIA ACK", e)
            }
        }
    }

    // ──────────────────────── HELLO EXCHANGE ────────────────────────

    fun broadcastHello(endpointId: String) {
        val identity = identity ?: return
        val contactString = CryptoManager.createContactString(identity, myOnionAddress.ifBlank { null })
        nearbyManager.sendRaw(endpointId, MeshProtocol.encodeHello(contactString))
        Log.d(TAG, "[HELLO] Sent hello to $endpointId with onion=${myOnionAddress.take(20)}...")
    }

    private fun handleHello(endpointId: String, contactString: String) {
        if (contactString.isBlank()) return
        val parsed = CryptoManager.parseContactString(contactString) ?: return

        Log.d(TAG, "[HELLO] Received hello from ${parsed.name} (onion=${parsed.onionAddress?.take(20) ?: "none"})")
        if (!parsed.onionAddress.isNullOrBlank()) {
            Log.d(TAG, "[TOR] Received peer onion: ${parsed.onionAddress}")
        }

        scope.launch(Dispatchers.IO) {
            db.contactDao().insertContact(
                ContactEntity(
                    signingPublicKey = CryptoManager.toHex(parsed.signingPublicKey),
                    encryptionPublicKey = CryptoManager.toHex(parsed.encryptionPublicKey),
                    name = parsed.name,
                    endpointId = endpointId,
                    onionAddress = parsed.onionAddress ?: "",
                    isConnected = true
                )
            )
            
            // Broadcast our profile to the newly connected peer
            val service = com.torxone.app.service.TorXOneService.getInstance()
            service?.profileSyncManager?.broadcastLocalProfile(CryptoManager.toHex(parsed.signingPublicKey))
        }
    }

    // ──────────────────────── RELAY ────────────────────────

    private suspend fun handleRelay(fromEndpointId: String?, json: JSONObject) {
        val dest = json.optString("dest")
        val ttl = json.optInt("ttl", 0)
        val payload = MeshProtocol.parseEncrypted(json) ?: return
        val messageId = json.optString("msgId", "")
        val senderOnion = json.optString("senderOnion", "")
        val innerType = json.optString("innerType", MeshProtocol.TYPE_MSG)
        val fingerprint = "${payload.fromSigningKey}:${payload.toSigningKey}:${payload.nonceHex}:${payload.signatureHex}"

        if (dest == mySigningKeyHex) {
            handleEncrypted(json, fromEndpointId, innerType)
            return
        }

        if (ttl <= 1) return
        if (!rememberRelayFingerprint(fingerprint)) return

        val wire = MeshProtocol.encodeRelayMessage(payload, ttl - 1, messageId, senderOnion, innerType = innerType)
        val connected = nearbyManager.connectedEndpoints.value
        connected.filter { it != fromEndpointId }.forEach { nearbyManager.sendRaw(it, wire) }
    }

    // ──────────────────────── DECRYPTION ────────────────────────

    private suspend fun handleEncrypted(json: JSONObject, viaEndpoint: String?, messageType: String) {
        val payload = MeshProtocol.parseEncrypted(json) ?: return
        val identity = identity ?: return
        val messageId = json.optString("msgId", "")
        val senderOnion = json.optString("senderOnion", "")

        if (payload.toSigningKey != mySigningKeyHex) {
            Log.w(TAG, "[RECV] Ignoring message addressed to another identity")
            return
        }

        val senderKey = payload.fromSigningKey
        if (senderKey == mySigningKeyHex) {
            Log.w(TAG, "[RECV] Ignoring message that claims to be from this identity")
            return
        }

        val contact = db.contactDao().getContact(senderKey) ?: run {
            Log.w(TAG, "[RECV] Unknown sender: ${senderKey.take(20)}...")
            return
        }

        // Update the contact's onion address if we received it and it's new
        if (senderOnion.isNotBlank() && contact.onionAddress != senderOnion) {
            Log.d(TAG, "[RECV] Updating sender's onion address to ${senderOnion.take(20)}...")
            db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
        }

        if (contact.encryptionPublicKey.isBlank()) return

        val ciphertext = CryptoManager.fromHexOrNull(payload.ciphertextHex) ?: return
        val nonce = CryptoManager.fromHexOrNull(payload.nonceHex, 24) ?: return
        val signature = CryptoManager.fromHexOrNull(payload.signatureHex, 64) ?: return
        val senderSigPub = CryptoManager.fromHexOrNull(senderKey, 32) ?: return
        val senderEncPub = CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) ?: return

        if (!CryptoManager.verify(ciphertext + nonce, signature, senderSigPub)) {
            Log.w(TAG, "[RECV] Signature verification failed from ${contact.name}")
            return
        }

        val plaintext = try {
            CryptoManager.decryptMessage(ciphertext, nonce, senderEncPub, identity.encryptionSecretKey)
        } catch (e: Exception) {
            Log.w(TAG, "[RECV] Decryption failed from ${contact.name}", e)
            return
        }

        val service = com.torxone.app.service.TorXOneService.getInstance()
        if (messageType != MeshProtocol.TYPE_PROFILE_UPDATE &&
            messageType != MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO &&
            messageType != MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK) {
            service?.profileSyncManager?.syncWithContactSoon(senderKey)
        }

        if (messageType == MeshProtocol.TYPE_MEDIA_CHUNK || 
            messageType == MeshProtocol.TYPE_MEDIA_OFFER || 
            messageType == MeshProtocol.TYPE_MEDIA_ACK ||
            messageType == MeshProtocol.TYPE_MEDIA_COMPLETE) {
            service?.mediaTransferManager?.handleMediaPacket(messageType, plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_CALL_OFFER ||
            messageType == MeshProtocol.TYPE_CALL_ANSWER ||
            messageType == MeshProtocol.TYPE_ICE_CANDIDATE) {
            service?.callManager?.handleSignal(messageType, plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_PROFILE_UPDATE ||
            messageType == MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO ||
            messageType == MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK) {
            service?.profileSyncManager?.handleProfilePacket(messageType, plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_REACTION) {
            handleReactionPacket(plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_PRESENCE) {
            service?.presenceManager?.handlePresencePacket(plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_MUSIC_NOTE) {
            service?.musicNoteManager?.handleMusicNotePacket(plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_MUSIC_SYNC) {
            service?.listenTogetherManager?.handleSyncPacket(plaintext, senderKey)
            return
        }

        val chatPayload = decodeChatMessagePayload(plaintext)

        Log.d(TAG, "[RECV] Message from ${contact.name} (${chatPayload.text.length} chars)")

        if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
            Log.d(TAG, "[RECV] Duplicate message ignored: $messageId")
            sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            return
        }

        val isActiveConversation = com.torxone.app.service.ActiveConversationTracker.isActive(senderKey)

        db.messageDao().insertMessage(
            MessageEntity(
                messageId = if (messageId.isNotBlank()) messageId else UUID.randomUUID().toString(),
                contactKey = senderKey,
                text = chatPayload.text,
                timestamp = System.currentTimeMillis(),
                direction = "received",
                status = "delivered",
                replyToId = chatPayload.replyToId,
                replyToText = chatPayload.replyToText,
                replyToSender = chatPayload.replyToSender,
                replyToType = chatPayload.replyToType
            )
        )

        if (service != null) {
            val isMuted = contact.muteUntil == -1L || (contact.muteUntil > 0L && System.currentTimeMillis() < contact.muteUntil)
            if (isActiveConversation) {
                com.torxone.app.service.NotificationHelper.clearContactNotifications(service, senderKey)
            } else if (!isMuted) {
                val unreadMsgs = db.messageDao().getUnreadMessagesSync(senderKey)
                com.torxone.app.service.NotificationHelper.showMessageNotification(service, contact, unreadMsgs)
            }
        }

        // Send ACK back to sender
        if (messageId.isNotBlank()) {
            sendAck(messageId, senderKey, viaEndpoint, senderOnion)
        }
    }

    // ──────────────────────── ENCRYPTION ────────────────────────

    private fun buildEncryptedPayload(
        identity: Identity,
        contact: ContactEntity,
        text: String
    ): MeshProtocol.EncryptedPayload? {
        return try {
            if (contact.encryptionPublicKey.isBlank()) return null
            val encPub = CryptoManager.fromHex(contact.encryptionPublicKey)
            val (ciphertext, nonce) = CryptoManager.encryptMessage(
                text, encPub, identity.encryptionSecretKey
            )
            val signature = CryptoManager.sign(ciphertext + nonce, identity.signingSecretKey)
            MeshProtocol.EncryptedPayload(
                fromSigningKey = mySigningKeyHex,
                toSigningKey = contact.signingPublicKey,
                ciphertextHex = CryptoManager.toHex(ciphertext),
                nonceHex = CryptoManager.toHex(nonce),
                signatureHex = CryptoManager.toHex(signature)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CRYPTO] buildEncryptedPayload failed", e)
            null
        }
    }

    private suspend fun sendReactionPacket(reaction: ReactionOutboxEntity): SendResult {
        val actorKey = mySigningKeyHex.ifBlank {
            identity?.signingPublicKey?.let { CryptoManager.toHex(it) }.orEmpty()
        }
        val payload = JSONObject()
            .put("astraType", "reaction")
            .put("version", 1)
            .put("reactionId", reaction.reactionId)
            .put("targetMessageId", reaction.targetMessageId)
            .put("emoji", reaction.emoji)
            .put("action", reaction.action)
            .put("actorKey", actorKey)
            .put("createdAt", reaction.createdAt)
            .toString()
        return sendRawPayload(reaction.contactKey, payload, MeshProtocol.TYPE_REACTION)
    }

    private fun handleReactionPacket(raw: String, senderKey: String) {
        runCatching {
            val json = JSONObject(raw)
            val targetMessageId = json.getString("targetMessageId")
            val emoji = json.getString("emoji")
            val action = json.optString("action", "add")
            applyReactionToMessage(targetMessageId, senderKey, emoji, action)
        }.onFailure { e ->
            Log.w(TAG, "Invalid reaction packet", e)
        }
    }

    private fun applyReactionToMessage(messageId: String, actorKey: String, emoji: String, action: String) {
        if (messageId.isBlank() || actorKey.isBlank() || emoji.isBlank()) return
        val message = db.messageDao().getMessageById(messageId) ?: return
        val reactions = parseReactionMap(message.reactionsJson)
        val actorReactions = reactions[actorKey]?.toMutableSet() ?: linkedSetOf()
        when (action) {
            "remove" -> actorReactions.remove(emoji)
            "set" -> {
                actorReactions.clear()
                actorReactions.add(emoji)
            }
            else -> {
                actorReactions.clear()
                actorReactions.add(emoji)
            }
        }

        if (actorReactions.isEmpty()) {
            reactions.remove(actorKey)
        } else {
            reactions[actorKey] = actorReactions.toList()
        }
        db.messageDao().updateReactions(messageId, encodeReactionMap(reactions))
    }

    private fun parseReactionMap(raw: String?): MutableMap<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        if (raw.isNullOrBlank()) return result
        return runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { actor ->
                val value = json.get(actor)
                result[actor] = when (value) {
                    is org.json.JSONArray -> List(value.length()) { index -> value.optString(index) }
                        .filter { it.isNotBlank() }
                    is String -> listOf(value).filter { it.isNotBlank() }
                    else -> emptyList()
                }
            }
            result
        }.getOrElse { result }
    }

    private fun encodeReactionMap(reactions: Map<String, List<String>>): String? {
        if (reactions.isEmpty()) return null
        val json = JSONObject()
        reactions.forEach { (actor, emojis) ->
            val array = org.json.JSONArray()
            emojis.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }
            if (array.length() > 0) json.put(actor, array)
        }
        return if (json.length() == 0) null else json.toString()
    }

    private data class ChatMessagePayload(
        val text: String,
        val replyToId: String? = null,
        val replyToText: String? = null,
        val replyToSender: String? = null,
        val replyToType: String? = null
    )

    private fun encodeChatMessagePayload(
        text: String,
        replyToId: String?,
        replyToText: String?,
        replyToSender: String?,
        replyToType: String?
    ): String {
        if (replyToId.isNullOrBlank()) return text
        return JSONObject()
            .put("astraType", "chat_message")
            .put("version", 1)
            .put("text", text)
            .put(
                "reply",
                JSONObject()
                    .put("originalMessageId", replyToId)
                    .put("originalSender", replyToSender ?: "")
                    .put("originalType", replyToType ?: "TEXT")
                    .put("originalPreview", replyToText ?: "")
            )
            .toString()
    }

    private fun decodeChatMessagePayload(raw: String): ChatMessagePayload {
        return runCatching {
            val json = JSONObject(raw)
            if (json.optString("astraType") != "chat_message") {
                return@runCatching ChatMessagePayload(raw)
            }
            val reply = json.optJSONObject("reply")
            ChatMessagePayload(
                text = json.optString("text", ""),
                replyToId = reply?.optString("originalMessageId")?.takeIf { it.isNotBlank() },
                replyToSender = reply?.optString("originalSender")?.takeIf { it.isNotBlank() },
                replyToType = reply?.optString("originalType")?.takeIf { it.isNotBlank() },
                replyToText = reply?.optString("originalPreview")?.takeIf { it.isNotBlank() }
            )
        }.getOrElse {
            ChatMessagePayload(raw)
        }
    }

    private fun rememberRelayFingerprint(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val iterator = recentRelayFingerprints.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > RELAY_CACHE_TTL_MS) iterator.remove()
        }

        if (recentRelayFingerprints.containsKey(fingerprint)) return false

        recentRelayFingerprints[fingerprint] = now
        while (recentRelayFingerprints.size > MAX_RELAY_CACHE_SIZE) {
            val firstKey = recentRelayFingerprints.keys.firstOrNull() ?: break
            recentRelayFingerprints.remove(firstKey)
        }
        return true
    }
}
