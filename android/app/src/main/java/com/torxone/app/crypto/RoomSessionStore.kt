package com.torxone.app.crypto

import com.torxone.app.data.dao.SessionDao
import com.torxone.app.data.dao.SkippedKeyDao
import com.torxone.app.data.entity.SessionDbEntity
import com.torxone.app.data.entity.SkippedKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed persistence for Double Ratchet session state and skipped message keys.
 */
class RoomSessionStore(
    private val sessionDao: SessionDao,
    private val skippedKeyDao: SkippedKeyDao
) : SessionStore {

    override suspend fun loadSession(relationshipId: String): SessionState? = withContext(Dispatchers.IO) {
        val entity = sessionDao.getByRelationshipId(relationshipId) ?: return@withContext null
        val skippedEntities = skippedKeyDao.getKeysForSession(entity.sessionId)

        val skippedMap = mutableMapOf<SkippedKeyId, ByteArray>()
        for (skip in skippedEntities) {
            skippedMap[SkippedKeyId(skip.ratchetPublicKeyHex, skip.counter)] = skip.messageKey
        }

        SessionState(
            sessionId = entity.sessionId,
            relationshipId = entity.relationshipId,
            rootKey = entity.rootKey,
            localRatchetPrivateKey = entity.localDhPrivateKey,
            localRatchetPublicKey = entity.localDhPublicKey,
            remoteRatchetPublicKey = entity.remoteDhPublicKey,
            sendChainKey = entity.sendChainKey,
            recvChainKey = entity.recvChainKey,
            sendMessageNumber = entity.sendMessageNumber,
            receiveMessageNumber = entity.receiveMessageNumber,
            previousSendCount = entity.previousSendCount,
            skippedKeys = skippedMap
        )
    }

    override suspend fun saveSession(state: SessionState): Unit = withContext(Dispatchers.IO) {
        val entity = SessionDbEntity(
            sessionId = state.sessionId,
            relationshipId = state.relationshipId,
            rootKey = state.rootKey,
            localDhPublicKey = state.localRatchetPublicKey,
            localDhPrivateKey = state.localRatchetPrivateKey,
            remoteDhPublicKey = state.remoteRatchetPublicKey,
            sendChainKey = state.sendChainKey,
            recvChainKey = state.recvChainKey,
            sendMessageNumber = state.sendMessageNumber,
            receiveMessageNumber = state.receiveMessageNumber,
            previousSendCount = state.previousSendCount,
            state = "ACTIVE",
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.upsert(entity)

        // Sync skipped keys
        val skippedList = state.skippedKeys.map { (keyId, mk) ->
            SkippedKeyEntity(
                sessionId = state.sessionId,
                ratchetPublicKeyHex = keyId.ratchetPublicKeyHex,
                counter = keyId.counter,
                messageKey = mk
            )
        }
        if (skippedList.isNotEmpty()) {
            skippedKeyDao.insertAll(skippedList)
        }
    }
}
