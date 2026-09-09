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
        private const val MAX_TIMESTAMP_DRIFT_MS = 15 * 60 * 1000L
        private val lazySodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    }
    var identity: Identity? = null
    var myOnionAddress: String = ""
    data class SessionWirePayload(val wireJsonString: String, val sessionId: String, val messageId: String)
    data class DecryptedResult(val plaintext: String, val messageType: String, val sessionId: String, val msgNum: Int)
    suspend fun encrypt(contact: ContactEntity, plaintext: String, messageType: String = MeshProtocol.TYPE_MSG): SessionWirePayload {
        val id = identity ?: throw IllegalStateException("Identity not available")
        val mySigKeyHex = CryptoManager.toHex(id.signingPublicKey)
        val myEncKeyHex = CryptoManager.toHex(id.encryptionPublicKey)
        val contactKey = contact.signingPublicKey.trim().lowercase()
        var session = sessionDao.getSession(contactKey)
        if (session == null || session.state != "ACTIVE") {
            session = initializeInitiatorSession(contactKey, contact.encryptionPublicKey, id)
            Log.d(TAG, "Initialized new secure session ${session.sessionId} for contact ${contact.name}")
        }
        val currentSendChain = CryptoManager.fromHex(session.sendChainKeyHex)
        val (messageKey, nextSendChain) = SessionRatchet.stepSymmetricChain(currentSendChain)
        val msgNum = session.sendMsgCount
        val now = System.currentTimeMillis()
        val aad = SessionCipher.buildAad(session.sessionId, msgNum, mySigKeyHex, contactKey, now)
        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aad)
        val signatureBody = buildSignatureBody(mySigKeyHex, myEncKeyHex, contactKey, messageType, session.sessionId, msgNum, session.localRatchetPubHex, encrypted.ciphertextBase64, encrypted.ivBase64, now)
        val signatureHex = CryptoManager.toHex(CryptoManager.sign(signatureBody, id.signingSecretKey))
        val messageId = UUID.randomUUID().toString()
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
            if (myOnionAddress.isNotBlank()) put("senderOnion", myOnionAddress)
            put("ratchetPub", session.localRatchetPubHex)
            put("ciphertext", encrypted.ciphertextBase64)
            put("iv", encrypted.ivBase64)
            put("signature", signatureHex)
            put("timestamp", now)
            put("ttl", 3)
        }
        sessionDao.upsertSession(session.copy(sendChainKeyHex = CryptoManager.toHex(nextSendChain), sendMsgCount = session.sendMsgCount + 1, lastActiveAt = now))
        return SessionWirePayload(wireJson.toString(), session.sessionId, messageId)
    }
    suspend fun decrypt(senderKey: String, json: JSONObject): DecryptedResult {
        val id = identity ?: throw IllegalStateException("Identity not available")
        val mySigKeyHex = CryptoManager.toHex(id.signingPublicKey)
        val normalizedSender = senderKey.trim().lowercase()
        val to = json.optString("to", "").trim().lowercase()
        if (to != mySigKeyHex) throw SecurityException("Message addressed to another identity ($to)")
        val sessionId = json.getString("sessionId")
        val msgNum = json.getInt("msgNum")
        val innerType = json.optString("innerType", MeshProtocol.TYPE_MSG)
        val remoteRatchetPubHex = json.getString("ratchetPub")
        val ciphertextBase64 = json.getString("ciphertext")
        val ivBase64 = json.getString("iv")
        val signatureHex = json.getString("signature")
        val fromEnc = json.optString("fromEnc", "")
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp > 0L && Math.abs(System.currentTimeMillis() - timestamp) > MAX_TIMESTAMP_DRIFT_MS) throw SecurityException("Message timestamp drift rejected")
        val senderEncPubHex = if (fromEnc.isNotBlank()) fromEnc else contactDao?.getContact(normalizedSender)?.encryptionPublicKey ?: ""
        if (senderEncPubHex.isBlank()) throw SecurityException("Missing sender encryption public key")
        val senderSigPub = CryptoManager.fromHexOrNull(normalizedSender, 32) ?: throw SecurityException("Invalid sender signing key format")
        val signatureBytes = CryptoManager.fromHexOrNull(signatureHex, 64) ?: throw SecurityException("Invalid signature format")
        val signatureBodyWithTs = buildSignatureBody(normalizedSender, senderEncPubHex, mySigKeyHex, innerType, sessionId, msgNum, remoteRatchetPubHex, ciphertextBase64, ivBase64, timestamp)
        if (!CryptoManager.verify(signatureBodyWithTs, signatureBytes, senderSigPub)) {
            val signatureBodyNoTs = buildSignatureBody(normalizedSender, senderEncPubHex, mySigKeyHex, innerType, sessionId, msgNum, remoteRatchetPubHex, ciphertextBase64, ivBase64, 0L)
            if (!CryptoManager.verify(signatureBodyNoTs, signatureBytes, senderSigPub)) {
                val legacyBody = "$normalizedSender|$mySigKeyHex|$innerType|$sessionId|$msgNum|$remoteRatchetPubHex|$ciphertextBase64|$ivBase64".toByteArray(Charsets.UTF_8)
                if (!CryptoManager.verify(legacyBody, signatureBytes, senderSigPub)) throw SecurityException("Digital signature verification failed for session message")
            }
        }
        // 3. Replay Protection Guard
        val isFresh = replayProtection.checkAndMark(sessionId, msgNum)
        if (!isFresh) {
            throw SecurityException("Replay rejected: Counter #$msgNum in session $sessionId already processed")
        }

        // 4. Retrieve or Initialize Responder Session (Two-Slot in-flight safe arbitration)
        var session = sessionDao.getSessionById(normalizedSender, sessionId)
        val activeSession = sessionDao.getSession(normalizedSender)

        if (session == null) {
            if (activeSession == null) {
                // First session from this contact
                session = initializeResponderSession(
                    contactKey = normalizedSender,
                    sessionId = sessionId,
                    remoteRatchetPubHex = remoteRatchetPubHex,
                    remoteEncPubHex = senderEncPubHex,
                    localIdentity = id
                )
                Log.d(TAG, "[SESSION_RX] Initialized new responder session $sessionId for sender $normalizedSender")
            } else {
                val isUnusedLocalSession = (activeSession.sendMsgCount == 0 && activeSession.recvMsgCount == 0)
                val isSimultaneousInitiation = (activeSession.sendMsgCount > 0 && activeSession.recvMsgCount == 0 && msgNum == 0)
                val remoteWinsTieBreak = normalizedSender < mySigKeyHex

                if (isUnusedLocalSession || (isSimultaneousInitiation && remoteWinsTieBreak)) {
                    // Remote session wins tie-break or local was unused
                    Log.d(TAG, "[SESSION_RX] Remote session $sessionId wins arbitration over ${activeSession.sessionId}")
                    session = initializeResponderSession(
                        contactKey = normalizedSender,
                        sessionId = sessionId,
                        remoteRatchetPubHex = remoteRatchetPubHex,
                        remoteEncPubHex = senderEncPubHex,
                        localIdentity = id
                    )
                    // Demote old active session to TRANSITION so in-flight messages can still drain
                    sessionDao.upsertSession(activeSession.copy(state = "TRANSITION"))
                } else if (isSimultaneousInitiation && !remoteWinsTieBreak) {
                    // Local session wins tie-break: decrypt incoming message under incoming sessionId in TRANSITION slot
                    Log.d(TAG, "[SESSION_RX] Local session ${activeSession.sessionId} wins arbitration over $sessionId; decrypting incoming with temporary responder")
                    session = initializeResponderSession(
                        contactKey = normalizedSender,
                        sessionId = sessionId,
                        remoteRatchetPubHex = remoteRatchetPubHex,
                        remoteEncPubHex = senderEncPubHex,
                        localIdentity = id
                    ).copy(state = "TRANSITION")
                } else {
                    // Established active session rotating to new session generation
                    Log.d(TAG, "[SESSION_RX] Adopting new session generation $sessionId from $normalizedSender (previous=${activeSession.sessionId})")
                    session = initializeResponderSession(
                        contactKey = normalizedSender,
                        sessionId = sessionId,
                        remoteRatchetPubHex = remoteRatchetPubHex,
                        remoteEncPubHex = senderEncPubHex,
                        localIdentity = id
                    )
                    sessionDao.upsertSession(activeSession.copy(state = "TRANSITION"))
                }
            }
        }
        suspend fun deriveAndDecrypt(current: SessionEntity): Pair<String, SessionEntity> {
            var working = current
            if (working.remoteRatchetPubHex != remoteRatchetPubHex) working = performDhRatchetStep(working, remoteRatchetPubHex)
            val messageKey: ByteArray
            val updatedRecvChain: ByteArray
            val updatedRecvCount: Int
            if (msgNum < working.recvMsgCount) {
                val skippedEntry = skippedKeyDao?.getSkippedKey(sessionId, remoteRatchetPubHex, msgNum) ?: throw SecurityException("Replay or duplicate counter #$msgNum rejected")
                messageKey = CryptoManager.fromHex(skippedEntry.messageKeyHex)
                skippedKeyDao.deleteSkippedKey(sessionId, remoteRatchetPubHex, msgNum)
                updatedRecvChain = CryptoManager.fromHex(working.recvChainKeyHex)
                updatedRecvCount = working.recvMsgCount
            } else if (msgNum > working.recvMsgCount) {
                val skipCount = msgNum - working.recvMsgCount
                if (skipCount > MAX_SKIPPED_KEYS) throw SecurityException("Too many skipped messages: $skipCount")
                var tempChain = CryptoManager.fromHex(working.recvChainKeyHex)
                for (i in working.recvMsgCount until msgNum) {
                    val (skippedMsgKey, nextChain) = SessionRatchet.stepSymmetricChain(tempChain)
                    skippedKeyDao?.insertSkippedKey(SkippedMessageKeyEntity(sessionId, remoteRatchetPubHex, i, CryptoManager.toHex(skippedMsgKey)))
                    tempChain = nextChain
                }
                val (keyForMsg, finalChain) = SessionRatchet.stepSymmetricChain(tempChain)
                messageKey = keyForMsg
                updatedRecvChain = finalChain
                updatedRecvCount = msgNum + 1
            } else {
                val currentRecvChain = CryptoManager.fromHex(working.recvChainKeyHex)
                val (keyForMsg, nextRecvChain) = SessionRatchet.stepSymmetricChain(currentRecvChain)
                messageKey = keyForMsg
                updatedRecvChain = nextRecvChain
                updatedRecvCount = working.recvMsgCount + 1
            }
            val plaintext = try {
                SessionCipher.decrypt(messageKey.clone(), ciphertextBase64, ivBase64, SessionCipher.buildAad(sessionId, msgNum, normalizedSender, mySigKeyHex, timestamp))
            } catch (e: Exception) {
                SessionCipher.decrypt(messageKey, ciphertextBase64, ivBase64, SessionCipher.buildAad(sessionId, msgNum, normalizedSender, mySigKeyHex, 0L))
            }
            return Pair(plaintext, working.copy(recvChainKeyHex = CryptoManager.toHex(updatedRecvChain), recvMsgCount = updatedRecvCount, lastActiveAt = System.currentTimeMillis()))
        }
        val firstSession = session
        val result = try {
            deriveAndDecrypt(firstSession)
        } catch (firstError: Exception) {
            Log.w(TAG, "[$sessionId] Ratchet mismatch for ${normalizedSender.take(12)}; rebuilding responder state: ${firstError.message}")
            sessionDao.deleteSession(normalizedSender)
            skippedKeyDao?.clearSessionSkippedKeys(sessionId)
            val newSession = initializeResponderSession(normalizedSender, sessionId, remoteRatchetPubHex, senderEncPubHex, id)
            deriveAndDecrypt(newSession)
        }
        if (!replayProtection.checkAndMark(sessionId, msgNum)) throw SecurityException("Replay rejected: Counter #$msgNum in session $sessionId already processed")
        sessionDao.upsertSession(result.second)
        skippedKeyDao?.pruneExpiredKeys(System.currentTimeMillis() - 7 * 24 * 3600 * 1000L)
        return DecryptedResult(result.first, innerType, sessionId, msgNum)
    }
    suspend fun resetSession(contactKey: String) {
        val normalized = contactKey.trim().lowercase()
        val existing = sessionDao.getSession(normalized)
        sessionDao.deleteSession(normalized)
        existing?.let { skippedKeyDao?.clearSessionSkippedKeys(it.sessionId) }
        Log.w(TAG, "Reset ratchet session for ${normalized.take(12)}")
    }
    private fun initializeInitiatorSession(contactKey: String, contactEncPubHex: String, localIdentity: Identity): SessionEntity {
        val sessionId = UUID.randomUUID().toString()
        val localRatchetKeyPair = lazySodium.cryptoBoxKeypair()
        val remoteIdentityPub = CryptoManager.fromHex(contactEncPubHex)
        val rootKey0 = SessionRatchet.computeInitiatorRootKey(localRatchetKeyPair.secretKey.asBytes, remoteIdentityPub, localIdentity.encryptionSecretKey)
        val (finalRoot, sendChain) = SessionRatchet.stepDhRatchet(rootKey0, localRatchetKeyPair.secretKey.asBytes, remoteIdentityPub)
        return SessionEntity(contactKey, sessionId, CryptoManager.toHex(finalRoot), CryptoManager.toHex(sendChain), "", CryptoManager.toHex(localRatchetKeyPair.publicKey.asBytes), CryptoManager.toHex(localRatchetKeyPair.secretKey.asBytes), contactEncPubHex, 0, 0, 0, System.currentTimeMillis(), "ACTIVE")
    }
    private fun initializeResponderSession(contactKey: String, sessionId: String, remoteRatchetPubHex: String, remoteEncPubHex: String, localIdentity: Identity): SessionEntity {
        val remoteRatchetPub = CryptoManager.fromHex(remoteRatchetPubHex)
        val remoteIdentityPub = CryptoManager.fromHex(remoteEncPubHex)
        val rootKey0 = SessionRatchet.computeResponderRootKey(localIdentity.encryptionSecretKey, remoteRatchetPub, remoteIdentityPub)
        val (rootKey1, recvChain) = SessionRatchet.stepDhRatchet(rootKey0, localIdentity.encryptionSecretKey, remoteRatchetPub)
        val localRatchetKeyPair = lazySodium.cryptoBoxKeypair()
        val (finalRoot, sendChain) = SessionRatchet.stepDhRatchet(rootKey1, localRatchetKeyPair.secretKey.asBytes, remoteRatchetPub)
        return SessionEntity(contactKey, sessionId, CryptoManager.toHex(finalRoot), CryptoManager.toHex(sendChain), CryptoManager.toHex(recvChain), CryptoManager.toHex(localRatchetKeyPair.publicKey.asBytes), CryptoManager.toHex(localRatchetKeyPair.secretKey.asBytes), remoteRatchetPubHex, 0, 0, 0, System.currentTimeMillis(), "ACTIVE")
    }
    private fun performDhRatchetStep(session: SessionEntity, newRemoteRatchetPubHex: String): SessionEntity {
        val rootKey = CryptoManager.fromHex(session.rootKeyHex)
        val localSec = CryptoManager.fromHex(session.localRatchetSecHex)
        val newRemotePub = CryptoManager.fromHex(newRemoteRatchetPubHex)
        val (nextRoot, nextRecvChain) = SessionRatchet.stepDhRatchet(rootKey, localSec, newRemotePub)
        val newLocalKeyPair = lazySodium.cryptoBoxKeypair()
        val (finalRoot, nextSendChain) = SessionRatchet.stepDhRatchet(nextRoot, newLocalKeyPair.secretKey.asBytes, newRemotePub)
        return session.copy(rootKeyHex = CryptoManager.toHex(finalRoot), sendChainKeyHex = CryptoManager.toHex(nextSendChain), recvChainKeyHex = CryptoManager.toHex(nextRecvChain), localRatchetPubHex = CryptoManager.toHex(newLocalKeyPair.publicKey.asBytes), localRatchetSecHex = CryptoManager.toHex(newLocalKeyPair.secretKey.asBytes), remoteRatchetPubHex = newRemoteRatchetPubHex, previousSendCount = session.sendMsgCount, sendMsgCount = 0, recvMsgCount = 0)
    }
    private fun buildSignatureBody(from: String, fromEnc: String, to: String, type: String, sessionId: String, msgNum: Int, ratchetPub: String, ciphertext: String, iv: String, timestamp: Long = 0L): ByteArray {
        val body = if (timestamp > 0L) "$from|$fromEnc|$to|$type|$sessionId|$msgNum|$ratchetPub|$ciphertext|$iv|$timestamp" else "$from|$fromEnc|$to|$type|$sessionId|$msgNum|$ratchetPub|$ciphertext|$iv"
        return body.toByteArray(Charsets.UTF_8)
    }
}
