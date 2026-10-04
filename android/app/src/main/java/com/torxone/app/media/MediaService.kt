package com.torxone.app.media

import android.content.Context
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.connection.RelationshipSendCoordinator
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MediaDao
import com.torxone.app.data.dao.MediaTransferDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.dao.GroupMessageDeliveryDao
import com.torxone.app.data.dao.GroupDao
import com.torxone.app.data.entity.*
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.GroupEnvelopeMetadata
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
import com.torxone.app.transport.DeliveryDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaService — Authority for encrypted media attachments and chunked transfers.
 *
 * Invariants:
 * 1. Large media payloads are NEVER stuffed into SecureEnvelope ratchet frames.
 * 2. Media payload is encrypted with a fresh random 256-bit AES key via streaming crypto (O(1) memory overhead).
 * 3. The symmetric key is exchanged securely inside the initial Double Ratchet message.
 * 4. Ratchet state advance is atomically committed with database entities inside encryptAndCommit.
 * 5. Chunks carry full conversationId, senderIdentity, and recipientBinding authentication.
 * 6. Transfer completion is confirmed by receiver verification (FILE_COMPLETE), not sender enqueuing.
 * 7. Temporary encrypted files are reliably cleaned up on cancel, delete, completion, and orphan sweep.
 * 8. Media transfers NEVER starve or block normal chat traffic, typing, presence, or ACKs (DeliveryPriority.LOW + yields).
 * 9. Transfer is fully resumable across process death via bitmask persistence and FILE_RESUME requests.
 */
class MediaService(
    private val context: Context? = null,
    private val sessionCrypto: SessionCrypto,
    private val connectionManager: ConnectionManager,
    private val agent: TorXAgent,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val mediaDao: MediaDao,
    private val mediaTransferDao: MediaTransferDao,
    private val outboxDao: OutboxDao? = null,
    private val localIdentityIdProvider: (() -> String?)? = null,
    private val mediaStorage: MediaStorage = MediaStorage(context),
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { it() },
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null,
    private val relationshipSendCoordinator: RelationshipSendCoordinator? = null,
    private val sessionStore: com.torxone.app.crypto.SessionStore? = null,
    private val dedicatedMediaTransport: DedicatedMediaTransport? = null,
    private val groupMessageDeliveryDao: GroupMessageDeliveryDao? = null,
    private val securityPolicyService: com.torxone.app.privacy.SecurityPolicyService? = null,
    private val recoveryIdleMs: Long = DEFAULT_RECOVERY_IDLE_MS,
    private val recoveryRounds: Int = DEFAULT_RECOVERY_ROUNDS,
    private val chunkAttemptLimit: Int = DEFAULT_CHUNK_ATTEMPTS,
    private val chunkRetryBaseMs: Long = 1_000L,
    private val onMissingChunks: (String) -> Unit = {},
    private val groupDao: GroupDao? = null
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB chunks fit comfortably in framing
        const val DEFAULT_RECOVERY_IDLE_MS = 60_000L
        const val DEFAULT_RECOVERY_ROUNDS = 3
        const val DEFAULT_CHUNK_ATTEMPTS = 3
    }

    private val sendCoordinator: RelationshipSendCoordinator by lazy {
        relationshipSendCoordinator ?: RelationshipSendCoordinator(
            connectionManager = connectionManager,
            sessionStore = requireNotNull(sessionStore) { "MediaService requires SessionStore when no coordinator is injected" },
            sessionCrypto = sessionCrypto,
            agent = agent,
            transactionRunner = transactionRunner
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val activeTransfers = ConcurrentHashMap<String, Job>()
    private val recoveryJobs = ConcurrentHashMap<String, Job>()

    init { require(recoveryIdleMs > 0 && recoveryRounds > 0 && chunkAttemptLimit > 0 && chunkRetryBaseMs > 0) }

    private suspend fun <T> withTrackedMediaFiles(mediaId: String, files: List<File>, block: suspend () -> T): T {
        val policy = securityPolicyService ?: return block()
        return policy.withTrackedFiles(mediaId, files, block)
    }

    private fun mediaDiagnostic(state: String, relationshipId: String? = null,
                                conversationId: String? = null, mediaId: String? = null,
                                transport: TransportType? = null) {
        DeliveryDiagnostics.event("media_transfer", relationshipId, conversationId, mediaId,
            transport = transport, state = state)
    }

    private suspend fun transferGroupMetadata(transfer: MediaTransferEntity): GroupEnvelopeMetadata? {
        val groupTransfer = transfer.transferId != transfer.mediaId ||
            conversationDao.getById(transfer.conversationId)?.type == ConversationType.GROUP
        if (!groupTransfer) return null
        val group = requireNotNull(groupDao?.getById(transfer.conversationId)) {
            "Group state is unavailable. Reopen the group before retrying this attachment."
        }
        require(group.epoch in 1..Int.MAX_VALUE.toLong()) { "Group state needs secure recovery" }
        return GroupEnvelopeMetadata(group.groupId, group.epoch.toInt(), group.epoch.toInt())
    }

    /** The shared bubble is derived from recipient rows; chunk acceptance never means delivery. */
    private suspend fun refreshMediaTransferStatus(mediaId: String) {
        val media = mediaDao.getById(mediaId) ?: return
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        if (transfers.isEmpty()) return
        if (media.status == MediaStatus.COMPLETE.name && transfers.all { it.direction == TransferDirection.DOWNLOAD.name }) return
        val upload = transfers.all { it.direction == TransferDirection.UPLOAD.name }
        val total = transfers.sumOf { it.totalChunks.coerceAtLeast(1).toLong() }
        val completed = transfers.sumOf {
            if (it.status == TransferStatus.COMPLETED.name) it.totalChunks.toLong()
            else it.completedChunks.coerceIn(0, it.totalChunks).toLong()
        }
        val progress = (completed.toDouble() / total).toFloat()
        val status = when {
            transfers.all { it.status == TransferStatus.COMPLETED.name } ->
                if (upload) MediaStatus.DELIVERED.name else MediaStatus.COMPLETE.name
            transfers.all { it.status in setOf(TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name) } -> MediaStatus.CANCELLED.name
            transfers.any { it.status == TransferStatus.ACTIVE.name } ->
                if (upload && completed == total) MediaStatus.SENT.name
                else if (upload) MediaStatus.UPLOADING.name else MediaStatus.DOWNLOADING.name
            transfers.any { it.status in setOf(TransferStatus.QUEUED.name, TransferStatus.IDLE.name) } -> MediaStatus.QUEUED.name
            transfers.any { it.status == TransferStatus.PAUSED.name } -> MediaStatus.PAUSED.name
            else -> MediaStatus.FAILED.name
        }
        mediaDao.updateStatus(mediaId, status, progress)
    }

    private suspend fun failUploadUnlessTerminal(transferId: String, mediaId: String) {
        transactionRunner {
            val latest = mediaTransferDao.getByTransferId(transferId) ?: return@transactionRunner
            if (latest.status !in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) {
                mediaTransferDao.updateStatus(transferId, TransferStatus.FAILED.name)
                refreshMediaTransferStatus(mediaId)
            }
        }
    }

    data class GroupMediaRecipient(
        val identityId: String,
        val relationshipId: String
    )

    fun observeMediaForMessage(messageId: String): Flow<MediaEntity?> =
        mediaDao.observeByMessageId(messageId)

    fun observeMediaForConversation(conversationId: String): Flow<List<MediaEntity>> =
        mediaDao.observeForConversation(conversationId)

    suspend fun getMediaByMessageId(messageId: String): MediaEntity? =
        mediaDao.getByMessageId(messageId)

    suspend fun getMediaById(mediaId: String): MediaEntity? =
        mediaDao.getById(mediaId)

    suspend fun resolveLocalConversationId(relationshipId: String): String? =
        contactDao?.getByRelationshipId(relationshipId)?.conversationId

    // ═══════════════════════════════════════════════════════════════
    //  Outgoing Media Sending
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sends an in-memory byte array media attachment.
     * Uses streaming encryption from a ByteArrayInputStream into the temp file to avoid duplicate byte allocations.
     */
    suspend fun sendMedia(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        type: MediaType,
        fileName: String,
        mimeType: String,
        rawBytes: ByteArray,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveformData: ByteArray? = null,
        replyToMessageId: String? = null,
        logicalMessageId: String? = null
    ): String {
        require(rawBytes.size.toLong() in 1..MediaProtocolCodec.MAX_MEDIA_BYTES) { "Media file exceeds supported size" }
        val expiresAt = securityPolicyService?.expiryForSend(conversationId, listOf(relationshipId), System.currentTimeMillis())
        require(fileName.isNotBlank() && fileName.length <= 255 && '/' !in fileName && '\\' !in fileName)
        val mediaId = UUID.randomUUID().toString()
        mediaDiagnostic("QUEUED", relationshipId, conversationId, mediaId)

        // 1. Save local plaintext copy for instant local viewing
        val localFile = mediaStorage.incomingFile(mediaId, fileName)
        val tempEncryptedFile = mediaStorage.getTempEncryptedFile(mediaId)
        return withTrackedMediaFiles(mediaId, listOf(localFile, tempEncryptedFile)) {
            localFile.writeBytes(rawBytes)

            // 2. Stream-encrypt directly to temp file
            val mediaKey = MediaCrypto.generateMediaKey()
            val encryptedSha256 = tempEncryptedFile.outputStream().use { outStream ->
                ByteArrayInputStream(rawBytes).use { inStream ->
                    MediaCrypto.encryptStream(mediaKey, inStream, outStream)
                }
            }
            val encryptedFileSize = tempEncryptedFile.length()

            sendMediaInternal(
                mediaId = mediaId,
                conversationId = conversationId,
                relationshipId = relationshipId,
                localIdentityId = localIdentityId,
                recipientId = recipientId,
                type = type,
                fileName = fileName,
                mimeType = mimeType,
                fileSize = rawBytes.size.toLong(),
                encryptedFileSize = encryptedFileSize,
                encryptedSha256 = encryptedSha256,
                mediaKey = mediaKey,
                localFilePath = localFile.absolutePath,
                tempEncryptedFile = tempEncryptedFile,
                durationMs = durationMs,
                thumbnailBytes = thumbnailBytes,
                waveformData = waveformData,
                replyToMessageId = replyToMessageId,
                logicalMessageId = logicalMessageId,
                expiresAt = expiresAt
            )
        }
    }

    /**
     * Sends a large media file from disk using pure streaming encryption.
     * Memory overhead is constant O(1) (64 KB buffer) regardless of file size (e.g. 500 MB+).
     */
    suspend fun sendMediaFile(
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        type: MediaType,
        file: File,
        mimeType: String,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveformData: ByteArray? = null,
        replyToMessageId: String? = null
    ): String {
        require(file.exists() && file.isFile) { "File does not exist: ${file.absolutePath}" }
        require(file.length() in 1..MediaProtocolCodec.MAX_MEDIA_BYTES) { "Media file exceeds supported size" }
        require(file.name.length <= 255 && '/' !in file.name && '\\' !in file.name)
        val expiresAt = securityPolicyService?.expiryForSend(conversationId, listOf(relationshipId), System.currentTimeMillis())
        val mediaId = UUID.randomUUID().toString()
        mediaDiagnostic("QUEUED", relationshipId, conversationId, mediaId)

        val tempEncryptedFile = mediaStorage.getTempEncryptedFile(mediaId)

        val localFile = if (expiresAt != null) File(mediaStorage.outgoingDir, "${mediaId}_${file.name}") else file
        val trackedFiles = if (expiresAt != null) listOf(localFile, tempEncryptedFile) else listOf(tempEncryptedFile)
        return withTrackedMediaFiles(mediaId, trackedFiles) {
            if (expiresAt != null) file.copyTo(localFile, overwrite = false)

            // Stream-encrypt from file directly into temp encrypted file
            val mediaKey = MediaCrypto.generateMediaKey()
            val encryptedSha256 = tempEncryptedFile.outputStream().use { outStream ->
                file.inputStream().use { inStream ->
                    MediaCrypto.encryptStream(mediaKey, inStream, outStream)
                }
            }
            val encryptedFileSize = tempEncryptedFile.length()

            sendMediaInternal(
                mediaId = mediaId,
                conversationId = conversationId,
                relationshipId = relationshipId,
                localIdentityId = localIdentityId,
                recipientId = recipientId,
                type = type,
                fileName = file.name,
                mimeType = mimeType,
                fileSize = file.length(),
                encryptedFileSize = encryptedFileSize,
                encryptedSha256 = encryptedSha256,
                mediaKey = mediaKey,
                localFilePath = localFile.absolutePath,
                tempEncryptedFile = tempEncryptedFile,
                durationMs = durationMs,
                thumbnailBytes = thumbnailBytes,
                waveformData = waveformData,
                replyToMessageId = replyToMessageId,
                expiresAt = expiresAt
            )
        }
    }

    /**
     * Creates one logical group message and one encrypted media object, then fans the
     * descriptor and chunks out over each member's pairwise session. Transfer rows are
     * recipient-specific so retrying one member cannot duplicate the message for everyone.
     */
    suspend fun sendGroupMedia(
        groupId: String,
        groupEpoch: Long,
        localIdentityId: String,
        recipients: List<GroupMediaRecipient>,
        type: MediaType,
        fileName: String,
        mimeType: String,
        rawBytes: ByteArray,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveformData: ByteArray? = null,
        replyToMessageId: String? = null,
        logicalMessageId: String? = null
    ): String {
        require(rawBytes.size.toLong() in 1..MediaProtocolCodec.MAX_MEDIA_BYTES) { "Media file exceeds supported size" }
        require(fileName.isNotBlank() && fileName.length <= 255 && '/' !in fileName && '\\' !in fileName)
        require(recipients.distinctBy { it.identityId }.size == recipients.size) { "Duplicate group media recipient" }
        val expiresAt = securityPolicyService?.expiryForSend(groupId, recipients.map { it.relationshipId }, System.currentTimeMillis())
        recipients.forEach {
            require(it.identityId.isNotBlank() && it.relationshipId.isNotBlank()) { "Invalid group media recipient" }
            require(it.identityId != ContactEntity.REMOTE_IDENTITY_UNKNOWN) { "Recipient identity is not authenticated" }
        }

        val mediaId = UUID.randomUUID().toString()
        val localFile = mediaStorage.incomingFile(mediaId, fileName)
        val encryptedFile = mediaStorage.getTempEncryptedFile(mediaId)
        return withTrackedMediaFiles(mediaId, listOf(localFile, encryptedFile)) {
            localFile.writeBytes(rawBytes)
            val mediaKey = MediaCrypto.generateMediaKey()
            val encryptedSha256 = encryptedFile.outputStream().use { output ->
                ByteArrayInputStream(rawBytes).use { input -> MediaCrypto.encryptStream(mediaKey, input, output) }
            }
            sendGroupMediaInternal(
                mediaId, groupId, groupEpoch, localIdentityId, recipients, type, fileName, mimeType,
                rawBytes.size.toLong(), encryptedFile.length(), encryptedSha256, mediaKey,
                localFile.absolutePath, encryptedFile, durationMs, thumbnailBytes, waveformData,
                replyToMessageId, logicalMessageId, expiresAt
            )
        }
    }

    private suspend fun sendGroupMediaInternal(
        mediaId: String,
        groupId: String,
        groupEpoch: Long,
        localIdentityId: String,
        recipients: List<GroupMediaRecipient>,
        type: MediaType,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        encryptedFileSize: Long,
        encryptedSha256: String,
        mediaKey: ByteArray,
        localFilePath: String,
        tempEncryptedFile: File,
        durationMs: Long?,
        thumbnailBytes: ByteArray?,
        waveformData: ByteArray?,
        replyToMessageId: String?,
        logicalMessageId: String? = null,
        expiresAt: Long? = null
    ): String {
        val deliveryDao = requireNotNull(groupMessageDeliveryDao) {
            "Group media requires GroupMessageDeliveryDao"
        }
        val messageId = logicalMessageId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val chunkSize = DEFAULT_CHUNK_SIZE
        val totalChunks = if (encryptedFileSize == 0L) 1 else ((encryptedFileSize + chunkSize - 1) / chunkSize).toInt()
        val protoMessageType = mediaMessageType(type)
        val descriptorBytes = MediaProtocolCodec.encodeDescriptor(
            MediaDescriptor(
                mediaId = mediaId,
                type = type,
                mimeType = mimeType,
                fileName = fileName,
                fileSize = fileSize,
                encryptedSha256 = encryptedSha256,
                mediaKeyBase64 = Base64.getEncoder().encodeToString(mediaKey),
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                durationMs = durationMs,
                thumbnailBase64 = thumbnailBytes?.let { Base64.getEncoder().encodeToString(it) },
                waveformBase64 = waveformData?.let { Base64.getEncoder().encodeToString(it) }
            )
        )
        val message = MessageEntity(
            logicalMessageId = messageId,
            conversationId = groupId,
            senderId = localIdentityId,
            type = protoMessageType.name,
            body = fileName,
            direction = MessageDirection.OUTGOING,
            status = if (recipients.isEmpty()) DeliveryStatus.DELIVERED.name else DeliveryStatus.QUEUED.name,
            createdAt = now,
            replyToMessageId = replyToMessageId,
            expiresAt = expiresAt
        )
        val media = MediaEntity(
            mediaId = mediaId,
            messageId = messageId,
            conversationId = groupId,
            mediaType = type.name,
            mimeType = mimeType,
            fileName = fileName,
            fileSize = fileSize,
            localPath = localFilePath,
            encryptedSha256 = encryptedSha256,
            mediaKey = mediaKey,
            thumbnailData = thumbnailBytes,
            durationMs = durationMs,
            waveformData = waveformData,
            status = if (recipients.isEmpty()) MediaStatus.COMPLETE.name else MediaStatus.QUEUED.name,
            transferProgress = if (recipients.isEmpty()) 1f else 0f,
            createdAt = now
        )
        val deliveries = recipients.map { recipient ->
            GroupMessageDeliveryEntity(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = messageId,
                recipientIdentityId = recipient.identityId,
                relationshipId = recipient.relationshipId,
                status = GroupDeliveryStatus.PENDING.name,
                createdAt = now,
                updatedAt = now
            )
        }
        val transfers = recipients.map { recipient ->
            MediaTransferEntity(
                transferId = groupTransferId(mediaId, recipient.relationshipId),
                mediaId = mediaId,
                conversationId = groupId,
                relationshipId = recipient.relationshipId,
                direction = TransferDirection.UPLOAD.name,
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                tempEncryptedPath = tempEncryptedFile.absolutePath,
                status = TransferStatus.IDLE.name,
                totalBytes = encryptedFileSize,
                updatedAt = now
            )
        }
        transactionRunner {
            messageDao.insertIfAbsent(message)
            mediaDao.insert(media)
            deliveries.forEach { deliveryDao.upsert(it) }
            transfers.forEach { mediaTransferDao.upsert(it) }
            conversationDao.updateLastMessage(groupId, messageId, mediaPreview(type, fileName), now)
            conversationDao.unarchive(groupId)
            conversationDao.updateManuallyUnread(groupId, false)
        }

        recipients.forEachIndexed { index, recipient ->
            val delivery = deliveries[index]
            val transfer = transfers[index]
            try {
                val connection = connectionManager.getConnectionByRelationship(recipient.relationshipId)
                    ?: throw IllegalStateException("No active connection for relationship ${recipient.relationshipId}")
                val outboxDeliveryId = UUID.randomUUID().toString()
                sendCoordinator.sendSequenced(
                    relationshipId = recipient.relationshipId,
                    connection = connection,
                    buildEnvelope = { sequence ->
                        SecureEnvelope(
                            logicalMessageId = messageId,
                            conversationId = groupId,
                            senderIdentity = localIdentityId,
                            recipientBinding = recipient.identityId,
                            messageType = protoMessageType,
                            timestamp = now,
                            payload = descriptorBytes,
                            replyToMessageId = replyToMessageId,
                            expiresAt = expiresAt,
                            groupMetadata = GroupEnvelopeMetadata(groupId, groupEpoch.toInt(), groupEpoch.toInt()),
                            directionSequence = sequence
                        )
                    },
                    persistDomain = { sequence, _, ciphertext ->
                        requireNotNull(outboxDao) { "Group media requires OutboxDao" }.insert(
                            OutboxEntity(
                                deliveryId = outboxDeliveryId,
                                logicalMessageId = messageId,
                                conversationId = groupId,
                                connectionId = connection.connectionId,
                                queueAddress = connection.sendQueueId,
                                ciphertext = ciphertext,
                                queueAuthenticator = connection.sendAuth,
                                status = DeliveryStatus.QUEUED.name,
                                nextAttemptAt = now,
                                createdAt = now,
                                updatedAt = now,
                                expectsAck = true,
                                applicationSequence = sequence,
                                relationshipId = recipient.relationshipId
                            )
                        )
                        deliveryDao.upsert(delivery.copy(outboxDeliveryId = outboxDeliveryId, status = GroupDeliveryStatus.QUEUED.name, updatedAt = now))
                        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.ACTIVE.name)
                    }
                )
                if (dedicatedMediaTransport == null) {
                    startChunkUpload(
                        mediaId, groupId, localIdentityId, recipient.identityId, recipient.relationshipId,
                        tempEncryptedFile, totalChunks, chunkSize, encryptedFileSize,
                        transferId = transfer.transferId,
                        groupMetadata = GroupEnvelopeMetadata(groupId, groupEpoch.toInt(), groupEpoch.toInt())
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mediaDiagnostic("RECOVERY_RETRY", recipient.relationshipId, groupId, mediaId)
                deliveryDao.upsert(delivery.copy(status = GroupDeliveryStatus.FAILED.name, updatedAt = System.currentTimeMillis()))
                mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            }
        }
        return messageId
    }

    private fun mediaMessageType(type: MediaType): MessageType = when (type) {
        MediaType.IMAGE -> MessageType.IMAGE
        MediaType.VIDEO -> MessageType.VIDEO
        MediaType.AUDIO -> MessageType.AUDIO
        MediaType.DOCUMENT -> MessageType.FILE
        MediaType.VOICE_NOTE -> MessageType.VOICE_NOTE
    }

    private fun mediaPreview(type: MediaType, fileName: String): String = when (type) {
        MediaType.IMAGE -> "📷 Photo"
        MediaType.VIDEO -> "🎥 Video"
        MediaType.VOICE_NOTE -> "🎤 Voice message"
        MediaType.AUDIO -> "🎵 Audio"
        MediaType.DOCUMENT -> "📄 $fileName"
    }

    private fun groupTransferId(mediaId: String, relationshipId: String): String = "$mediaId:$relationshipId"

    private suspend fun sendMediaInternal(
        mediaId: String,
        conversationId: String,
        relationshipId: String,
        localIdentityId: String,
        recipientId: String,
        type: MediaType,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        encryptedFileSize: Long,
        encryptedSha256: String,
        mediaKey: ByteArray,
        localFilePath: String,
        tempEncryptedFile: File,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveformData: ByteArray? = null,
        replyToMessageId: String? = null,
        logicalMessageId: String? = null,
        expiresAt: Long? = null
    ): String {
        if (recipientId.isBlank() || recipientId == com.torxone.app.data.entity.ContactEntity.REMOTE_IDENTITY_UNKNOWN) {
            throw IllegalStateException("Security information for this contact needs to be refreshed. Reconnect or re-add this contact.")
        }

        val messageId = logicalMessageId ?: UUID.randomUUID().toString()
        val deliveryId = UUID.randomUUID().toString()
        val chunkSize = DEFAULT_CHUNK_SIZE
        val totalChunks = if (encryptedFileSize == 0L) 1 else ((encryptedFileSize + chunkSize - 1) / chunkSize).toInt()

        val mediaKeyBase64 = Base64.getEncoder().encodeToString(mediaKey)
        val thumbBase64 = thumbnailBytes?.let { Base64.getEncoder().encodeToString(it) }
        val waveBase64 = waveformData?.let { Base64.getEncoder().encodeToString(it) }

        val descriptor = MediaDescriptor(
            mediaId = mediaId,
            type = type,
            mimeType = mimeType,
            fileName = fileName,
            fileSize = fileSize,
            encryptedSha256 = encryptedSha256,
            mediaKeyBase64 = mediaKeyBase64,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            durationMs = durationMs,
            thumbnailBase64 = thumbBase64,
            waveformBase64 = waveBase64
        )

        val protoMessageType = when (type) {
            MediaType.IMAGE -> MessageType.IMAGE
            MediaType.VIDEO -> MessageType.VIDEO
            MediaType.AUDIO -> MessageType.AUDIO
            MediaType.DOCUMENT -> MessageType.FILE
            MediaType.VOICE_NOTE -> MessageType.VOICE_NOTE
        }

        val now = System.currentTimeMillis()
        val descriptorBytes = MediaProtocolCodec.encodeDescriptor(descriptor)
        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        sendCoordinator.sendSequenced(
            relationshipId = relationshipId,
            connection = connection,
            buildEnvelope = { seq ->
                SecureEnvelope(
                    logicalMessageId = messageId,
                    conversationId = conversationId,
                    senderIdentity = localIdentityId,
                    recipientBinding = recipientId,
                    messageType = protoMessageType,
                    timestamp = now,
                    payload = descriptorBytes,
                    replyToMessageId = replyToMessageId,
                    expiresAt = expiresAt,
                    directionSequence = seq
                )
            },
            persistDomain = { seq, _, ciphertext ->
                val messageEntity = MessageEntity(
                    logicalMessageId = messageId,
                    conversationId = conversationId,
                    senderId = localIdentityId,
                    type = protoMessageType.name,
                    body = fileName,
                    direction = MessageDirection.OUTGOING,
                    status = DeliveryStatus.QUEUED.name,
                    createdAt = now,
                    replyToMessageId = replyToMessageId,
                    expiresAt = expiresAt
                )

                val mediaEntity = MediaEntity(
                    mediaId = mediaId,
                    messageId = messageId,
                    conversationId = conversationId,
                    mediaType = type.name,
                    mimeType = mimeType,
                    fileName = fileName,
                    fileSize = fileSize,
                    localPath = localFilePath,
                    encryptedSha256 = encryptedSha256,
                    mediaKey = mediaKey,
                    thumbnailData = thumbnailBytes,
                    durationMs = durationMs,
                    waveformData = waveformData,
                    status = MediaStatus.QUEUED.name,
                    transferProgress = 0f,
                    createdAt = now
                )

                val transferEntity = MediaTransferEntity(
                    transferId = mediaId,
                    mediaId = mediaId,
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    direction = TransferDirection.UPLOAD.name,
                    totalChunks = totalChunks,
                    chunkSize = chunkSize,
                    completedChunks = 0,
                    chunkBitmask = "",
                    tempEncryptedPath = tempEncryptedFile.absolutePath,
                    status = TransferStatus.ACTIVE.name,
                    bytesTransferred = 0L,
                    totalBytes = encryptedFileSize,
                    updatedAt = now
                )

                val outboxEntity = OutboxEntity(
                    deliveryId = deliveryId,
                    logicalMessageId = messageId,
                    conversationId = conversationId,
                    connectionId = connection.connectionId,
                    queueAddress = connection.sendQueueId,
                    ciphertext = ciphertext,
                    queueAuthenticator = connection.sendAuth,
                    status = DeliveryStatus.QUEUED.name,
                    attemptCount = 0,
                    nextAttemptAt = now,
                    createdAt = now,
                    updatedAt = now,
                    expectsAck = true,
                    applicationSequence = seq,
                    relationshipId = connection.relationshipId
                )

                messageDao.insertIfAbsent(messageEntity)
                mediaDao.insert(mediaEntity)
                mediaTransferDao.upsert(transferEntity)
                outboxDao?.insert(outboxEntity)

                val previewText = when (type) {
                    MediaType.IMAGE -> "📷 Photo"
                    MediaType.VIDEO -> "🎥 Video"
                    MediaType.VOICE_NOTE -> "🎤 Voice message"
                    MediaType.AUDIO -> "🎵 Audio"
                    MediaType.DOCUMENT -> "📄 $fileName"
                }
                conversationDao.updateLastMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    preview = previewText,
                    time = now
                )
                conversationDao.unarchive(conversationId)
                conversationDao.updateManuallyUnread(conversationId, false)
            }
        )

        // Media V2 waits for an authenticated FILE_ACCEPT before opening the
        // dedicated stream. Legacy/test composition retains the old path.
        if (dedicatedMediaTransport == null) {
            startChunkUpload(
                mediaId = mediaId,
                conversationId = conversationId,
                localIdentityId = localIdentityId,
                recipientId = recipientId,
                relationshipId = relationshipId,
                tempEncryptedFile = tempEncryptedFile,
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                totalBytes = encryptedFileSize
            )
        }

        return messageId
    }

    private fun startChunkUpload(
        mediaId: String,
        conversationId: String,
        localIdentityId: String,
        recipientId: String,
        relationshipId: String,
        tempEncryptedFile: File,
        totalChunks: Int,
        chunkSize: Int,
        totalBytes: Long,
        chunkIndicesToUpload: List<Int>? = null,
        transferId: String = mediaId,
        groupMetadata: GroupEnvelopeMetadata? = null
    ) {
        if (dedicatedMediaTransport != null) {
            startDedicatedChunkUpload(
                mediaId, relationshipId, tempEncryptedFile, totalChunks, chunkSize, totalBytes, chunkIndicesToUpload, transferId
            )
            return
        }
        val job = activeTransfers.compute(transferId) { _, existing ->
            existing?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
            try {
                val transfer = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                val bitmaskSet = transfer.chunkBitmask.split(',').mapNotNull(String::toIntOrNull).toMutableSet()
                val indices = chunkIndicesToUpload ?: (0 until totalChunks).filterNot(bitmaskSet::contains)
                if (!updateUploadProgress(transferId, mediaId, bitmaskSet, chunkSize, totalBytes,
                        TransferStatus.ACTIVE.name)) return@launch

                for (chunkIndex in indices) {
                    ensureActive()
                    if (uploadHasExpired(mediaId)) return@launch
                    val current = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                    if (current.status in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) return@launch

                    // Read chunk slice
                    val chunkData = mediaStorage.readChunk(
                        encryptedFile = tempEncryptedFile,
                        chunkIndex = chunkIndex,
                        chunkSize = chunkSize,
                        totalBytes = totalBytes
                    )

                    val chunkPayload = MediaChunkPayload(
                        mediaId = mediaId,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        chunkData = chunkData
                    )
                    val rawChunkBytes = MediaProtocolCodec.encodeChunk(chunkPayload)

                    // Dispatch chunk envelope over transport with LOW priority so chat traffic is prioritized.
                    // Populate senderIdentity, recipientBinding, and conversationId for authentication.
                    val connection = requireNotNull(connectionManager.getConnectionByRelationship(relationshipId)) {
                        "Secure peer connection unavailable"
                    }
                        sendCoordinator.sendSequenced(
                            relationshipId = relationshipId,
                            connection = connection,
                            buildEnvelope = { sequence ->
                                SecureEnvelope(
                                    logicalMessageId = UUID.randomUUID().toString(),
                                    conversationId = conversationId,
                                    senderIdentity = localIdentityId,
                                    recipientBinding = recipientId,
                                    messageType = MessageType.FILE_PROGRESS,
                                    timestamp = System.currentTimeMillis(),
                                    payload = rawChunkBytes,
                                    groupMetadata = groupMetadata,
                                    directionSequence = sequence
                                )
                            }
                        ) { sequence, envelope, ciphertext ->
                            val latest = mediaTransferDao.getByTransferId(transferId) ?: error("Attachment is no longer available")
                            check(latest.status !in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) {
                                "Attachment transfer stopped"
                            }
                            val now = System.currentTimeMillis()
                            requireNotNull(outboxDao) { "MediaService requires OutboxDao for durable chunk delivery" }
                                .insert(
                                    OutboxEntity(
                                        deliveryId = UUID.randomUUID().toString(),
                                        logicalMessageId = envelope.logicalMessageId,
                                        conversationId = conversationId,
                                        connectionId = connection.connectionId,
                                        queueAddress = connection.sendQueueId,
                                        ciphertext = ciphertext,
                                        queueAuthenticator = connection.sendAuth,
                                        status = DeliveryStatus.QUEUED.name,
                                        priority = DeliveryPriority.LOW,
                                        nextAttemptAt = now,
                                        createdAt = now,
                                        updatedAt = now,
                                        expectsAck = true,
                                        applicationSequence = sequence,
                                        relationshipId = relationshipId
                                    )
                                )
                            bitmaskSet.add(chunkIndex)
                            updateUploadProgress(transferId, mediaId, bitmaskSet, chunkSize, totalBytes, TransferStatus.ACTIVE.name)
                        }

                    // Cooperative yield: ensures text messages, reactions, typing, and ACKs NEVER starve
                    delay(5)
                }

                mediaDiagnostic("QUEUED", relationshipId, conversationId, mediaId)
            } catch (_: CancellationException) {
                // Pause/cancel owns the persisted state; replacement must not overwrite it.
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mediaDiagnostic("UPLOAD_REJECTED", relationshipId, conversationId, mediaId)
                failUploadUnlessTerminal(transferId, mediaId)
            } finally {
                activeTransfers.remove(transferId, currentCoroutineContext().job)
            }
            }
        }
        job?.start()
    }

    private fun startDedicatedChunkUpload(
        mediaId: String,
        relationshipId: String,
        encryptedFile: File,
        totalChunks: Int,
        chunkSize: Int,
        totalBytes: Long,
        requestedIndices: List<Int>? = null,
        transferId: String = mediaId
    ) {
        val job = activeTransfers.compute(transferId) { _, existing ->
            existing?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
            try {
                val media = mediaDao.getById(mediaId) ?: error("Missing media record")
                val connection = connectionManager.getConnectionByRelationship(relationshipId)
                    ?: error("No active connection for media transfer")
                val destination = TransportDestination(connection.sendQueueId, relationshipId = connection.relationshipId)
                val transfer = mediaTransferDao.getByTransferId(transferId) ?: error("Missing transfer record")
                if (transfer.status in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) return@launch
                val alreadySent = transfer.chunkBitmask.split(',').mapNotNull(String::toIntOrNull).toMutableSet()
                val indices = requestedIndices ?: (0 until totalChunks).filterNot(alreadySent::contains)
                if (!updateUploadProgress(transferId, mediaId, alreadySent, chunkSize, totalBytes,
                        TransferStatus.ACTIVE.name)) return@launch

                for (chunkIndex in indices) {
                    ensureActive()
                    if (uploadHasExpired(mediaId)) break
                    val current = mediaTransferDao.getByTransferId(transferId) ?: break
                    if (current.status == TransferStatus.PAUSED.name || current.status == TransferStatus.CANCELLED.name) break
                    val plaintextChunk = mediaStorage.readChunk(encryptedFile, chunkIndex, chunkSize, totalBytes)
                    val encryptedChunk = DedicatedMediaChunkCrypto.encrypt(
                        media.mediaKey, mediaId, relationshipId, chunkIndex, totalChunks, plaintextChunk
                    )
                    var retryAttempt = 0
                    while (true) {
                        ensureActive()
                        if (uploadHasExpired(mediaId)) return@launch
                        val pending = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                        if (pending.status in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) return@launch
                        val result = dedicatedMediaTransport!!.send(
                            destination,
                            DedicatedMediaFrame(mediaId, chunkIndex, totalChunks, encryptedChunk)
                        )
                        if (result is TransportResult.Accepted) {
                            break
                        }
                        // Preserve the file and chunk bitmap while offline. Startup recovery
                        // resumes QUEUED transfers; a network error is not a terminal failure.
                        if (!updateUploadProgress(transferId, mediaId, alreadySent, chunkSize, totalBytes,
                                TransferStatus.QUEUED.name)) return@launch
                        retryAttempt++
                        if (retryAttempt >= chunkAttemptLimit) {
                            com.torxone.app.transport.DeliveryDiagnostics.event("media_waiting_for_peer", relationshipId,
                                transfer.conversationId, mediaId, state = "CHUNK_RETRY_BUDGET", attempt = retryAttempt)
                            return@launch
                        }
                        delay(minOf(60_000L, chunkRetryBaseMs * (1L shl minOf(retryAttempt - 1, 6))))
                    }
                    alreadySent.add(chunkIndex)
                    if (!updateUploadProgress(transferId, mediaId, alreadySent, chunkSize, totalBytes,
                            TransferStatus.ACTIVE.name)) return@launch
                    yield()
                }
            } catch (_: CancellationException) {
                // Pause/cancel methods persist the intended terminal state.
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                com.torxone.app.transport.DeliveryDiagnostics.event("media_upload_failed", relationshipId,
                    delivery = mediaId, state = "UPLOAD_REJECTED")
                failUploadUnlessTerminal(transferId, mediaId)
            } finally {
                activeTransfers.remove(transferId, currentCoroutineContext().job)
            }
            }
        }
        job?.start()
    }

    private suspend fun updateUploadProgress(transferId: String, mediaId: String, attempted: Set<Int>,
                                             chunkSize: Int, totalBytes: Long, status: String): Boolean {
        var updated = false
        transactionRunner {
            val latest = mediaTransferDao.getByTransferId(transferId)
            if (latest != null && latest.status !in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) {
                val combined = (latest.chunkBitmask.split(',').mapNotNull(String::toIntOrNull) + attempted).toSet()
                val bytes = combined.sumOf { index -> minOf(chunkSize.toLong(), totalBytes - index.toLong() * chunkSize) }
                mediaTransferDao.updateProgress(transferId, combined.size, combined.sorted().joinToString(","), bytes, status)
                refreshMediaTransferStatus(mediaId)
                updated = true
            }
        }
        return updated
    }

    // ═══════════════════════════════════════════════════════════════
    //  Incoming Descriptor Handling
    // ═══════════════════════════════════════════════════════════════

    suspend fun handleIncomingDescriptor(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        val descriptor = try {
            MediaProtocolCodec.decodeDescriptor(envelope.payload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", connection.relationshipId, envelope.conversationId)
            return false
        }

        val messageId = envelope.logicalMessageId
        val mediaId = descriptor.mediaId
        val isGroup = envelope.groupMetadata != null
        val conversationId: String
        if (isGroup) {
            conversationId = envelope.groupMetadata!!.groupId
        } else {
            if (contactDao != null) {
                val contact = contactDao.getByRelationshipId(connection.relationshipId)
                if (contact == null || contact.conversationId.isBlank()) {
                    mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", connection.relationshipId)
                    return false
                }
                conversationId = contact.conversationId
            } else {
                conversationId = envelope.conversationId
            }
        }
        val now = System.currentTimeMillis()

        mediaDiagnostic("QUEUED", connection.relationshipId, conversationId, mediaId)

        messageDao.getById(messageId)?.let {
            // Different transport envelopes may retry one logical descriptor. Never
            // recreate erased media, keys or thumbnails for an existing tombstone.
            return it.senderId == envelope.senderIdentity && it.conversationId == conversationId
        }
        suspend fun persistExpiredDescriptor() {
            requireNotNull(securityPolicyService) { "Expiry cleanup is unavailable" }.recordExpiredDescriptor(
                MessageEntity(messageId, conversationId, envelope.senderIdentity, envelope.messageType.name,
                    null, MessageDirection.INCOMING, DeliveryStatus.DELIVERED.name,
                    createdAt = envelope.timestamp, receivedAt = System.currentTimeMillis(), expiresAt = envelope.expiresAt),
                mediaId, connection.relationshipId
            )
        }
        if (com.torxone.app.privacy.DisappearingPolicy.expired(envelope.expiresAt, now)) {
            transactionRunner { persistExpiredDescriptor() }
            return true
        }

        val mediaKey = try {
            Base64.getDecoder().decode(descriptor.mediaKeyBase64)
        } catch (_: Exception) {
            mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", connection.relationshipId, conversationId, mediaId)
            return false
        }

        val thumbBytes = descriptor.thumbnailBase64?.let {
            try { Base64.getDecoder().decode(it) } catch (_: Exception) { null }
        }
        val waveBytes = descriptor.waveformBase64?.let {
            try { Base64.getDecoder().decode(it) } catch (_: Exception) { null }
        }

        val tempEncryptedFile = mediaStorage.getTempEncryptedFile(mediaId)

        // Check Low-Bandwidth / Auto-Download preference
        val autoDownload = shouldAutoDownload(descriptor.type)
        val initialStatus = if (autoDownload) MediaStatus.DOWNLOADING else MediaStatus.QUEUED

        transactionRunner {
            if (com.torxone.app.privacy.DisappearingPolicy.expired(envelope.expiresAt, System.currentTimeMillis())) {
                persistExpiredDescriptor()
                return@transactionRunner
            }
            val messageEntity = MessageEntity(
                logicalMessageId = messageId,
                conversationId = conversationId,
                senderId = envelope.senderIdentity,
                type = envelope.messageType.name,
                body = descriptor.fileName,
                direction = MessageDirection.INCOMING,
                status = DeliveryStatus.DELIVERED.name,
                createdAt = envelope.timestamp,
                receivedAt = now,
                replyToMessageId = envelope.replyToMessageId,
                expiresAt = envelope.expiresAt
            )
            messageDao.insertIfAbsent(messageEntity)

            val mediaEntity = MediaEntity(
                mediaId = mediaId,
                messageId = messageId,
                conversationId = conversationId,
                mediaType = descriptor.type.name,
                mimeType = descriptor.mimeType,
                fileName = descriptor.fileName,
                fileSize = descriptor.fileSize,
                localPath = null,
                encryptedSha256 = descriptor.encryptedSha256,
                mediaKey = mediaKey,
                thumbnailData = thumbBytes,
                durationMs = descriptor.durationMs,
                waveformData = waveBytes,
                status = initialStatus.name,
                transferProgress = 0f,
                createdAt = now
            )
            mediaDao.insert(mediaEntity)
            securityPolicyService?.trackExpectedFile(mediaId, mediaStorage.incomingFile(mediaId, descriptor.fileName))

            val transferEntity = MediaTransferEntity(
                transferId = mediaId,
                mediaId = mediaId,
                conversationId = conversationId,
                relationshipId = connection.relationshipId,
                direction = TransferDirection.DOWNLOAD.name,
                totalChunks = descriptor.totalChunks,
                chunkSize = descriptor.chunkSize,
                completedChunks = 0,
                chunkBitmask = "",
                tempEncryptedPath = tempEncryptedFile.absolutePath,
                status = if (autoDownload) TransferStatus.ACTIVE.name else TransferStatus.PAUSED.name,
                bytesTransferred = 0L,
                totalBytes = descriptor.fileSize + 28L,
                updatedAt = now
            )
            mediaTransferDao.upsert(transferEntity)

            val previewText = when (descriptor.type) {
                MediaType.IMAGE -> "📷 Photo"
                MediaType.VIDEO -> "🎥 Video"
                MediaType.VOICE_NOTE -> "🎤 Voice message"
                MediaType.AUDIO -> "🎵 Audio"
                MediaType.DOCUMENT -> "📄 ${descriptor.fileName}"
            }
            conversationDao.updateLastMessage(
                conversationId = conversationId,
                messageId = messageId,
                preview = previewText,
                time = envelope.timestamp
            )
        }

        if (com.torxone.app.privacy.DisappearingPolicy.expired(envelope.expiresAt, System.currentTimeMillis())) return true
        if (autoDownload && dedicatedMediaTransport != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sendAccept(connection, conversationId, envelope.senderIdentity, mediaId)
            }
            startDownloadRecovery(mediaId)
        }

        return true
    }

    private suspend fun sendAccept(
        connection: Connection,
        conversationId: String,
        recipientIdentity: String,
        mediaId: String
    ) {
        val payload = MediaProtocolCodec.encodeAccept(MediaAcceptPayload(mediaId))
        val senderIdentity = localIdentityIdProvider?.invoke() ?: return
        val metadata = mediaTransferDao.getByMediaIdAndRelationship(mediaId, connection.relationshipId)
            ?.let { transferGroupMetadata(it) }
        sendCoordinator.sendSequenced(
            relationshipId = connection.relationshipId,
            connection = connection,
            buildEnvelope = { sequence ->
                SecureEnvelope(
                    logicalMessageId = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    senderIdentity = senderIdentity,
                    recipientBinding = recipientIdentity,
                    messageType = MessageType.FILE_ACCEPT,
                    timestamp = System.currentTimeMillis(),
                    payload = payload,
                    groupMetadata = metadata,
                    directionSequence = sequence
                )
            }
        ) { sequence, envelope, ciphertext ->
            val now = System.currentTimeMillis()
            requireNotNull(outboxDao) { "MediaService requires OutboxDao for FILE_ACCEPT" }.insert(
                OutboxEntity(
                    deliveryId = UUID.randomUUID().toString(),
                    logicalMessageId = envelope.logicalMessageId,
                    conversationId = conversationId,
                    connectionId = connection.connectionId,
                    queueAddress = connection.sendQueueId,
                    ciphertext = ciphertext,
                    queueAuthenticator = connection.sendAuth,
                    status = DeliveryStatus.QUEUED.name,
                    priority = DeliveryPriority.HIGH,
                    nextAttemptAt = now,
                    createdAt = now,
                    updatedAt = now,
                    expectsAck = true,
                    applicationSequence = sequence,
                    relationshipId = connection.relationshipId
                )
            )
        }
    }

    suspend fun handleIncomingAccept(
        connection: Connection,
        envelope: SecureEnvelope,
        accept: MediaAcceptPayload
    ): Boolean {
        if (isExpiredFrame(accept.mediaId, connection, envelope)) return true
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(accept.mediaId, connection.relationshipId) ?: return false
        if (dedicatedMediaTransport == null || transfer.relationshipId != connection.relationshipId ||
            transfer.direction != TransferDirection.UPLOAD.name || transfer.conversationId != envelope.conversationId ||
            transfer.status == TransferStatus.CANCELLED.name || transfer.status == TransferStatus.COMPLETED.name
        ) return false
        val file = File(transfer.tempEncryptedPath)
        if (!file.exists()) return false
        startChunkUpload(
            mediaId = transfer.mediaId,
            conversationId = transfer.conversationId,
            localIdentityId = envelope.recipientBinding,
            recipientId = envelope.senderIdentity,
            relationshipId = transfer.relationshipId,
            tempEncryptedFile = file,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            totalBytes = transfer.totalBytes,
            transferId = transfer.transferId,
            groupMetadata = envelope.groupMetadata
        )
        return true
    }

    private suspend fun shouldAutoDownload(type: MediaType): Boolean {
        if (appSettingsRepository == null) return true
        val autoDownload = appSettingsRepository.autoDownloadMedia.first()
        if (!autoDownload) return false
        val lowBandwidth = appSettingsRepository.isLowBandwidthMode()
        return if (lowBandwidth) {
            type == MediaType.VOICE_NOTE || type == MediaType.IMAGE
        } else {
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Incoming Chunk Handling & Assembly
    // ═══════════════════════════════════════════════════════════════

    suspend fun handleIncomingChunk(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        val chunk = try {
            MediaProtocolCodec.decodeChunk(envelope.payload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", connection.relationshipId, envelope.conversationId)
            return false
        }

        val mediaId = chunk.mediaId
        if (isExpiredFrame(mediaId, connection, envelope)) return true
        val media = mediaDao.getById(mediaId) ?: return false
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId ||
            transfer.direction != TransferDirection.DOWNLOAD.name ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId) ||
            chunk.totalChunks != transfer.totalChunks || chunk.chunkIndex !in 0 until transfer.totalChunks ||
            chunk.chunkData.size > transfer.chunkSize || transfer.chunkSize !in 1..MediaProtocolCodec.MAX_CHUNK_BYTES
        ) return false

        if (transfer.status == TransferStatus.PAUSED.name || transfer.status == TransferStatus.COMPLETED.name ||
            transfer.status == TransferStatus.CANCELLED.name) return true
        if (transfer.status !in setOf(TransferStatus.ACTIVE.name, TransferStatus.QUEUED.name)) return false
        val expectedBytes = minOf(transfer.chunkSize.toLong(),
            transfer.totalBytes - chunk.chunkIndex.toLong() * transfer.chunkSize).toInt()
        if (expectedBytes <= 0 || chunk.chunkData.size != expectedBytes) return false
        if (transfer.chunkBitmask.split(",").any { it.toIntOrNull() == chunk.chunkIndex }) return true
        // Write chunk directly to temp file at index offset
        mediaStorage.writeChunk(
            mediaId = mediaId,
            chunkIndex = chunk.chunkIndex,
            chunkSize = transfer.chunkSize,
            chunkData = chunk.chunkData
        )

        if (isExpiredFrame(mediaId, connection, envelope)) {
            securityPolicyService?.discardFile(mediaStorage.getTempEncryptedFile(mediaId).absolutePath)
            return true
        }

        // Record each chunk exactly once even if transport retries deliver duplicates.
        val chunkWrite = mediaTransferDao.recordChunkIfMissing(
            transferId = transfer.transferId,
            chunkIndex = chunk.chunkIndex,
            chunkBytes = chunk.chunkData.size.toLong()
        )
        if (chunkWrite == 0) {
            if (mediaTransferDao.getByTransferId(transfer.transferId)?.status == TransferStatus.CANCELLED.name)
                mediaStorage.cleanupTempTransfer(mediaId)
            return true
        }
        val receivedIndices = transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
        receivedIndices.add(chunk.chunkIndex)
        val completedCount = receivedIndices.size
        val progress = completedCount.toFloat() / transfer.totalChunks

        mediaDao.updateStatus(mediaId, MediaStatus.DOWNLOADING.name, progress)
        if (dedicatedMediaTransport != null) startDownloadRecovery(transfer.transferId)

        // Check if all chunks have arrived!
        if (completedCount >= transfer.totalChunks) {
            finalizeIncomingMedia(mediaId, media, transfer, envelope.senderIdentity)
        }

        return true
    }

    suspend fun handleDedicatedMediaFrame(rawBytes: ByteArray, transportType: TransportType): Boolean =
        handleDedicatedMediaFrame(rawBytes, transportType, null)

    suspend fun handleDedicatedMediaFrame(rawBytes: ByteArray, transportType: TransportType,
                                         authenticatedRelationshipId: String?,
                                         onAuthenticatedRelationship: ((String) -> Boolean)? = null): Boolean {
        val frame = try {
            DedicatedMediaFrameCodec.decode(rawBytes)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mediaDiagnostic("INVALID_LENGTH", transport = transportType)
            return false
        }
        if (authenticatedRelationshipId != null && securityPolicyService?.expiredMedia(frame.mediaId, authenticatedRelationshipId) == true) return true
        val media = mediaDao.getById(frame.mediaId) ?: return false
        val transfer = mediaTransferDao.getByMediaId(frame.mediaId) ?: return false
        if (authenticatedRelationshipId != null && transfer.relationshipId != authenticatedRelationshipId) return false
        if (transfer.direction != TransferDirection.DOWNLOAD.name || frame.totalChunks != transfer.totalChunks ||
            transfer.status !in setOf(TransferStatus.ACTIVE.name, TransferStatus.QUEUED.name, TransferStatus.COMPLETED.name)
        ) return false
        val chunk = try {
            DedicatedMediaChunkCrypto.decrypt(
                media.mediaKey,
                frame.mediaId,
                transfer.relationshipId,
                frame.chunkIndex,
                frame.totalChunks,
                frame.encryptedChunk
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", transfer.relationshipId, transfer.conversationId, frame.mediaId, transportType)
            return false
        }
        if (onAuthenticatedRelationship?.invoke(transfer.relationshipId) == false) return false
        // A verified duplicate after completion is harmless. Completion is already durable.
        if (transfer.status == TransferStatus.COMPLETED.name) return true
        val connection = connectionManager.getConnectionByRelationship(transfer.relationshipId) ?: return false
        val remoteIdentity = contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId
            ?: messageDao.getById(media.messageId)?.senderId.orEmpty()
        val envelope = SecureEnvelope(
            conversationId = transfer.conversationId,
            senderIdentity = remoteIdentity,
            recipientBinding = localIdentityIdProvider?.invoke().orEmpty(),
            messageType = MessageType.FILE_PROGRESS,
            payload = MediaProtocolCodec.encodeChunk(
                MediaChunkPayload(frame.mediaId, frame.chunkIndex, frame.totalChunks, chunk)
            )
        )
        return handleIncomingChunk(connection, envelope)
    }

    private suspend fun finalizeIncomingMedia(
        mediaId: String,
        media: MediaEntity,
        transfer: MediaTransferEntity,
        senderIdentity: String = ""
    ) {
        if (securityPolicyService?.expiredMedia(mediaId, transfer.relationshipId, transfer.conversationId) == true) return
        val expectedTempFile = mediaStorage.getTempEncryptedFile(mediaId).canonicalFile
        val tempFile = File(transfer.tempEncryptedPath).canonicalFile
        if (tempFile != expectedTempFile || media.mediaId != transfer.mediaId || media.conversationId != transfer.conversationId) {
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            return
        }
        if (!tempFile.exists()) {
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            return
        }

        // 1. Streaming verify SHA-256 integrity over tempFile
        if (!MediaCrypto.verifyFileIntegrity(tempFile, media.encryptedSha256)) {
            mediaDiagnostic("FAILED", transfer.relationshipId, transfer.conversationId, mediaId)
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            mediaStorage.cleanupTempTransfer(mediaId)
            return
        }

        // 2. Stream-decrypt ciphertext directly to incoming destination file
        val destination = mediaStorage.incomingFile(mediaId, media.fileName)
        withTrackedMediaFiles(mediaId, listOf(destination, tempFile)) {
            try {
                require(media.fileSize in 1..MediaProtocolCodec.MAX_MEDIA_BYTES)
                require(media.mediaKey.size == 32)
                require(transfer.totalChunks in 1..MediaProtocolCodec.MAX_CHUNK_COUNT)
                tempFile.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        MediaCrypto.decryptStream(media.mediaKey, input, output)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", transfer.relationshipId, transfer.conversationId, mediaId)
                securityPolicyService?.discardFile(destination.absolutePath)
                mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
                mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
                mediaStorage.cleanupTempTransfer(mediaId)
                return@withTrackedMediaFiles
            }

            if (securityPolicyService?.expiredMedia(mediaId, transfer.relationshipId, transfer.conversationId) == true ||
                messageDao.getById(media.messageId)?.deletedAt != null) {
                securityPolicyService?.discardFile(destination.absolutePath)
                securityPolicyService?.discardFile(tempFile.absolutePath)
                return@withTrackedMediaFiles
            }

            // 3. Keep ciphertext until the completion signal is durably committed.
            // Legacy chunks may still be inside their ratchet/receive transaction here.
            mediaDao.updateLocalPathAndStatus(mediaId, destination.absolutePath, MediaStatus.COMPLETE.name)

            mediaDiagnostic("MEDIA_LOCAL_COMPLETE", transfer.relationshipId, transfer.conversationId, mediaId)

            // 4. Send FILE_COMPLETE confirmation back to sender!
            // Launched asynchronously so that if called within a decrypt commit block,
            // it does not deadlock the relationship SessionActor.
            // UNDISPATCHED: starts immediately on current thread (queuing the encrypt in SessionActor
            // right away) and resumes on scope dispatcher after first suspension. This avoids
            // Dispatchers.IO scheduling delays that cause test flakiness under load.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sendCompleteConfirmation(transfer.relationshipId, media, senderIdentity, transfer.transferId)
            }
        }
    }

    private suspend fun sendCompleteConfirmation(
        relationshipId: String,
        media: MediaEntity,
        recipientBinding: String,
        completedTransferId: String
    ) {
        val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return
        val completePayload = MediaCompletePayload(
            mediaId = media.mediaId,
            verifiedSha256 = media.encryptedSha256
        )
        val completeBytes = MediaProtocolCodec.encodeComplete(completePayload)
        val senderId = localIdentityIdProvider?.invoke() ?: ""
        try {
            val metadata = mediaTransferDao.getByTransferId(completedTransferId)?.let { transferGroupMetadata(it) }
            sendCoordinator.sendSequenced(
                relationshipId = relationshipId,
                connection = connection,
                buildEnvelope = { sequence ->
                    SecureEnvelope(
                        logicalMessageId = UUID.randomUUID().toString(),
                        conversationId = media.conversationId,
                        senderIdentity = senderId,
                        recipientBinding = recipientBinding,
                        messageType = MessageType.FILE_COMPLETE,
                        timestamp = System.currentTimeMillis(),
                        payload = completeBytes,
                        groupMetadata = metadata,
                        directionSequence = sequence
                    )
                }
            ) { sequence, envelope, ciphertext ->
                val now = System.currentTimeMillis()
                requireNotNull(outboxDao) { "MediaService requires OutboxDao for completion delivery" }
                    .insert(
                        OutboxEntity(
                            deliveryId = UUID.randomUUID().toString(),
                            logicalMessageId = envelope.logicalMessageId,
                            conversationId = media.conversationId,
                            connectionId = connection.connectionId,
                            queueAddress = connection.sendQueueId,
                            ciphertext = ciphertext,
                            queueAuthenticator = connection.sendAuth,
                            status = DeliveryStatus.QUEUED.name,
                            priority = DeliveryPriority.HIGH,
                            nextAttemptAt = now,
                            createdAt = now,
                            updatedAt = now,
                            expectsAck = true,
                            applicationSequence = sequence,
                            relationshipId = relationshipId
                        )
                    )
                // This callback shares the ratchet/outbox transaction. A restart cannot
                // observe a completed receiver without its durable completion signal.
                mediaTransferDao.updateStatus(completedTransferId, TransferStatus.COMPLETED.name)
            }
            // The receive transaction and the completion outbox transaction have both
            // committed. A crash before this cleanup leaves only a recoverable orphan.
            mediaStorage.cleanupTempTransfer(media.mediaId)
            mediaDiagnostic("QUEUED", relationshipId, media.conversationId, media.mediaId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            com.torxone.app.transport.DeliveryDiagnostics.event("media_completion_retry", relationshipId,
                media.conversationId, media.mediaId, state = "COMPLETION_NOT_COMMITTED")
        }
    }

    suspend fun handleIncomingCompletion(complete: MediaCompletePayload): Boolean {
        val transfer = mediaTransferDao.getAllByMediaId(complete.mediaId).singleOrNull() ?: return false
        if (transfer.transferId != transfer.mediaId || transfer.direction != TransferDirection.UPLOAD.name) return false
        val media = mediaDao.getById(complete.mediaId) ?: return false
        if (!media.encryptedSha256.equals(complete.verifiedSha256, ignoreCase = true)) {
            mediaDiagnostic("DECRYPT_OR_COMMIT_REJECTED", conversationId = media.conversationId, mediaId = complete.mediaId)
            return false
        }
        mediaDiagnostic("DELIVERED", conversationId = media.conversationId, mediaId = complete.mediaId)
        mediaDao.updateStatus(complete.mediaId, MediaStatus.DELIVERED.name, 1.0f)
        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.COMPLETED.name)
        // Peer confirmed and verified file: safely delete temp encrypted file!
        mediaStorage.cleanupTempTransfer(complete.mediaId)
        return true
    }

    suspend fun handleIncomingCompletion(connection: Connection, envelope: SecureEnvelope, complete: MediaCompletePayload): Boolean {
        if (isExpiredFrame(complete.mediaId, connection, envelope)) return true
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(complete.mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId || transfer.direction != TransferDirection.UPLOAD.name ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId)
        ) return false
        val media = mediaDao.getById(complete.mediaId) ?: return false
        if (!media.encryptedSha256.equals(complete.verifiedSha256, ignoreCase = true)) return false
        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.COMPLETED.name)
        val message = messageDao.getById(media.messageId)
        if (message != null && groupMessageDeliveryDao != null && message.conversationId == transfer.conversationId) {
            groupMessageDeliveryDao.markDelivered(
                message.logicalMessageId,
                envelope.senderIdentity,
                System.currentTimeMillis()
            )
        }
        val allTransfers = mediaTransferDao.getAllByMediaId(complete.mediaId)
        refreshMediaTransferStatus(complete.mediaId)
        if (allTransfers.all {
                it.status in setOf(TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name)
            }) {
            mediaStorage.cleanupTempTransfer(complete.mediaId)
        }
        return true
    }

    private suspend fun expectedConversationId(
        envelope: SecureEnvelope,
        relationshipId: String,
        persistedConversationId: String
    ): String = envelope.groupMetadata?.groupId
        ?: envelope.conversationId.takeIf { it == persistedConversationId }
        ?: resolveLocalConversationId(relationshipId)
        ?: envelope.conversationId

    private suspend fun isExpiredFrame(mediaId: String, connection: Connection, envelope: SecureEnvelope): Boolean {
        val security = securityPolicyService ?: return false
        val saved = security.dao.expiredMedia(mediaId, connection.relationshipId)
        val transfer = if (saved == null) mediaTransferDao.getByMediaIdAndRelationship(mediaId, connection.relationshipId) else null
        val conversation = saved?.conversationId ?: transfer?.conversationId ?: return false
        if (expectedConversationId(envelope, connection.relationshipId, conversation) != conversation) return false
        return security.expiredMedia(mediaId, connection.relationshipId, conversation)
    }

    private suspend fun uploadHasExpired(mediaId: String): Boolean {
        val media = mediaDao.getById(mediaId) ?: return true
        val message = messageDao.getById(media.messageId) ?: return true
        return message.deletedAt != null || com.torxone.app.privacy.DisappearingPolicy.expired(message.expiresAt, System.currentTimeMillis())
    }

    // ═══════════════════════════════════════════════════════════════
    //  Resumability & Missing Chunk Recovery
    // ═══════════════════════════════════════════════════════════════

    suspend fun getMissingChunkIndices(mediaId: String): List<Int> {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        require(transfers.size <= 1) { "Select a recipient to inspect group attachment progress" }
        val transfer = transfers.singleOrNull() ?: return emptyList()
        return getMissingChunkIndices(transfer)
    }

    private fun getMissingChunkIndices(transfer: MediaTransferEntity): List<Int> {
        val receivedIndices = if (transfer.chunkBitmask.isEmpty()) {
            emptySet()
        } else {
            transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        }
        return (0 until transfer.totalChunks).filter { !receivedIndices.contains(it) }
    }

    /** Receiver-owned reconciliation: only authenticated FILE_RESUME can replay missing chunks.
     * A bounded quiet transfer releases its job; route/network/manual recovery starts a new round.
     */
    private fun startDownloadRecovery(transferId: String, immediate: Boolean = false) {
        val job = recoveryJobs.compute(transferId) { _, existing ->
            if (existing != null && !existing.isCompleted) existing else scope.launch(start = CoroutineStart.LAZY) {
                try {
                    var rounds = 0
                    var previousProgress = -1
                    var pendingResumeId: String? = null
                    var first = true
                    while (rounds < recoveryRounds) {
                        if (!first || !immediate) delay(recoveryIdleMs)
                        val firstPass = first
                        first = false
                        val transfer = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                        if (transfer.direction != TransferDirection.DOWNLOAD.name ||
                            transfer.status !in setOf(TransferStatus.ACTIVE.name, TransferStatus.QUEUED.name) ||
                            uploadHasExpired(transfer.mediaId)) return@launch
                        val media = mediaDao.getById(transfer.mediaId) ?: return@launch
                        if (transfer.completedChunks != previousProgress) {
                            rounds = 0
                            previousProgress = transfer.completedChunks
                        }
                        if (!(firstPass && immediate) && System.currentTimeMillis() - transfer.updatedAt < recoveryIdleMs) continue
                        rounds++
                        try {
                            val missing = getMissingChunkIndices(transfer)
                            if (missing.isEmpty()) {
                                val recipient = messageDao.getById(media.messageId)?.senderId ?: return@launch
                                if (media.status == MediaStatus.COMPLETE.name && media.localPath?.let { File(it).isFile } == true) {
                                    // Local file survived a crash before FILE_COMPLETE was queued.
                                    sendCompleteConfirmation(transfer.relationshipId, media, recipient, transfer.transferId)
                                } else finalizeIncomingMedia(transfer.mediaId, media, transfer, recipient)
                            } else if (pendingResumeId?.let { outboxDao?.getByDeliveryId(it) } == null) {
                                pendingResumeId = requestResume(transfer)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            // Pairing/connection state can change while offline. Keep exact chunks.
                            com.torxone.app.transport.DeliveryDiagnostics.event("media_recovery_failed", transfer.relationshipId,
                                transfer.conversationId, transfer.mediaId, state = "RECOVERY_RETRY", attempt = rounds)
                        }
                    }
                    val waiting = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                    if (waiting.status in setOf(TransferStatus.ACTIVE.name, TransferStatus.QUEUED.name)) {
                        mediaTransferDao.updateStatus(transferId, TransferStatus.QUEUED.name)
                        if (mediaDao.getById(waiting.mediaId)?.status != MediaStatus.COMPLETE.name)
                            mediaDao.updateStatus(waiting.mediaId, MediaStatus.QUEUED.name,
                                waiting.completedChunks.toFloat() / waiting.totalChunks.coerceAtLeast(1))
                        com.torxone.app.transport.DeliveryDiagnostics.event("media_waiting_for_peer", waiting.relationshipId,
                            waiting.conversationId, waiting.mediaId, state = "RECEIVER_RECONCILIATION_BUDGET", attempt = rounds)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    com.torxone.app.transport.DeliveryDiagnostics.event("media_recovery_failed",
                        delivery = transferId, state = "RECONCILIATION_READ_REJECTED")
                } finally { recoveryJobs.remove(transferId, currentCoroutineContext().job) }
            }
        }
        job?.start()
    }

    /** Restore only this peer's work after a verified route or network recovery. No socket is held here. */
    suspend fun recoverDeliveryPath(relationshipId: String? = null) {
        for (transfer in mediaTransferDao.getPendingTransfers()) {
            if (relationshipId != null && transfer.relationshipId != relationshipId) continue
            if (transfer.status == TransferStatus.PAUSED.name || uploadHasExpired(transfer.mediaId)) continue
            try { recoverTransfer(transfer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mediaDiagnostic("RECOVERY_RETRY", transfer.relationshipId, transfer.conversationId, transfer.mediaId)
            }
        }
    }

    private suspend fun recoverTransfer(transfer: MediaTransferEntity) {
        if (transfer.direction == TransferDirection.DOWNLOAD.name) {
            if (dedicatedMediaTransport != null) {
                startDownloadRecovery(transfer.transferId, immediate = true)
                return
            }
            val missing = getMissingChunkIndices(transfer)
            if (missing.isNotEmpty()) requestResume(transfer)
            else {
                val media = mediaDao.getById(transfer.mediaId) ?: return
                val recipient = messageDao.getById(media.messageId)?.senderId ?: return
                if (media.status == MediaStatus.COMPLETE.name && media.localPath?.let { File(it).isFile } == true)
                    sendCompleteConfirmation(transfer.relationshipId, media, recipient, transfer.transferId)
                else finalizeIncomingMedia(transfer.mediaId, media, transfer, recipient)
            }
        } else if (activeTransfers[transfer.transferId]?.isActive != true) {
            if (!File(transfer.tempEncryptedPath).isFile) failUploadUnlessTerminal(transfer.transferId, transfer.mediaId)
            else if (transfer.completedChunks < transfer.totalChunks)
                resumeUpload(transfer, getMissingChunkIndices(transfer))
        }
    }

    suspend fun resumeUpload(mediaId: String, missingChunkIndices: List<Int>) {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        require(transfers.size <= 1) { "Group attachment resume requires an authenticated recipient" }
        val transfer = transfers.singleOrNull() ?: return
        resumeUpload(transfer, missingChunkIndices)
    }

    private suspend fun resumeUpload(transfer: MediaTransferEntity, missingChunkIndices: List<Int>) {
        require(missingChunkIndices.all { it in 0 until transfer.totalChunks }) { "Invalid attachment chunk request" }
        val tempFile = File(transfer.tempEncryptedPath)
        require(tempFile.isFile) { "Attachment transfer data is unavailable. Send the attachment again." }
        requireNotNull(connectionManager.getConnectionByRelationship(transfer.relationshipId)) {
            "Secure peer connection unavailable. Reopen this chat and try again."
        }
        val metadata = transferGroupMetadata(transfer)
        if (metadata != null && groupMessageDeliveryDao != null) {
            val media = requireNotNull(mediaDao.getById(transfer.mediaId)) { "Attachment is no longer available" }
            val delivery = groupMessageDeliveryDao.getDeliveriesForMessage(media.messageId)
                .firstOrNull { it.relationshipId == transfer.relationshipId }
            require(delivery?.outboxDeliveryId != null) {
                "Attachment was not sent to some group members. Send the attachment again."
            }
        }

        val localSenderId = localIdentityIdProvider?.invoke() ?: ""
        startChunkUpload(
            mediaId = transfer.mediaId,
            conversationId = transfer.conversationId,
            localIdentityId = localSenderId,
            recipientId = requireNotNull(contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId
                ?.takeIf { it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN }) {
                "Peer identity unavailable. Reconnect this contact."
            },
            relationshipId = transfer.relationshipId,
            tempEncryptedFile = tempFile,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            totalBytes = transfer.totalBytes,
            chunkIndicesToUpload = missingChunkIndices,
            transferId = transfer.transferId,
            groupMetadata = metadata
        )
    }

    suspend fun requestResume(mediaId: String) {
        mediaTransferDao.getAllByMediaId(mediaId)
            .filter { it.direction == TransferDirection.DOWNLOAD.name }
            .forEach { requestResume(it) }
    }

    private suspend fun requestResume(transfer: MediaTransferEntity): String? {
        val mediaId = transfer.mediaId
        val missing = getMissingChunkIndices(transfer)
        if (missing.isEmpty()) return null

        val connection = connectionManager.getConnectionByRelationship(transfer.relationshipId) ?: return null
        val resumePayload = MediaResumeRequest(mediaId = mediaId, missingChunkIndices = missing)
        val resumeBytes = MediaProtocolCodec.encodeResumeRequest(resumePayload)
        val metadata = transferGroupMetadata(transfer)
        val senderId = localIdentityIdProvider?.invoke() ?: ""
        val recipient = contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId
            ?.takeIf { it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN }
            ?: mediaDao.getById(mediaId)?.let { messageDao.getById(it.messageId)?.senderId }
            ?: return null
        val deliveryId = UUID.randomUUID().toString()
        sendCoordinator.sendSequenced(
            relationshipId = transfer.relationshipId,
            connection = connection,
            buildEnvelope = { sequence ->
                SecureEnvelope(
                    logicalMessageId = UUID.randomUUID().toString(),
                    conversationId = transfer.conversationId,
                    senderIdentity = senderId,
                    recipientBinding = recipient,
                    messageType = MessageType.FILE_RESUME,
                    timestamp = System.currentTimeMillis(),
                    payload = resumeBytes,
                    groupMetadata = metadata,
                    directionSequence = sequence
                )
            }
        ) { sequence, envelope, ciphertext ->
            val now = System.currentTimeMillis()
            requireNotNull(outboxDao).insert(
                OutboxEntity(
                    deliveryId = deliveryId, logicalMessageId = envelope.logicalMessageId,
                    conversationId = transfer.conversationId, connectionId = connection.connectionId,
                    queueAddress = connection.sendQueueId, ciphertext = ciphertext,
                    queueAuthenticator = connection.sendAuth, status = DeliveryStatus.QUEUED.name,
                    priority = DeliveryPriority.HIGH, nextAttemptAt = now, createdAt = now, updatedAt = now,
                    expectsAck = true, applicationSequence = sequence, relationshipId = transfer.relationshipId
                )
            )
        }
        mediaDiagnostic("QUEUED", transfer.relationshipId, transfer.conversationId, mediaId)
        return deliveryId
    }

    suspend fun pauseTransfer(mediaId: String) {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
            .filter { it.status !in setOf(TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name) }
        if (transfers.isEmpty()) return
        transactionRunner {
            transfers.forEach { transfer ->
                val latest = mediaTransferDao.getByTransferId(transfer.transferId)
                if (latest?.status !in setOf(null, TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name))
                    mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.PAUSED.name)
            }
            refreshMediaTransferStatus(mediaId)
        }
        transfers.forEach {
            activeTransfers.remove(it.transferId)?.cancel()
            recoveryJobs.remove(it.transferId)?.cancel()
        }
    }

    suspend fun resumeTransfer(mediaId: String) {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        check(transfers.isNotEmpty()) { "This attachment is no longer available" }
        var failed = false
        for (transfer in transfers) {
            if (transfer.status !in setOf(TransferStatus.PAUSED.name, TransferStatus.FAILED.name, TransferStatus.QUEUED.name, TransferStatus.IDLE.name)) continue
            try { resumeTransfer(transfer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failed = true
                transactionRunner {
                    val latest = mediaTransferDao.getByTransferId(transfer.transferId)
                    if (latest?.status !in setOf(null, TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name))
                        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.PAUSED.name)
                    refreshMediaTransferStatus(mediaId)
                }
                mediaDiagnostic("RECOVERY_RETRY", transfer.relationshipId, transfer.conversationId, mediaId)
            }
        }
        if (failed) error("Some attachment transfers could not resume. Reopen the chat or send the attachment again.")
    }

    private suspend fun resumeTransfer(transfer: MediaTransferEntity) {
        val mediaId = transfer.mediaId
        val download = transfer.direction == TransferDirection.DOWNLOAD.name
        val connection = if (download) requireNotNull(connectionManager.getConnectionByRelationship(transfer.relationshipId)) {
            "Secure peer connection unavailable. Reopen this chat and try again."
        } else null
        val recipient = if (download && dedicatedMediaTransport != null) requireNotNull(
            contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId?.takeIf {
                it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN
            }) { "Peer identity unavailable. Reconnect this contact." } else null
        var started = false
        transactionRunner {
            val latest = mediaTransferDao.getByTransferId(transfer.transferId) ?: return@transactionRunner
            if (latest.status in setOf(TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name)) return@transactionRunner
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.ACTIVE.name)
            refreshMediaTransferStatus(mediaId)
            started = true
        }
        if (!started) return
        if (download) {
            if (dedicatedMediaTransport != null) sendAccept(requireNotNull(connection), transfer.conversationId, requireNotNull(recipient), mediaId)
            requestResume(transfer)
            if (dedicatedMediaTransport != null) startDownloadRecovery(transfer.transferId)
        } else {
            resumeUpload(transfer, getMissingChunkIndices(transfer))
        }
    }

    suspend fun handleIncomingResume(resumeReq: MediaResumeRequest): Boolean {
        val transfers = mediaTransferDao.getAllByMediaId(resumeReq.mediaId)
        if (transfers.size != 1) return false
        val transfer = transfers.single()
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return false

        mediaDiagnostic("RECOVERY_RETRY", transfer.relationshipId, transfer.conversationId, resumeReq.mediaId)
        resumeUpload(transfer, resumeReq.missingChunkIndices)
        return true
    }

    suspend fun handleIncomingResume(connection: Connection, envelope: SecureEnvelope, resumeReq: MediaResumeRequest): Boolean {
        if (isExpiredFrame(resumeReq.mediaId, connection, envelope)) return true
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(resumeReq.mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId || transfer.direction != TransferDirection.UPLOAD.name ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId) ||
            resumeReq.missingChunkIndices.any { it !in 0 until transfer.totalChunks }
        ) return false
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return false
        // Authenticated missing-chunk evidence invalidates only this relationship's
        // suspect persistent stream before selective replay.
        onMissingChunks(connection.relationshipId)
        resumeUpload(transfer, resumeReq.missingChunkIndices)
        return true
    }

    suspend fun handleIncomingCancel(connection: Connection, envelope: SecureEnvelope, mediaId: String): Boolean {
        if (isExpiredFrame(mediaId, connection, envelope)) return true
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId)
        ) return false
        cancelTransfers(mediaId, listOf(transfer), notifyPeer = false)
        return true
    }

    suspend fun recoverPendingTransfersOnStartup() {
        try {
            val pendingTransfers = mediaTransferDao.getPendingTransfers()
            val activeMediaIds = mediaTransferDao.getAllActiveMediaIds().toSet()

            // Sweep orphan temp files (files that do not correspond to any active transfer)
            mediaStorage.cleanupOrphanTempTransfers(activeMediaIds)

            for (transfer in pendingTransfers) {
                if (uploadHasExpired(transfer.mediaId)) continue
                if (transfer.status == TransferStatus.PAUSED.name) continue
                try { recoverTransfer(transfer) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mediaDiagnostic("RECOVERY_RETRY", transfer.relationshipId, transfer.conversationId, transfer.mediaId)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mediaDiagnostic("RECONCILIATION_READ_REJECTED")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cancel & Deletion
    // ═══════════════════════════════════════════════════════════════

    suspend fun cancelTransfer(mediaId: String, notifyPeer: Boolean = true) {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        cancelTransfers(mediaId, transfers, notifyPeer)
    }

    private suspend fun cancelTransfers(mediaId: String, transfers: List<MediaTransferEntity>, notifyPeer: Boolean) {
        val cancelled = mutableListOf<MediaTransferEntity>()
        transactionRunner {
            transfers.forEach { transfer ->
                val latest = mediaTransferDao.getByTransferId(transfer.transferId) ?: return@forEach
                if (latest.status !in setOf(TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name)) {
                    mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.CANCELLED.name)
                    cancelled += latest
                }
            }
            refreshMediaTransferStatus(mediaId)
        }
        cancelled.forEach { transfer ->
            activeTransfers.remove(transfer.transferId)?.cancel()
            recoveryJobs.remove(transfer.transferId)?.cancel()
            if (notifyPeer) scope.launch {
                try { sendCancelControl(transfer) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mediaDiagnostic("RECOVERY_RETRY", transfer.relationshipId, transfer.conversationId, mediaId)
                }
            }
        }
        if (mediaTransferDao.getAllByMediaId(mediaId).all {
                it.status in setOf(TransferStatus.COMPLETED.name, TransferStatus.CANCELLED.name)
            }) mediaStorage.cleanupTempTransfer(mediaId)
    }

    private suspend fun sendCancelControl(transfer: MediaTransferEntity) {
        val connection = connectionManager.getConnectionByRelationship(transfer.relationshipId) ?: return
        val media = mediaDao.getById(transfer.mediaId) ?: return
        val recipient = contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId
            ?.takeIf { it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN }
            ?: messageDao.getById(media.messageId)?.senderId
            ?: return
        val sender = localIdentityIdProvider?.invoke() ?: return
        val bytes = MediaProtocolCodec.encodeCancel(MediaCancelPayload(transfer.mediaId))
        val metadata = transferGroupMetadata(transfer)
        sendCoordinator.sendSequenced(
            relationshipId = transfer.relationshipId,
            connection = connection,
            buildEnvelope = { sequence ->
                SecureEnvelope(
                    logicalMessageId = UUID.randomUUID().toString(), conversationId = transfer.conversationId,
                    senderIdentity = sender, recipientBinding = recipient, messageType = MessageType.FILE_CANCEL,
                    timestamp = System.currentTimeMillis(), payload = bytes, directionSequence = sequence,
                    groupMetadata = metadata
                )
            }
        ) { sequence, envelope, ciphertext ->
            val now = System.currentTimeMillis()
            requireNotNull(outboxDao).insert(
                OutboxEntity(
                    deliveryId = UUID.randomUUID().toString(), logicalMessageId = envelope.logicalMessageId,
                    conversationId = transfer.conversationId, connectionId = connection.connectionId,
                    queueAddress = connection.sendQueueId, ciphertext = ciphertext,
                    queueAuthenticator = connection.sendAuth, status = DeliveryStatus.QUEUED.name,
                    priority = DeliveryPriority.HIGH, nextAttemptAt = now, createdAt = now, updatedAt = now,
                    expectsAck = true, applicationSequence = sequence, relationshipId = transfer.relationshipId
                )
            )
        }
    }

    suspend fun deleteMediaForMessage(messageId: String, cleanupLocalFile: Boolean = true) {
        val media = mediaDao.getByMessageId(messageId)
        if (media != null) {
            cancelTransfer(media.mediaId)
            if (cleanupLocalFile) {
                mediaStorage.deleteLocalFile(media.localPath)
            }
            mediaStorage.cleanupTempTransfer(media.mediaId)
            mediaDao.deleteByMediaId(media.mediaId)
            mediaTransferDao.deleteByMediaId(media.mediaId)
        }
    }

    suspend fun deleteMediaForConversation(conversationId: String, cleanupLocalFiles: Boolean = true) {
        val mediaList = mediaDao.getMediaForConversation(conversationId)
        for (media in mediaList) {
            cancelTransfer(media.mediaId)
            if (cleanupLocalFiles) {
                mediaStorage.deleteLocalFile(media.localPath)
            }
            mediaStorage.cleanupTempTransfer(media.mediaId)
        }
        mediaDao.deleteByConversation(conversationId)
        mediaTransferDao.deleteByConversation(conversationId)
    }
}
