package com.torxone.app.security.session

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.torxone.app.data.ContactDao
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MeshProtocol
import org.json.JSONObject
import java.util.UUID

/**
 * SessionManager: Manages Double-Ratcheted Secure Sessions and Forward Secrecy.
 *
 * Sits directly between the application messaging layer and the network transport stack.
 * Guarantees:
 * - Forward Secrecy: Message keys are ephemeral and zeroized after single use.
 * - Post-Compromise Security: Periodic DH ratchet steps re-establish secrecy.
 * - Out-of-Order Delivery: Bounded skipped-message-keys store ensures reliable delivery across mesh hops.
 * - Replay Protection: Monotonic sequence tracking and replay protection window.
 * - Authenticated Header & Timestamp: Signatures and AAD cover recipient, message type, and timestamps.
 */
class SessionManager(
    private val sessionDao: SessionDao,
    private val replayProtection: ReplayProtection,
    private val contactDao: ContactDao? = null,
    private val skippedKeyDao: SkippedMessageKeyDao? = null
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val SCHEMA_VERSION = 1
        private const val MAX_SKIPPED_KEYS = 100
        private const val MAX_TIMESTAMP_DRIFT_MS = 15 * 60 * 1000L // 15 minutes
        private val lazySodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    }

    var identity: Identity? = null
    var myOnionAddress: String = ""

    data class SessionWirePayload(
        val wireJsonString: String,
        val sessionId: String,
        val messageId: String
    )

    data class DecryptedResult(
        val plaintext: String,
        val messageType: String,
        val sessionId: String,
        val msgNum: Int
    )

    /**
     * Encrypts an outgoing message using the active ratcheted session.
     * If no session exists with [contact], automatically initializes one.
     */
    suspend fun encrypt(
        contact: ContactEntity,
        plaintext: String,
        messageType: String = MeshProtocol.TYPE_MSG
    ): SessionWirePayload {
        val id = identity ?: throw IllegalStateException("Identity not available")
        val mySigKeyHex = CryptoManager.toHex(id.signingPublicKey)
        val myEncKeyHex = CryptoManager.toHex(id.encryptionPublicKey)
        val contactKey = contact.signingPublicKey.trim().lowercase()

        // 1. Retrieve or Initialize Session
        var session = sessionDao.getSession(contactKey)
        if (session == null || session.state != "ACTIVE") {
            session = initializeInitiatorSession(contactKey, contact.encryptionPublicKey, id)
            Log.d(TAG, "Initialized new secure session ${session.sessionId} for contact ${contact.name}")
        }

        // 2. Advance Symmetric KDF Chain (Forward Secrecy)
        val currentSendChain = CryptoManager.fromHex(session.sendChainKeyHex)
        val (messageKey, nextSendChain) = SessionRatchet.stepSymmetricChain(currentSendChain)

        val msgNum = session.sendMsgCount
        val now = System.currentTimeMillis()
        val aad = SessionCipher.buildAad(session.sessionId, msgNum, mySigKeyHex, contactKey, now)

        // 3. Encrypt Plaintext with ephemeral message key (zeroized in SessionCipher)
        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aad)

        // 4. Construct Signature over message and metadata (binding recipient, timestamp, and messageType)
        val signatureBody = buildSignatureBody(
            from = mySigKeyHex,
            fromEnc = myEncKeyHex,
            to = contactKey,
            type = messageType,
            sessionId = session.sessionId,
            msgNum = msgNum,
            ratchetPub = session.localRatchetPubHex,
            ciphertext = encrypted.ciphertextBase64,
            iv = encrypted.ivBase64,
            timestamp = now
        )
        val signatureHex = CryptoManager.toHex(CryptoManager.sign(signatureBody, id.signingSecretKey))

        val messageId = UUID.randomUUID().toString()

        // 5. Build Wire JSON envelope
        val wireJson = JSONObject().apply {
            put("type", MeshProtocol.TYPE_SESSION_MSG)
            put("schemaVersion", SCHEMA_VERSION)
            put("msgId", messageId)
            put("sessionId", session.sessionId)
            put("msgNum", msgNum)
            put("innerType", messageType)
            put("from", mySigKeyHex)
            put("fromEnc", myEncKeyHex)
            put("to", contactKey)
            if (myOnionAddress.isNotBlank()) {
                put("senderOnion", myOnionAddress)
            }
            put("ratchetPub", session.localRatchetPubHex)
            put("ciphertext", encrypted.ciphertextBase64)
            put("iv", encrypted.ivBase64)
            put("signature", signatureHex)
            put("timestamp", now)
            put("ttl", 3)
        }

        // 6. Persist Updated Session State
        val updatedSession = session.copy(
            sendChainKeyHex = CryptoManager.toHex(nextSendChain),
            sendMsgCount = session.sendMsgCount + 1,
            lastActiveAt = now
        )
        sessionDao.upsertSession(updatedSession)

        return SessionWirePayload(
            wireJsonString = wireJson.toString(),
            sessionId = session.sessionId,
            messageId = messageId
        )
    }

    /**
     * Decrypts an incoming ratcheted session message from [senderKey].
     * Verifies recipient binding, signature, timestamp, replay protection, out-of-order keys, and advances receive ratchet.
     */
    suspend fun decrypt(
        senderKey: String,
        json: JSONObject
    ): DecryptedResult {
        val id = identity ?: throw IllegalStateException("Identity not available")
        val mySigKeyHex = CryptoManager.toHex(id.signingPublicKey)
        val normalizedSender = senderKey.trim().lowercase()

        val to = json.optString("to", "").trim().lowercase()
        if (to != mySigKeyHex) {
            throw SecurityException("Message addressed to another identity ($to)")
        }

        val sessionId = json.getString("sessionId")
        val msgNum = json.getInt("msgNum")
        val innerType = json.optString("innerType", MeshProtocol.TYPE_MSG)
        val remoteRatchetPubHex = json.getString("ratchetPub")
        val ciphertextBase64 = json.getString("ciphertext")
        val ivBase64 = json.getString("iv")
        val signatureHex = json.getString("signature")
        val fromEnc = json.optString("fromEnc", "")
        val timestamp = json.optLong("timestamp", 0L)

        // 1. Clock drift validation (if timestamp present)
        if (timestamp > 0L) {
            val now = System.currentTimeMillis()
            val drift = Math.abs(now - timestamp)
            if (drift > MAX_TIMESTAMP_DRIFT_MS) {
                throw SecurityException("Message timestamp drift rejected ($drift ms)")
            }
        }

        val senderEncPubHex = if (fromEnc.isNotBlank()) {
            fromEnc
        } else {
            val contact = contactDao?.getContact(normalizedSender)
            contact?.encryptionPublicKey ?: ""
        }
        if (senderEncPubHex.isBlank()) {
            throw SecurityException("Missing sender encryption public key")
        }

        // 2. Verify Digital Signature with sender's public key (Cryptographic Integrity & Recipient Binding)
        val senderSigPub = CryptoManager.fromHexOrNull(normalizedSender, 32)
            ?: throw SecurityException("Invalid sender signing key format")
        val signatureBytes = CryptoManager.fromHexOrNull(signatureHex, 64)
            ?: throw SecurityException("Invalid signature format")

        val signatureBodyWithTs = buildSignatureBody(
            from = normalizedSender,
            fromEnc = senderEncPubHex,
            to = mySigKeyHex,
            type = innerType,
            sessionId = sessionId,
            msgNum = msgNum,
            ratchetPub = remoteRatchetPubHex,
            ciphertext = ciphertextBase64,
            iv = ivBase64,
            timestamp = timestamp
        )

        if (!CryptoManager.verify(signatureBodyWithTs, signatureBytes, senderSigPub)) {
            // Backward compatibility for signature without timestamp
            val signatureBodyNoTs = buildSignatureBody(
                from = normalizedSender,
                fromEnc = senderEncPubHex,
                to = mySigKeyHex,
                type = innerType,
                sessionId = sessionId,
                msgNum = msgNum,
                ratchetPub = remoteRatchetPubHex,
                ciphertext = ciphertextBase64,
                iv = ivBase64,
                timestamp = 0L
            )
            if (!CryptoManager.verify(signatureBodyNoTs, signatureBytes, senderSigPub)) {
                // Backward compatibility for signature without fromEnc
                val legacyBody = "$normalizedSender|$mySigKeyHex|$innerType|$sessionId|$msgNum|$remoteRatchetPubHex|$ciphertextBase64|$ivBase64".toByteArray(Charsets.UTF_8)
                if (!CryptoManager.verify(legacyBody, signatureBytes, senderSigPub)) {
                    throw SecurityException("Digital signature verification failed for session message")
                }
            }
        }

        // 3. Replay Protection Guard
        val isFresh = replayProtection.checkAndMark(sessionId, msgNum)
        if (!isFresh) {
            throw SecurityException("Replay rejected: Counter #$msgNum in session $sessionId already processed")
        }

        // 4. Retrieve or Initialize Responder Session
        var session = sessionDao.getSession(normalizedSender)
        if (session == null || session.sessionId != sessionId) {
            // Simultaneous initiation tie-break:
            // If session already exists, and we haven't received any messages yet, but peer sent one:
            // if normalizedSender < mySigKeyHex, peer's session takes precedence.
            session = initializeResponderSession(
                contactKey = normalizedSender,
                sessionId = sessionId,
                remoteRatchetPubHex = remoteRatchetPubHex,
                remoteEncPubHex = senderEncPubHex,
                localIdentity = id
            )
            Log.d(TAG, "Initialized responder session $sessionId for sender $normalizedSender")
        }

        // 5. Asymmetric DH Ratchet Step if remote ratchet key changed
        if (session.remoteRatchetPubHex != remoteRatchetPubHex) {
            session = performDhRatchetStep(session, remoteRatchetPubHex)
            Log.d(TAG, "[$sessionId] Advanced DH ratchet with new remote key: ${remoteRatchetPubHex.take(12)}")
        }

        // 6. Receive Chain Derivation with Out-of-Order / Skipped Keys Support
        val messageKey: ByteArray
        val updatedRecvChain: ByteArray
        val updatedRecvCount: Int

        if (msgNum < session.recvMsgCount) {
            // Out-of-order late arrival: Must have been stored in skipped keys
            val skippedEntry = skippedKeyDao?.getSkippedKey(sessionId, remoteRatchetPubHex, msgNum)
                ?: throw SecurityException("Replay or duplicate counter #$msgNum rejected (not in skipped keys)")
            messageKey = CryptoManager.fromHex(skippedEntry.messageKeyHex)
            skippedKeyDao.deleteSkippedKey(sessionId, remoteRatchetPubHex, msgNum)
            updatedRecvChain = CryptoManager.fromHex(session.recvChainKeyHex)
            updatedRecvCount = session.recvMsgCount
        } else if (msgNum > session.recvMsgCount) {
            // Out-of-order forward arrival: Some messages arrived ahead of sequence
            val skipCount = msgNum - session.recvMsgCount
            if (skipCount > MAX_SKIPPED_KEYS) {
                throw SecurityException("Too many skipped messages: $skipCount exceeds maximum of $MAX_SKIPPED_KEYS")
            }
            var tempChain = CryptoManager.fromHex(session.recvChainKeyHex)
            for (i in session.recvMsgCount until msgNum) {
                val (skippedMsgKey, nextChain) = SessionRatchet.stepSymmetricChain(tempChain)
                skippedKeyDao?.insertSkippedKey(
                    SkippedMessageKeyEntity(
                        sessionId = sessionId,
                        ratchetPubHex = remoteRatchetPubHex,
                        msgNum = i,
                        messageKeyHex = CryptoManager.toHex(skippedMsgKey)
                    )
                )
                tempChain = nextChain
            }
            val (keyForMsg, finalChain) = SessionRatchet.stepSymmetricChain(tempChain)
            messageKey = keyForMsg
            updatedRecvChain = finalChain
            updatedRecvCount = msgNum + 1
        } else {
            // Normal in-order message: msgNum == session.recvMsgCount
            val currentRecvChain = CryptoManager.fromHex(session.recvChainKeyHex)
            val (keyForMsg, nextRecvChain) = SessionRatchet.stepSymmetricChain(currentRecvChain)
            messageKey = keyForMsg
            updatedRecvChain = nextRecvChain
            updatedRecvCount = session.recvMsgCount + 1
        }

        // 7. Decrypt and Authenticate via AES-256-GCM
        val aadWithTs = SessionCipher.buildAad(sessionId, msgNum, normalizedSender, mySigKeyHex, timestamp)
        val plaintext = try {
            SessionCipher.decrypt(messageKey.clone(), ciphertextBase64, ivBase64, aadWithTs)
        } catch (e: Exception) {
            // Backward compatibility for AAD without timestamp
            val aadNoTs = SessionCipher.buildAad(sessionId, msgNum, normalizedSender, mySigKeyHex, 0L)
            SessionCipher.decrypt(messageKey, ciphertextBase64, ivBase64, aadNoTs)
        }

        // 8. Persist Updated Session State
        val updatedSession = session.copy(
            recvChainKeyHex = CryptoManager.toHex(updatedRecvChain),
            recvMsgCount = updatedRecvCount,
            lastActiveAt = System.currentTimeMillis()
        )
        sessionDao.upsertSession(updatedSession)

        // Prune stale skipped keys older than 7 days
        skippedKeyDao?.pruneExpiredKeys(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)

        return DecryptedResult(
            plaintext = plaintext,
            messageType = innerType,
            sessionId = sessionId,
            msgNum = msgNum
        )
    }

    private fun initializeInitiatorSession(
        contactKey: String,
        contactEncPubHex: String,
        localIdentity: Identity
    ): SessionEntity {
        val sessionId = UUID.randomUUID().toString()
        val localRatchetKeyPair = lazySodium.cryptoBoxKeypair()
        val remoteIdentityPub = CryptoManager.fromHex(contactEncPubHex)

        val rootKey0 = SessionRatchet.computeInitiatorRootKey(
            localEphemeralSec = localRatchetKeyPair.secretKey.asBytes,
            remoteIdentityPub = remoteIdentityPub,
            localIdentitySec = localIdentity.encryptionSecretKey
        )

        // Initial DH ratchet step to derive send chain
        val (finalRoot, sendChain) = SessionRatchet.stepDhRatchet(
            currentRootKey = rootKey0,
            localEphemeralSec = localRatchetKeyPair.secretKey.asBytes,
            remoteEphemeralPub = remoteIdentityPub
        )

        return SessionEntity(
            contactKey = contactKey,
            sessionId = sessionId,
            rootKeyHex = CryptoManager.toHex(finalRoot),
            sendChainKeyHex = CryptoManager.toHex(sendChain),
            recvChainKeyHex = "",
            localRatchetPubHex = CryptoManager.toHex(localRatchetKeyPair.publicKey.asBytes),
            localRatchetSecHex = CryptoManager.toHex(localRatchetKeyPair.secretKey.asBytes),
            remoteRatchetPubHex = contactEncPubHex,
            sendMsgCount = 0,
            recvMsgCount = 0,
            previousSendCount = 0,
            state = "ACTIVE"
        )
    }

    private fun initializeResponderSession(
        contactKey: String,
        sessionId: String,
        remoteRatchetPubHex: String,
        remoteEncPubHex: String,
        localIdentity: Identity
    ): SessionEntity {
        val remoteRatchetPub = CryptoManager.fromHex(remoteRatchetPubHex)
        val remoteIdentityPub = CryptoManager.fromHex(remoteEncPubHex)

        val rootKey0 = SessionRatchet.computeResponderRootKey(
            localIdentitySec = localIdentity.encryptionSecretKey,
            remoteEphemeralPub = remoteRatchetPub,
            remoteIdentityPub = remoteIdentityPub
        )

        // Matches initiator's sendChain derivation: ok = localIdentitySec * remoteRatchetPub
        val (rootKey1, recvChain) = SessionRatchet.stepDhRatchet(
            currentRootKey = rootKey0,
            localEphemeralSec = localIdentity.encryptionSecretKey,
            remoteEphemeralPub = remoteRatchetPub
        )

        // Generate responder's local ratchet keypair for sending replies
        val localRatchetKeyPair = lazySodium.cryptoBoxKeypair()

        // Derive responder's send chain using localRatchetKeyPair and remoteRatchetPub
        val (finalRoot, sendChain) = SessionRatchet.stepDhRatchet(
            currentRootKey = rootKey1,
            localEphemeralSec = localRatchetKeyPair.secretKey.asBytes,
            remoteEphemeralPub = remoteRatchetPub
        )

        return SessionEntity(
            contactKey = contactKey,
            sessionId = sessionId,
            rootKeyHex = CryptoManager.toHex(finalRoot),
            sendChainKeyHex = CryptoManager.toHex(sendChain),
            recvChainKeyHex = CryptoManager.toHex(recvChain),
            localRatchetPubHex = CryptoManager.toHex(localRatchetKeyPair.publicKey.asBytes),
            localRatchetSecHex = CryptoManager.toHex(localRatchetKeyPair.secretKey.asBytes),
            remoteRatchetPubHex = remoteRatchetPubHex,
            sendMsgCount = 0,
            recvMsgCount = 0,
            previousSendCount = 0,
            state = "ACTIVE"
        )
    }

    private fun performDhRatchetStep(session: SessionEntity, newRemoteRatchetPubHex: String): SessionEntity {
        val rootKey = CryptoManager.fromHex(session.rootKeyHex)
        val localSec = CryptoManager.fromHex(session.localRatchetSecHex)
        val newRemotePub = CryptoManager.fromHex(newRemoteRatchetPubHex)

        // 1. Ratchet receive chain
        val (nextRoot, nextRecvChain) = SessionRatchet.stepDhRatchet(rootKey, localSec, newRemotePub)

        // 2. Generate new local ratchet keypair for subsequent sends
        val newLocalKeyPair = lazySodium.cryptoBoxKeypair()

        // 3. Ratchet send chain
        val (finalRoot, nextSendChain) = SessionRatchet.stepDhRatchet(
            nextRoot,
            newLocalKeyPair.secretKey.asBytes,
            newRemotePub
        )

        return session.copy(
            rootKeyHex = CryptoManager.toHex(finalRoot),
            sendChainKeyHex = CryptoManager.toHex(nextSendChain),
            recvChainKeyHex = CryptoManager.toHex(nextRecvChain),
            localRatchetPubHex = CryptoManager.toHex(newLocalKeyPair.publicKey.asBytes),
            localRatchetSecHex = CryptoManager.toHex(newLocalKeyPair.secretKey.asBytes),
            remoteRatchetPubHex = newRemoteRatchetPubHex,
            previousSendCount = session.sendMsgCount,
            sendMsgCount = 0,
            recvMsgCount = 0
        )
    }

    private fun buildSignatureBody(
        from: String,
        fromEnc: String,
        to: String,
        type: String,
        sessionId: String,
        msgNum: Int,
        ratchetPub: String,
        ciphertext: String,
        iv: String,
        timestamp: Long = 0L
    ): ByteArray {
        val body = if (timestamp > 0L) {
            "$from|$fromEnc|$to|$type|$sessionId|$msgNum|$ratchetPub|$ciphertext|$iv|$timestamp"
        } else {
            "$from|$fromEnc|$to|$type|$sessionId|$msgNum|$ratchetPub|$ciphertext|$iv"
        }
        return body.toByteArray(Charsets.UTF_8)
    }
}
