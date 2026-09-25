package com.torxone.app.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    suspend fun encryptAndCommit(
        relationshipId: String,
        plaintext: ByteArray,
        associatedData: ByteArray,
        commitBlock: suspend (EncryptedSessionMessage, SessionState) -> Unit
    ): EncryptedSessionMessage

    suspend fun decryptAndCommit(
        relationshipId: String,
        message: EncryptedSessionMessage,
        associatedData: ByteArray,
        commitBlock: suspend (ByteArray, SessionState) -> Unit
    ): ByteArray

    suspend fun saveSession(state: SessionState)
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
 * SessionController (Phase 3).
 * Owns session lifecycle and operations with no exposure of internal chain keys.
 */
interface SessionController : SessionCrypto {
    suspend fun resetSession(relationshipId: String)
}

/**
 * Storage abstraction for persisting Double Ratchet state.
 */
interface SessionStore {
    suspend fun loadSession(relationshipId: String): SessionState?
    suspend fun saveSession(state: SessionState)
}

/**
 * Commands handled sequentially by the per-relationship SessionActor (Phase 44).
 */
sealed interface SessionCommand {
    data class Encrypt(
        val plaintext: ByteArray,
        val associatedData: ByteArray,
        val commitBlock: (suspend (EncryptedSessionMessage, SessionState) -> Unit)?,
        val response: CompletableDeferred<EncryptedSessionMessage>
    ) : SessionCommand

    data class Decrypt(
        val message: EncryptedSessionMessage,
        val associatedData: ByteArray,
        val commitBlock: (suspend (ByteArray, SessionState) -> Unit)?,
        val response: CompletableDeferred<ByteArray>
    ) : SessionCommand

    data class Initialize(
        val sessionInitializationSecret: ByteArray,
        val isInitiator: Boolean,
        val remoteRatchetPublicKey: ByteArray,
        val localRatchetPrivateKey: ByteArray,
        val localRatchetPublicKey: ByteArray,
        val response: CompletableDeferred<SessionState>
    ) : SessionCommand

    data class SaveState(
        val state: SessionState,
        val response: CompletableDeferred<Unit>
    ) : SessionCommand

    data class HasSession(
        val response: CompletableDeferred<Boolean>
    ) : SessionCommand

    data class Reset(
        val response: CompletableDeferred<Unit>
    ) : SessionCommand
}

/**
 * Per-relationship actor (Phase 42-44) that serializes Double Ratchet state transitions.
 * Ensures that state mutation is sequential for a single relationship without holding locks over network I/O.
 */
class SessionActor(
    val relationshipId: String,
    private val sessionStore: SessionStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val channel = Channel<SessionCommand>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in channel) {
                try {
                    handleCommand(command)
                } catch (e: Throwable) {
                    when (command) {
                        is SessionCommand.Encrypt -> command.response.completeExceptionally(e)
                        is SessionCommand.Decrypt -> command.response.completeExceptionally(e)
                        is SessionCommand.Initialize -> command.response.completeExceptionally(e)
                        is SessionCommand.SaveState -> command.response.completeExceptionally(e)
                        is SessionCommand.HasSession -> command.response.completeExceptionally(e)
                        is SessionCommand.Reset -> command.response.completeExceptionally(e)
                    }
                }
            }
        }
    }

    suspend fun send(command: SessionCommand) {
        channel.send(command)
    }

    private suspend fun handleCommand(cmd: SessionCommand) {
        when (cmd) {
            is SessionCommand.Encrypt -> {
                val currentState = sessionStore.loadSession(relationshipId)
                    ?: throw IllegalStateException("No active Double Ratchet session for relationship $relationshipId")
                val workingState = currentState.copyState()
                val encrypted = SessionRatchet.ratchetEncrypt(workingState, cmd.plaintext, cmd.associatedData)
                cmd.commitBlock?.invoke(encrypted, workingState)
                sessionStore.saveSession(workingState)
                cmd.response.complete(encrypted)
            }
            is SessionCommand.Decrypt -> {
                val currentState = sessionStore.loadSession(relationshipId)
                    ?: throw IllegalStateException("No active Double Ratchet session for relationship $relationshipId")
                val workingState = currentState.copyState()
                val decrypted = SessionRatchet.ratchetDecrypt(workingState, cmd.message, cmd.associatedData)
                cmd.commitBlock?.invoke(decrypted, workingState)
                sessionStore.saveSession(workingState)
                cmd.response.complete(decrypted)
            }
            is SessionCommand.Initialize -> {
                val state = SessionRatchet.initializeSession(
                    relationshipId = relationshipId,
                    sessionInitializationSecret = cmd.sessionInitializationSecret,
                    isInitiator = cmd.isInitiator,
                    remoteRatchetPublicKey = cmd.remoteRatchetPublicKey,
                    localRatchetPrivateKey = cmd.localRatchetPrivateKey,
                    localRatchetPublicKey = cmd.localRatchetPublicKey
                )
                sessionStore.saveSession(state)
                cmd.response.complete(state)
            }
            is SessionCommand.SaveState -> {
                sessionStore.saveSession(cmd.state)
                cmd.response.complete(Unit)
            }
            is SessionCommand.HasSession -> {
                val exists = sessionStore.loadSession(relationshipId) != null
                cmd.response.complete(exists)
            }
            is SessionCommand.Reset -> {
                cmd.response.complete(Unit)
            }
        }
    }
}

/**
 * Thread-safe implementation of SessionController and SessionCrypto using per-relationship SessionActors.
 * Operations on Alice↔Bob will never block Alice↔Charlie, and network I/O is never executed under a lock.
 */
class DoubleRatchetSessionCrypto(
    private val sessionStore: SessionStore
) : SessionController {

    private val actors = ConcurrentHashMap<String, SessionActor>()

    private fun getActor(relationshipId: String): SessionActor {
        return actors.computeIfAbsent(relationshipId) {
            SessionActor(relationshipId, sessionStore)
        }
    }

    override suspend fun hasSession(relationshipId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        getActor(relationshipId).send(SessionCommand.HasSession(deferred))
        return deferred.await()
    }

    override suspend fun saveSession(state: SessionState) {
        val deferred = CompletableDeferred<Unit>()
        getActor(state.relationshipId).send(SessionCommand.SaveState(state, deferred))
        deferred.await()
    }

    override suspend fun initializeSession(
        relationshipId: String,
        sessionInitializationSecret: ByteArray,
        isInitiator: Boolean,
        remoteRatchetPublicKey: ByteArray,
        localRatchetPrivateKey: ByteArray,
        localRatchetPublicKey: ByteArray
    ): SessionState {
        val deferred = CompletableDeferred<SessionState>()
        getActor(relationshipId).send(
            SessionCommand.Initialize(
                sessionInitializationSecret = sessionInitializationSecret,
                isInitiator = isInitiator,
                remoteRatchetPublicKey = remoteRatchetPublicKey,
                localRatchetPrivateKey = localRatchetPrivateKey,
                localRatchetPublicKey = localRatchetPublicKey,
                response = deferred
            )
        )
        return deferred.await()
    }

    override suspend fun encrypt(
        relationshipId: String,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): EncryptedSessionMessage {
        val deferred = CompletableDeferred<EncryptedSessionMessage>()
        getActor(relationshipId).send(
            SessionCommand.Encrypt(
                plaintext = plaintext,
                associatedData = associatedData,
                commitBlock = null,
                response = deferred
            )
        )
        return deferred.await()
    }

    override suspend fun encryptAndCommit(
        relationshipId: String,
        plaintext: ByteArray,
        associatedData: ByteArray,
        commitBlock: suspend (EncryptedSessionMessage, SessionState) -> Unit
    ): EncryptedSessionMessage {
        val deferred = CompletableDeferred<EncryptedSessionMessage>()
        getActor(relationshipId).send(
            SessionCommand.Encrypt(
                plaintext = plaintext,
                associatedData = associatedData,
                commitBlock = commitBlock,
                response = deferred
            )
        )
        return deferred.await()
    }

    override suspend fun decrypt(
        relationshipId: String,
        message: EncryptedSessionMessage,
        associatedData: ByteArray
    ): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        getActor(relationshipId).send(
            SessionCommand.Decrypt(
                message = message,
                associatedData = associatedData,
                commitBlock = null,
                response = deferred
            )
        )
        return deferred.await()
    }

    override suspend fun decryptAndCommit(
        relationshipId: String,
        message: EncryptedSessionMessage,
        associatedData: ByteArray,
        commitBlock: suspend (ByteArray, SessionState) -> Unit
    ): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        getActor(relationshipId).send(
            SessionCommand.Decrypt(
                message = message,
                associatedData = associatedData,
                commitBlock = commitBlock,
                response = deferred
            )
        )
        return deferred.await()
    }

    override suspend fun resetSession(relationshipId: String) {
        val deferred = CompletableDeferred<Unit>()
        getActor(relationshipId).send(SessionCommand.Reset(deferred))
        deferred.await()
    }
}
