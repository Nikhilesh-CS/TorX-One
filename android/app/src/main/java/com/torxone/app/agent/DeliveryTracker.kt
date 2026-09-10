package com.torxone.app.agent

import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.network.MeshProtocol
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * TorX One 2.0 — Delivery Tracker
 *
 * Dedicated manager for ACK (delivery confirmation) and READ (seen confirmation)
 * receipts. Synchronizes state across:
 * 1. DeliveryQueueEntity (protocol delivery engine state)
 * 2. MessageEntity (UI / conversation layer state)
 * 3. Peer contacts (updating sender onion addresses if present)
 */
class DeliveryTracker(
    private val db: AppDatabase,
    private val agent: TorXAgent,
    private val transportRouter: TransportRouter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "DeliveryTracker"
    }

    var mySigningKeyHex: String = ""
    var myOnionAddress: String = ""

    /**
     * Process an incoming ACK receipt.
     */
    suspend fun handleAck(json: JSONObject, sourceAddress: String?) {
        val messageId = json.optString("msgId", "")
        val senderKey = json.optString("from", "").trim().lowercase()
        val toKey = json.optString("to", "").trim().lowercase()

        if (messageId.isBlank() || senderKey.isBlank()) return
        if (toKey.isNotBlank() && mySigningKeyHex.isNotBlank() && toKey != mySigningKeyHex.trim().lowercase()) {
            Log.w(TAG, "[ACK] Dropped ACK addressed to $toKey (my key=$mySigningKeyHex)")
            return
        }

        // 1. Update TorX Agent delivery queue
        agent.handleAck(messageId)

        // 2. Update Room MessageEntity status to "delivered"
        val existing = db.messageDao().getMessageById(messageId)
        if (existing != null && existing.direction == "sent") {
            db.messageDao().updateSentMessageStatus(messageId, existing.contactKey, "delivered")
            Log.i(TAG, "[ACK] messageId=$messageId marked DELIVERED in DB")
        }

        // 3. Update contact onion address if attached
        val senderOnion = json.optString("senderOnion", "")
        if (senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) {
                db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
            }
        }
    }

    /**
     * Process an incoming READ receipt.
     */
    suspend fun handleRead(json: JSONObject, sourceAddress: String?) {
        val messageId = json.optString("msgId", "")
        val senderKey = json.optString("from", "").trim().lowercase()
        val toKey = json.optString("to", "").trim().lowercase()

        if (messageId.isBlank() || senderKey.isBlank()) return
        if (toKey.isNotBlank() && mySigningKeyHex.isNotBlank() && toKey != mySigningKeyHex.trim().lowercase()) {
            Log.w(TAG, "[READ] Dropped READ addressed to $toKey (my key=$mySigningKeyHex)")
            return
        }

        // 1. Update TorX Agent delivery queue
        agent.handleRead(messageId)

        // 2. Update Room MessageEntity status to "read"
        val existing = db.messageDao().getMessageById(messageId)
        if (existing != null && existing.direction == "sent") {
            db.messageDao().updateSentMessageStatus(messageId, existing.contactKey, "read")
            Log.i(TAG, "[READ] messageId=$messageId marked READ in DB")
        }

        // 3. Update contact onion address if attached
        val senderOnion = json.optString("senderOnion", "")
        if (senderOnion.isNotBlank()) {
            val contact = db.contactDao().getContact(senderKey)
            if (contact != null && contact.onionAddress != senderOnion) {
                db.contactDao().insertContact(contact.copy(onionAddress = senderOnion))
            }
        }
    }

    /**
     * Send an authenticated ACK receipt to the message sender via TorX Agent queue.
     * All receipts flow through the persistent delivery engine — zero bypasses.
     */
    fun sendAck(messageId: String, senderKey: String, viaEndpoint: String? = null, senderOnion: String? = null) {
        if (messageId.isBlank() || senderKey.isBlank() || mySigningKeyHex.isBlank()) return
        val wire = MeshProtocol.encodeAck(
            messageId = messageId,
            fromKey = mySigningKeyHex,
            toKey = senderKey,
            senderOnion = myOnionAddress.ifBlank { null }
        )

        scope.launch {
            try {
                agent.queueForDelivery(
                    recipientKey = senderKey,
                    messageId = "ack_$messageId",
                    messageType = EnvelopeType.ACK,
                    encryptedPayload = wire
                )
                Log.d(TAG, "[SEND_ACK] Queued ACK envelope for $messageId to $senderKey")
            } catch (e: Exception) {
                Log.w(TAG, "[SEND_ACK] Failed to queue ACK for $messageId: ${e.message}")
            }
        }
    }

    /**
     * Send an authenticated READ receipt to the message sender via TorX Agent queue.
     * All receipts flow through the persistent delivery engine — zero bypasses.
     */
    fun sendReadReceipt(messageId: String, senderKey: String) {
        if (messageId.isBlank() || senderKey.isBlank() || mySigningKeyHex.isBlank()) return
        val wire = MeshProtocol.encodeRead(
            messageId = messageId,
            fromKey = mySigningKeyHex,
            toKey = senderKey,
            senderOnion = myOnionAddress.ifBlank { null }
        )

        scope.launch {
            try {
                agent.queueForDelivery(
                    recipientKey = senderKey,
                    messageId = "read_$messageId",
                    messageType = EnvelopeType.READ,
                    encryptedPayload = wire
                )
                Log.d(TAG, "[SEND_READ] Queued READ envelope for $messageId to $senderKey")
            } catch (e: Exception) {
                Log.w(TAG, "[SEND_READ] Failed to queue READ for $messageId: ${e.message}")
            }
        }
    }
}
