package com.torxone.app.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level session encryption interface.
 * Feature code interacts only through this interface and never directly manipulates chain keys.
 */
interface SessionCrypto {
    suspend fun encrypt(
        relationshipId: String,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): EncryptedSessionMessage

    suspend fun decrypt(
        relationshipId: String,
        message: EncryptedSessionMessage,
        associatedData: ByteArray
    ): ByteArray

    suspend fun hasSession(relationshipId: String): Boolean
    suspend fun initializeSession(
        relationshipId: String,
        sessionInitializationSecret: ByteArray,
        isInitiator: Boolean,
        remoteRatchetPublicKey: ByteArray,
        localRatchetPrivateKey: ByteArray,
        localRatchetPublicKey: ByteArray
    ): SessionState
}

/**
 * Storage abstraction for persisting Double Ratchet state.
 */
interface SessionStore {
    suspend fun loadSession(relationshipId: String): SessionState?
    suspend fun saveSession(state: SessionState)
}

/**
 * Thread-safe implementation of SessionCrypto using per-relationship mutexes.
 * Operations on Alice↔Bob will never block Alice↔Charlie.
 */
class DoubleRatchetSessionCrypto(
    private val sessionStore: SessionStore
) : SessionCrypto {

    // Per-relationship lock to prevent concurrent ratchet mutation
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun getLock(relationshipId: String): Mutex {
        return sessionLocks.computeIfAbsent(relationshipId) { Mutex() }
    }

    override suspend fun hasSession(relationshipId: String): Boolean {
        return sessionStore.loadSession(relationshipId) != null
    }

    override suspend fun initializeSession(
        relationshipId: String,
        sessionInitializationSecret: ByteArray,
        isInitiator: Boolean,
        remoteRatchetPublicKey: ByteArray,
        localRatchetPrivateKey: ByteArray,
        localRatchetPublicKey: ByteArray
    ): SessionState = getLock(relationshipId).withLock {
        val state = SessionRatchet.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = sessionInitializationSecret,
            isInitiator = isInitiator,
            remoteRatchetPublicKey = remoteRatchetPublicKey,
            localRatchetPrivateKey = localRatchetPrivateKey,
            localRatchetPublicKey = localRatchetPublicKey
        )
        sessionStore.saveSession(state)
        state
    }

    override suspend fun encrypt(
        relationshipId: String,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): EncryptedSessionMessage = getLock(relationshipId).withLock {
        val state = sessionStore.loadSession(relationshipId)
            ?: throw IllegalStateException("No active Double Ratchet session for relationship $relationshipId")

        val encrypted = SessionRatchet.ratchetEncrypt(state, plaintext, associatedData)
        sessionStore.saveSession(state)
        encrypted
    }

    override suspend fun decrypt(
        relationshipId: String,
        message: EncryptedSessionMessage,
        associatedData: ByteArray
    ): ByteArray = getLock(relationshipId).withLock {
        val state = sessionStore.loadSession(relationshipId)
            ?: throw IllegalStateException("No active Double Ratchet session for relationship $relationshipId")

        val plaintext = SessionRatchet.ratchetDecrypt(state, message, associatedData)
        sessionStore.saveSession(state)
        plaintext
    }
}
