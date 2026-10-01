package com.torxone.app.privacy

import androidx.room.withTransaction
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.media.MediaStorage
import com.torxone.app.protocol.PeerFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Enforces authenticated absolute expiry without deleting allocated outbox sequences. */
class SecurityPolicyService(
    private val database: TorXDatabase,
    private val storage: MediaStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onExpired: suspend (MessageEntity) -> Unit = {},
    private val onExpiredMedia: suspend (String) -> Unit = {}
) {
    val dao get() = database.securityPolicyDao()
    private val cleanupMutex = Mutex()
    private val activeFiles = mutableMapOf<String, Int>()
    private var cleanupJob: Job? = null

    suspend fun setTimer(conversationId: String, durationMs: Long?) {
        require(database.conversationDao().getById(conversationId) != null) { "Conversation no longer exists" }
        DisappearingPolicy.expiry(clock(), durationMs)
        dao.save(ConversationSecurityPolicy(conversationId, durationMs, clock()))
    }

    suspend fun expiryForSend(conversationId: String, relationships: List<String>, now: Long): Long? {
        val duration = dao.policy(conversationId)?.disappearAfterMs ?: return null
        for (relationship in relationships.distinct()) {
            val advertised = database.featureDao().capabilities(relationship)?.features?.split(',').orEmpty()
            require(PeerFeature.DISAPPEARING_V1.name in advertised) {
                "Disappearing messages require every recipient to update TorX and reconnect. Turn the timer off to send a normal message."
            }
        }
        return DisappearingPolicy.expiry(now, duration)
    }

    /** Repeated calls and process death are safe: the deletion work is committed before unlink. */
    suspend fun cleanupDue() = cleanupMutex.withLock {
        dao.due(clock()).forEach { expireMessage(it.logicalMessageId) }
        recoverPendingFiles()
        retryFileCleanupUnlocked()
    }

    /** The descriptor commits this intent before any incoming plaintext can be written. */
    suspend fun trackExpectedFile(mediaId: String, file: File) {
        val owned = storage.ownedFile(file.path)
        dao.trackFile(PendingMediaFile(owned.path, mediaId, clock()))
    }

    /** Outgoing intent commits before I/O. Active writers cannot be unlinked by cleanup. */
    suspend fun <T> withTrackedFiles(mediaId: String, files: List<File>, block: suspend () -> T): T {
        val paths = files.map { storage.ownedFile(it.path).path }.distinct()
        synchronized(activeFiles) { paths.forEach { activeFiles[it] = (activeFiles[it] ?: 0) + 1 } }
        try {
            paths.forEach { dao.trackFile(PendingMediaFile(it, mediaId, clock())) }
            return block()
        } finally {
            synchronized(activeFiles) {
                paths.forEach { path ->
                    val count = (activeFiles[path] ?: 1) - 1
                    if (count == 0) activeFiles.remove(path) else activeFiles[path] = count
                }
            }
            // The durable intent remains if this coroutine/process stops before association.
            // Cleanup reconciles it against committed media/transfer rows on the next pass.
        }
    }

    private suspend fun recoverPendingFiles() {
        for (file in dao.pendingMediaFiles()) {
            if (synchronized(activeFiles) { file.path in activeFiles }) continue
            database.withTransaction {
                if (database.mediaDao().getById(file.mediaId) == null) {
                    dao.queueFile(PrivacyFileCleanup(file.path, clock()))
                    dao.untrackFile(file.path)
                } else if (dao.referencedFile(file.path)) {
                    dao.untrackFile(file.path)
                }
                // A descriptor may own a future plaintext path before it is downloaded.
                // Retain that intent until association, expiry or media removal.
            }
        }
    }

    suspend fun expireMessage(messageId: String) {
        var expired: MessageEntity? = null
        database.withTransaction {
            val message = database.messageDao().getById(messageId) ?: return@withTransaction
            if (!DisappearingPolicy.expired(message.expiresAt, clock())) return@withTransaction
            val now = clock()
            val media = database.mediaDao().getByMessageId(messageId)
            if (media != null) {
                onExpiredMedia(media.mediaId)
                val transfers = database.mediaTransferDao().getAllByMediaId(media.mediaId)
                transfers.forEach {
                    dao.tombstone(ExpiredMediaTombstone(media.mediaId, it.relationshipId, it.conversationId, now))
                    dao.queueFile(PrivacyFileCleanup(it.tempEncryptedPath, now))
                }
                media.localPath?.let { dao.queueFile(PrivacyFileCleanup(it, now)) }
                dao.queueFile(PrivacyFileCleanup(storage.getTempEncryptedFile(media.mediaId).absolutePath, now))
                dao.mediaFiles(media.mediaId).forEach {
                    dao.queueFile(PrivacyFileCleanup(it.path, now))
                    dao.untrackFile(it.path)
                }
                database.mediaTransferDao().deleteByMediaId(media.mediaId)
                database.mediaDao().deleteByMediaId(media.mediaId)
            }
            dao.tombstoneMessage(messageId, now)
            dao.clearReactions(messageId)
            dao.clearStars(messageId)
            dao.clearLinks(messageId)
            dao.clearPreview(messageId)
            dao.clearReplyReferences(messageId)
            dao.clearDraftReplyReferences(messageId)
            dao.clearScheduledReplyReferences(messageId)
            dao.expireUnallocatedGroupDeliveries(messageId)
            expired = message.takeIf { it.deletedAt == null }
            // Already encrypted outbox frames are retained until ACK. Removing them creates
            // sequence holes; their authenticated expiry tells the receiver to discard data.
        }
        expired?.let { onExpired(it) }
    }

    suspend fun expiredMedia(mediaId: String, relationshipId: String, conversationId: String? = null): Boolean {
        dao.expiredMedia(mediaId, relationshipId)?.let {
            return conversationId == null || conversationId == it.conversationId
        }
        val transfer = database.mediaTransferDao().getByMediaIdAndRelationship(mediaId, relationshipId) ?: return false
        if (conversationId != null && transfer.conversationId != conversationId) return false
        val media = database.mediaDao().getById(mediaId) ?: return false
        val message = database.messageDao().getById(media.messageId) ?: return false
        if (!DisappearingPolicy.expired(message.expiresAt, clock())) return false
        expireMessage(message.logicalMessageId)
        return true
    }

    suspend fun recordExpiredDescriptor(message: MessageEntity, mediaId: String, relationshipId: String) {
        require(DisappearingPolicy.expired(message.expiresAt, clock()))
        dao.tombstone(ExpiredMediaTombstone(mediaId, relationshipId, message.conversationId, clock()))
        database.messageDao().insertIfAbsent(message.copy(body = null, replyToMessageId = null, deletedAt = clock()))
    }

    /** For raced writers: enqueue the newly created private file again after expiry. */
    suspend fun discardFile(path: String) {
        dao.queueFile(PrivacyFileCleanup(path, clock()))
    }

    suspend fun retryFileCleanup() = cleanupMutex.withLock { retryFileCleanupUnlocked() }

    private suspend fun retryFileCleanupUnlocked() {
        for (file in dao.pendingFiles()) {
            val ownedPath = runCatching { storage.ownedFile(file.path).path }.getOrNull() ?: continue
            val deleted = synchronized(activeFiles) {
                ownedPath !in activeFiles && storage.deleteOwnedFileConfirmed(ownedPath)
            }
            if (deleted) dao.fileDeleted(file.path)
        }
    }

    /** Call after unlocked database startup; cleanupDue also runs on foreground entry. */
    fun start(scope: CoroutineScope) {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { cleanupDue() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Durable rows remain for the next pass/startup. */ }
                delay(1_000)
            }
        }
    }
    fun stop() { cleanupJob?.cancel(); cleanupJob = null }
}
