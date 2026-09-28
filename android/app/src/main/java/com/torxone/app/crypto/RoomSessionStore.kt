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
    val keyProtector: KeyProtector = NoOpKeyProtector(),
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() }
) : SessionStore {

    override suspend fun loadSession(relationshipId: String): SessionState? = withContext(Dispatchers.IO) {
        val entity = sessionDao.getByRelationshipId(relationshipId) ?: return@withContext null
        val skippedEntities = skippedKeyDao.getKeysForSession(entity.sessionId)

        val isLegacy = entity.cryptoFormatVersion == 0
        var needsMigration = isLegacy

        fun unwrapField(bytes: ByteArray?): ByteArray? {
            if (bytes == null || bytes.isEmpty()) return bytes
            return if (isLegacy) {
                // Version 0: treat as legacy raw secret even if first byte happens to be 0x54
                needsMigration = true
                bytes
            } else {
                // Version 1: strictly unwrap with KeyProtector. Tampered data throws SecurityException.
                keyProtector.unwrap(bytes)
            }
        }

        val skippedMap = mutableMapOf<SkippedKeyId, ByteArray>()
        for (skip in skippedEntities) {
            val skipLegacy = skip.cryptoFormatVersion == 0 || isLegacy
            val unwrapped = if (skipLegacy) {
                needsMigration = true
                skip.messageKey
            } else {
                keyProtector.unwrap(skip.messageKey)
            }
            skippedMap[SkippedKeyId(skip.ratchetPublicKeyHex, skip.counter)] = unwrapped
        }

        val rootKey = unwrapField(entity.rootKey) ?: entity.rootKey
        val localRatchetPrivateKey = unwrapField(entity.localDhPrivateKey) ?: entity.localDhPrivateKey
        val sendChainKey = unwrapField(entity.sendChainKey)
        val recvChainKey = unwrapField(entity.recvChainKey)

        val state = SessionState(
            sessionId = entity.sessionId,
            relationshipId = entity.relationshipId,
            rootKey = rootKey,
            localRatchetPrivateKey = localRatchetPrivateKey,
            localRatchetPublicKey = entity.localDhPublicKey,
            remoteRatchetPublicKey = entity.remoteDhPublicKey,
            sendChainKey = sendChainKey,
            recvChainKey = recvChainKey,
            sendMessageNumber = entity.sendMessageNumber,
            receiveMessageNumber = entity.receiveMessageNumber,
            previousSendCount = entity.previousSendCount,
            skippedKeys = skippedMap
        )

        if (needsMigration) {
            saveSession(state)
        }

        state
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
            updatedAt = System.currentTimeMillis(),
            cryptoFormatVersion = 1
        )
        transactionRunner {
            sessionDao.upsert(entity)

            // Sync skipped keys atomically with the chain state; consumed keys cannot be resurrected.
            skippedKeyDao.deleteKeysForSession(state.sessionId)
            val skippedList = state.skippedKeys.map { (keyId, mk) ->
                SkippedKeyEntity(
                    sessionId = state.sessionId,
                    ratchetPublicKeyHex = keyId.ratchetPublicKeyHex,
                    counter = keyId.counter,
                    messageKey = keyProtector.wrap(mk),
                    cryptoFormatVersion = 1
                )
            }
            if (skippedList.isNotEmpty()) {
                skippedKeyDao.insertAll(skippedList)
            }
        }
    }

    override suspend fun deleteSession(relationshipId: String): Unit = withContext(Dispatchers.IO) {
        transactionRunner {
            val existing = sessionDao.getByRelationshipId(relationshipId)
            if (existing != null) {
                skippedKeyDao.deleteKeysForSession(existing.sessionId)
            }
            sessionDao.deleteByRelationshipId(relationshipId)
        }
    }
}
