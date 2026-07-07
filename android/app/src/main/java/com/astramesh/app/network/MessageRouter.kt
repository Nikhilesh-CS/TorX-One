package com.astramesh.app.network

import android.util.Log
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.crypto.Identity
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.ContactEntity
import com.astramesh.app.data.MessageEntity
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
        private const val MAX_RETRIES = 5
        private const val RELAY_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RELAY_CACHE_SIZE = 512
    }

    var identity: Identity? = null
    var mySigningKeyHex: String = ""
    var myOnionAddress: String = ""

    private var retryJob: Job? = null
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
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK -> scope.launch(Dispatchers.IO) { handleEncrypted(json, endpointId, json.optString("type")) }
            MeshProtocol.TYPE_RELAY -> scope.launch(Dispatchers.IO) { handleRelay(endpointId, json) }
            MeshProtocol.TYPE_ACK -> scope.launch(Dispatchers.IO) { handleAck(json) }
            MeshProtocol.TYPE_READ -> scope.launch(Dispatchers.IO) { handleRead(json) }
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
            MeshProtocol.TYPE_PROFILE_UPDATE,
            MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO,
            MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK -> scope.launch(Dispatchers.IO) { handleEncrypted(json, null, json.optString("type")) }
            MeshProtocol.TYPE_RELAY -> scope.launch(Dispatchers.IO) { handleRelay(null, json) }
            MeshProtocol.TYPE_ACK -> scope.launch(Dispatchers.IO) { handleAck(json) }
            MeshProtocol.TYPE_READ -> scope.launch(Dispatchers.IO) { handleRead(json) }
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
        replyToText: String? = null
    ): SendResult = withContext(Dispatchers.IO) {
        val identity = identity ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey)
            ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")

        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) {
            return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        }

        val payload = buildEncryptedPayload(identity, contact, text)
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
                replyToText = replyToText
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

    // ──────────────────────── RETRY LOOP ────────────────────────

    fun ensureRetryLoopRunning() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "[RETRY] Starting retry loop")
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
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
        if (pending.isEmpty()) {
            Log.d(TAG, "[RETRY] No pending messages, stopping retry loop")
            retryJob?.cancel()
            return
        }

        Log.d(TAG, "[RETRY] Retrying ${pending.size} pending messages")
        for (msg in pending) {
            val identity = identity ?: continue
            val contact = db.contactDao().getContact(msg.contactKey) ?: continue

            val payload = buildEncryptedPayload(identity, contact, msg.text) ?: continue
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

    // ──────────────────────── ACK HANDLING ────────────────────────

    private suspend fun handleAck(json: JSONObject) {
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
        val ackWire = MeshProtocol.encodeAck(messageId, mySigningKeyHex, myOnionAddress)
        Log.d(TAG, "[ACK] Sending ACK for $messageId to $senderKey (viaEndpoint=$viaEndpoint, senderOnion=${senderOnion?.take(20)})")

        // Send ACK back via the same transport it arrived on
        if (viaEndpoint != null) {
            nearbyManager.sendRaw(viaEndpoint, ackWire)
        } else {
            // Came via Tor — send ACK back via Tor
            scope.launch(Dispatchers.IO) {
                try {
                    // Prefer the senderOnion from the wire (most reliable)
                    val onion = if (!senderOnion.isNullOrBlank()) {
                        senderOnion
                    } else {
                        // Fallback: look up from DB
                        val contact = db.contactDao().getContact(senderKey)
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

    private suspend fun handleRead(json: JSONObject) {
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
        val readWire = MeshProtocol.encodeRead(messageId, mySigningKeyHex, myOnionAddress)
        Log.d(TAG, "[READ] Sending READ receipt for $messageId to $senderKey")

        scope.launch(Dispatchers.IO) {
            try {
                val contact = db.contactDao().getContact(senderKey) ?: return@launch
                val connected = nearbyManager.connectedEndpoints.value
                
                if (contact.endpointId.isNotEmpty() && connected.contains(contact.endpointId)) {
                    nearbyManager.sendRaw(contact.endpointId, readWire)
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

    fun sendMediaAck(messageId: String, senderKey: String) {
        if (messageId.isBlank()) return
        val ackWire = MeshProtocol.encodeAck(messageId, mySigningKeyHex, myOnionAddress)
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
            val service = com.astramesh.app.service.AstraMeshService.getInstance()
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

        if (messageType == MeshProtocol.TYPE_MEDIA_CHUNK || 
            messageType == MeshProtocol.TYPE_MEDIA_OFFER || 
            messageType == MeshProtocol.TYPE_MEDIA_ACK ||
            messageType == MeshProtocol.TYPE_MEDIA_COMPLETE) {
            val service = com.astramesh.app.service.AstraMeshService.getInstance()
            service?.mediaTransferManager?.handleMediaPacket(messageType, plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_CALL_OFFER ||
            messageType == MeshProtocol.TYPE_CALL_ANSWER ||
            messageType == MeshProtocol.TYPE_ICE_CANDIDATE) {
            val service = com.astramesh.app.service.AstraMeshService.getInstance()
            service?.callManager?.handleSignal(messageType, plaintext, senderKey)
            return
        }

        if (messageType == MeshProtocol.TYPE_PROFILE_UPDATE ||
            messageType == MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO ||
            messageType == MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK) {
            val service = com.astramesh.app.service.AstraMeshService.getInstance()
            service?.profileSyncManager?.handleProfilePacket(messageType, plaintext, senderKey)
            return
        }

        Log.d(TAG, "[RECV] Message from ${contact.name} (${plaintext.length} chars)")

        if (messageId.isNotBlank() && db.messageDao().getMessageById(messageId) != null) {
            Log.d(TAG, "[RECV] Duplicate message ignored: $messageId")
            sendAck(messageId, senderKey, viaEndpoint, senderOnion)
            return
        }

        db.messageDao().insertMessage(
            MessageEntity(
                messageId = if (messageId.isNotBlank()) messageId else UUID.randomUUID().toString(),
                contactKey = senderKey,
                text = plaintext,
                timestamp = System.currentTimeMillis(),
                direction = "received",
                status = "delivered"
            )
        )

        val service = com.astramesh.app.service.AstraMeshService.getInstance()
        if (service != null) {
            val isMuted = contact.muteUntil == -1L || (contact.muteUntil > 0L && System.currentTimeMillis() < contact.muteUntil)
            if (!isMuted) {
                val unreadMsgs = db.messageDao().getUnreadMessagesSync(senderKey)
                com.astramesh.app.service.NotificationHelper.showMessageNotification(service, contact, unreadMsgs)
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
