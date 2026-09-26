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
    private val skippedKeyDao: SkippedKeyDao,
    private val keyProtector: KeyProtector = NoOpKeyProtector()
) : SessionStore {

    override suspend fun loadSession(relationshipId: String): SessionState? = withContext(Dispatchers.IO) {
        val entity = sessionDao.getByRelationshipId(relationshipId) ?: return@withContext null
        val skippedEntities = skippedKeyDao.getKeysForSession(entity.sessionId)

        val skippedMap = mutableMapOf<SkippedKeyId, ByteArray>()
        for (skip in skippedEntities) {
            skippedMap[SkippedKeyId(skip.ratchetPublicKeyHex, skip.counter)] = keyProtector.unwrap(skip.messageKey)
        }

        SessionState(
            sessionId = entity.sessionId,
            relationshipId = entity.relationshipId,
            rootKey = keyProtector.unwrap(entity.rootKey),
            localRatchetPrivateKey = keyProtector.unwrap(entity.localDhPrivateKey),
            localRatchetPublicKey = entity.localDhPublicKey,
            remoteRatchetPublicKey = entity.remoteDhPublicKey,
            sendChainKey = entity.sendChainKey?.let { keyProtector.unwrap(it) },
            recvChainKey = entity.recvChainKey?.let { keyProtector.unwrap(it) },
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
            rootKey = keyProtector.wrap(state.rootKey),
            localDhPublicKey = state.localRatchetPublicKey,
            localDhPrivateKey = keyProtector.wrap(state.localRatchetPrivateKey),
            remoteDhPublicKey = state.remoteRatchetPublicKey,
            sendChainKey = state.sendChainKey?.let { keyProtector.wrap(it) },
            recvChainKey = state.recvChainKey?.let { keyProtector.wrap(it) },
            sendMessageNumber = state.sendMessageNumber,
            receiveMessageNumber = state.receiveMessageNumber,
            previousSendCount = state.previousSendCount,
            state = "ACTIVE",
            updatedAt = System.currentTimeMillis()
        )
        sessionDao.upsert(entity)

        // Sync skipped keys: delete consumed/obsolete keys to prevent resurrection
        skippedKeyDao.deleteKeysForSession(state.sessionId)
        val skippedList = state.skippedKeys.map { (keyId, mk) ->
            SkippedKeyEntity(
                sessionId = state.sessionId,
                ratchetPublicKeyHex = keyId.ratchetPublicKeyHex,
                counter = keyId.counter,
                messageKey = keyProtector.wrap(mk)
            )
        }
        if (skippedList.isNotEmpty()) {
            skippedKeyDao.insertAll(skippedList)
        }
    }

    override suspend fun deleteSession(relationshipId: String): Unit = withContext(Dispatchers.IO) {
        val existing = sessionDao.getByRelationshipId(relationshipId)
        if (existing != null) {
            skippedKeyDao.deleteKeysForSession(existing.sessionId)
        }
        sessionDao.deleteByRelationshipId(relationshipId)
    }
}
