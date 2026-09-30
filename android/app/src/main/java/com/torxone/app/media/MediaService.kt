package com.torxone.app.media

import android.content.Context
import android.util.Log
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
import com.torxone.app.data.entity.*
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.GroupEnvelopeMetadata
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportType
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
    private val groupMessageDeliveryDao: GroupMessageDeliveryDao? = null
) {
    companion object {
        private const val TAG = "MediaService"
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB chunks fit comfortably in framing
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
        replyToMessageId: String? = null
    ): String {
        require(rawBytes.size.toLong() in 1..MediaProtocolCodec.MAX_MEDIA_BYTES) { "Media file exceeds supported size" }
        require(fileName.isNotBlank() && fileName.length <= 255 && '/' !in fileName && '\\' !in fileName)
        val mediaId = UUID.randomUUID().toString()
        Log.i(TAG, "[SEND_MEDIA] mediaId=${mediaId.take(8)} type=$type size=${rawBytes.size}")

        // 1. Save local plaintext copy for instant local viewing
        val localFile = mediaStorage.saveIncomingFile(mediaId, fileName, rawBytes)
        val tempEncryptedFile = mediaStorage.getTempEncryptedFile(mediaId)

        // 2. Stream-encrypt directly to temp file
        val mediaKey = MediaCrypto.generateMediaKey()
        val encryptedSha256 = tempEncryptedFile.outputStream().use { outStream ->
            ByteArrayInputStream(rawBytes).use { inStream ->
                MediaCrypto.encryptStream(mediaKey, inStream, outStream)
            }
        }
        val encryptedFileSize = tempEncryptedFile.length()

        return sendMediaInternal(
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
            replyToMessageId = replyToMessageId
        )
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
        val mediaId = UUID.randomUUID().toString()
        Log.i(TAG, "[SEND_MEDIA_FILE] mediaId=${mediaId.take(8)} type=$type file=${file.name} size=${file.length()}")

        val tempEncryptedFile = mediaStorage.getTempEncryptedFile(mediaId)

        // Stream-encrypt from file directly into temp encrypted file
        val mediaKey = MediaCrypto.generateMediaKey()
        val encryptedSha256 = tempEncryptedFile.outputStream().use { outStream ->
            file.inputStream().use { inStream ->
                MediaCrypto.encryptStream(mediaKey, inStream, outStream)
            }
        }
        val encryptedFileSize = tempEncryptedFile.length()

        return sendMediaInternal(
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
            localFilePath = file.absolutePath,
            tempEncryptedFile = tempEncryptedFile,
            durationMs = durationMs,
            thumbnailBytes = thumbnailBytes,
            waveformData = waveformData,
            replyToMessageId = replyToMessageId
        )
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
        replyToMessageId: String? = null
    ): String {
        require(rawBytes.size.toLong() in 1..MediaProtocolCodec.MAX_MEDIA_BYTES) { "Media file exceeds supported size" }
        require(fileName.isNotBlank() && fileName.length <= 255 && '/' !in fileName && '\\' !in fileName)
        require(recipients.distinctBy { it.identityId }.size == recipients.size) { "Duplicate group media recipient" }
        recipients.forEach {
            require(it.identityId.isNotBlank() && it.relationshipId.isNotBlank()) { "Invalid group media recipient" }
            require(it.identityId != ContactEntity.REMOTE_IDENTITY_UNKNOWN) { "Recipient identity is not authenticated" }
        }

        val mediaId = UUID.randomUUID().toString()
        val localFile = mediaStorage.saveIncomingFile(mediaId, fileName, rawBytes)
        val encryptedFile = mediaStorage.getTempEncryptedFile(mediaId)
        val mediaKey = MediaCrypto.generateMediaKey()
        val encryptedSha256 = encryptedFile.outputStream().use { output ->
            ByteArrayInputStream(rawBytes).use { input -> MediaCrypto.encryptStream(mediaKey, input, output) }
        }
        return sendGroupMediaInternal(
            mediaId, groupId, groupEpoch, localIdentityId, recipients, type, fileName, mimeType,
            rawBytes.size.toLong(), encryptedFile.length(), encryptedSha256, mediaKey,
            localFile.absolutePath, encryptedFile, durationMs, thumbnailBytes, waveformData,
            replyToMessageId
        )
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
        replyToMessageId: String?
    ): String {
        val deliveryDao = requireNotNull(groupMessageDeliveryDao) {
            "Group media requires GroupMessageDeliveryDao"
        }
        val messageId = UUID.randomUUID().toString()
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
            replyToMessageId = replyToMessageId
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
                Log.e(TAG, "Group media fan-out pending for ${recipient.identityId}", error)
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
        replyToMessageId: String? = null
    ): String {
        if (recipientId.isBlank() || recipientId == com.torxone.app.data.entity.ContactEntity.REMOTE_IDENTITY_UNKNOWN) {
            throw IllegalStateException("Security information for this contact needs to be refreshed. Reconnect or re-add this contact.")
        }

        val messageId = UUID.randomUUID().toString()
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
                    replyToMessageId = replyToMessageId
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
        val activeKey = transferId
        val indices = chunkIndicesToUpload ?: (0 until totalChunks).toList()

        val job = scope.launch {
            try {
                mediaDao.updateStatus(mediaId, MediaStatus.UPLOADING.name, 0f)
                val bitmaskSet = mutableSetOf<Int>()

                for ((count, chunkIndex) in indices.withIndex()) {
                    if (!isActive) break

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
                    val connection = connectionManager.getConnectionByRelationship(relationshipId)
                    if (connection != null) {
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
                        }
                    }

                    bitmaskSet.add(chunkIndex)
                    val progress = (count + 1).toFloat() / indices.size
                    val current = mediaDao.getById(mediaId)
                    if (current?.status != MediaStatus.DELIVERED.name &&
                        current?.status != MediaStatus.COMPLETE.name &&
                        current?.status != MediaStatus.CANCELLED.name &&
                        current?.status != MediaStatus.FAILED.name
                    ) {
                        mediaDao.updateStatus(mediaId, MediaStatus.UPLOADING.name, progress)
                        mediaTransferDao.updateProgress(
                            transferId = transferId,
                            completedChunks = bitmaskSet.size,
                            chunkBitmask = bitmaskSet.joinToString(","),
                            bytesTransferred = (bitmaskSet.size * chunkSize).toLong().coerceAtMost(totalBytes),
                            status = TransferStatus.ACTIVE.name
                        )
                    }

                    // Cooperative yield: ensures text messages, reactions, typing, and ACKs NEVER starve
                    delay(5)
                }

                if (isActive) {
                    // All chunks have been enqueued locally.
                    // Transfer remains in ACTIVE state until peer receiver confirms complete file via FILE_COMPLETE!
                    val current = mediaDao.getById(mediaId)
                    if (current?.status != MediaStatus.DELIVERED.name &&
                        current?.status != MediaStatus.COMPLETE.name &&
                        current?.status != MediaStatus.CANCELLED.name &&
                        current?.status != MediaStatus.FAILED.name
                    ) {
                        mediaDao.updateStatus(mediaId, MediaStatus.SENT.name, 1.0f)
                        Log.i(TAG, "[ALL CHUNKS ENQUEUED] mediaId=${mediaId.take(8)} awaiting receiver confirmation")
                    }
                }
            } catch (e: CancellationException) {
                mediaDao.updateStatus(mediaId, MediaStatus.CANCELLED.name, 0f)
                mediaTransferDao.updateStatus(transferId, TransferStatus.CANCELLED.name)
            } catch (e: Exception) {
                Log.e(TAG, "[UPLOAD FAILED] mediaId=${mediaId.take(8)}", e)
                mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
                mediaTransferDao.updateStatus(transferId, TransferStatus.FAILED.name)
            } finally {
                activeTransfers.remove(activeKey)
            }
        }
        activeTransfers[activeKey] = job
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
        activeTransfers[transferId]?.cancel()
        val job = scope.launch {
            try {
                val media = mediaDao.getById(mediaId) ?: error("Missing media record")
                val connection = connectionManager.getConnectionByRelationship(relationshipId)
                    ?: error("No active connection for media transfer")
                val destination = TransportDestination(connection.sendQueueId, relationshipId = connection.relationshipId)
                val transfer = mediaTransferDao.getByTransferId(transferId) ?: error("Missing transfer record")
                val alreadySent = transfer.chunkBitmask.split(',').mapNotNull(String::toIntOrNull).toMutableSet()
                val indices = requestedIndices ?: (0 until totalChunks).filterNot(alreadySent::contains)
                mediaTransferDao.updateStatus(transferId, TransferStatus.ACTIVE.name)
                mediaDao.updateStatus(mediaId, MediaStatus.UPLOADING.name, alreadySent.size.toFloat() / totalChunks)

                for (chunkIndex in indices) {
                    ensureActive()
                    val current = mediaTransferDao.getByTransferId(transferId) ?: break
                    if (current.status == TransferStatus.PAUSED.name || current.status == TransferStatus.CANCELLED.name) break
                    val plaintextChunk = mediaStorage.readChunk(encryptedFile, chunkIndex, chunkSize, totalBytes)
                    val encryptedChunk = DedicatedMediaChunkCrypto.encrypt(
                        media.mediaKey, mediaId, relationshipId, chunkIndex, totalChunks, plaintextChunk
                    )
                    var retryAttempt = 0
                    while (true) {
                        ensureActive()
                        val pending = mediaTransferDao.getByTransferId(transferId) ?: return@launch
                        if (pending.status in setOf(TransferStatus.PAUSED.name, TransferStatus.CANCELLED.name, TransferStatus.COMPLETED.name)) return@launch
                        val result = dedicatedMediaTransport!!.send(
                            destination,
                            DedicatedMediaFrame(mediaId, chunkIndex, totalChunks, encryptedChunk)
                        )
                        if (result is TransportResult.Accepted) {
                            mediaTransferDao.updateStatus(transferId, TransferStatus.ACTIVE.name)
                            break
                        }
                        // Preserve the file and chunk bitmap while offline. Startup recovery
                        // resumes QUEUED transfers; a network error is not a terminal failure.
                        mediaTransferDao.updateStatus(transferId, TransferStatus.QUEUED.name)
                        mediaDao.updateStatus(mediaId, MediaStatus.QUEUED.name, alreadySent.size.toFloat() / totalChunks)
                        delay(minOf(60_000L, 1_000L shl minOf(retryAttempt++, 6)))
                    }
                    alreadySent.add(chunkIndex)
                    val sentBytes = alreadySent.sumOf { index ->
                        minOf(chunkSize.toLong(), totalBytes - index.toLong() * chunkSize)
                    }
                    mediaTransferDao.updateProgress(
                        transferId,
                        alreadySent.size,
                        alreadySent.sorted().joinToString(","),
                        sentBytes,
                        TransferStatus.ACTIVE.name
                    )
                    mediaDao.updateStatus(mediaId, MediaStatus.UPLOADING.name, alreadySent.size.toFloat() / totalChunks)
                    yield()
                }
                if (alreadySent.size == totalChunks) {
                    mediaDao.updateStatus(mediaId, MediaStatus.SENT.name, 1f)
                }
            } catch (_: CancellationException) {
                // Pause/cancel methods persist the intended terminal state.
            } catch (error: Exception) {
                Log.e(TAG, "Dedicated upload failed for $mediaId", error)
                mediaTransferDao.updateStatus(transferId, TransferStatus.FAILED.name)
                mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            } finally {
                activeTransfers.remove(transferId)
            }
        }
        activeTransfers[transferId] = job
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
            Log.e(TAG, "Failed to decode MediaDescriptor: ${e.message}")
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
                    Log.e(
                        TAG,
                        "[RX_DESCRIPTOR REJECT] No local contact/conversation mapping for relationshipId=${connection.relationshipId}"
                    )
                    return false
                }
                conversationId = contact.conversationId
            } else {
                conversationId = envelope.conversationId
            }
        }
        val now = System.currentTimeMillis()

        Log.i(TAG, "[RX_DESCRIPTOR] mediaId=${mediaId.take(8)} type=${descriptor.type} chunks=${descriptor.totalChunks} conv=${conversationId.take(8)}")

        val mediaKey = try {
            Base64.getDecoder().decode(descriptor.mediaKeyBase64)
        } catch (_: Exception) {
            Log.e(TAG, "Invalid media key in descriptor")
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
                replyToMessageId = envelope.replyToMessageId
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

        if (autoDownload && dedicatedMediaTransport != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                sendAccept(connection, conversationId, envelope.senderIdentity, mediaId)
            }
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
            Log.e(TAG, "Failed to decode MediaChunkPayload: ${e.message}")
            return false
        }

        val mediaId = chunk.mediaId
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
        if (transfer.status != TransferStatus.ACTIVE.name) return false
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

        // Record each chunk exactly once even if transport retries deliver duplicates.
        val chunkWrite = mediaTransferDao.recordChunkIfMissing(
            transferId = transfer.transferId,
            chunkIndex = chunk.chunkIndex,
            chunkBytes = chunk.chunkData.size.toLong()
        )
        if (chunkWrite == 0) return true
        val receivedIndices = transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
        receivedIndices.add(chunk.chunkIndex)
        val completedCount = receivedIndices.size
        val progress = completedCount.toFloat() / transfer.totalChunks

        mediaDao.updateStatus(mediaId, MediaStatus.DOWNLOADING.name, progress)

        // Check if all chunks have arrived!
        if (completedCount >= transfer.totalChunks) {
            finalizeIncomingMedia(mediaId, media, transfer, envelope.senderIdentity)
        }

        return true
    }

    suspend fun handleDedicatedMediaFrame(rawBytes: ByteArray, transportType: TransportType): Boolean =
        handleDedicatedMediaFrame(rawBytes, transportType, null)

    suspend fun handleDedicatedMediaFrame(rawBytes: ByteArray, transportType: TransportType, authenticatedRelationshipId: String?): Boolean {
        val frame = try {
            DedicatedMediaFrameCodec.decode(rawBytes)
        } catch (error: Exception) {
            Log.w(TAG, "Rejected malformed dedicated media frame over $transportType: ${error.message}")
            return false
        }
        val media = mediaDao.getById(frame.mediaId) ?: return false
        val transfer = mediaTransferDao.getByMediaId(frame.mediaId) ?: return false
        if (authenticatedRelationshipId != null && transfer.relationshipId != authenticatedRelationshipId) return false
        if (transfer.direction != TransferDirection.DOWNLOAD.name || frame.totalChunks != transfer.totalChunks ||
            transfer.status != TransferStatus.ACTIVE.name
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
            Log.w(TAG, "Rejected unauthenticated media chunk ${frame.chunkIndex}: ${error.message}")
            return false
        }
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
            Log.e(TAG, "[INTEGRITY FAILURE] Media hash mismatch for $mediaId")
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            mediaStorage.cleanupTempTransfer(mediaId)
            return
        }

        // 2. Stream-decrypt ciphertext directly to incoming destination file
        val safeName = media.fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val destination = File(mediaStorage.incomingDir, "${mediaId}_$safeName")
        try {
            require(media.fileSize in 1..MediaProtocolCodec.MAX_MEDIA_BYTES)
            require(media.mediaKey.size == 32)
            require(transfer.totalChunks in 1..MediaProtocolCodec.MAX_CHUNK_COUNT)
            tempFile.inputStream().use { input ->
                destination.outputStream().use { output ->
                    MediaCrypto.decryptStream(media.mediaKey, input, output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DECRYPTION FAILURE] Failed to decrypt media $mediaId: ${e.message}")
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            mediaStorage.cleanupTempTransfer(mediaId)
            return
        }

        // 3. Mark complete & cleanup receiver temp file
        mediaDao.updateLocalPathAndStatus(mediaId, destination.absolutePath, MediaStatus.COMPLETE.name)
        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.COMPLETED.name)
        mediaStorage.cleanupTempTransfer(mediaId)

        Log.i(TAG, "[DOWNLOAD COMPLETE] mediaId=${mediaId.take(8)} saved to ${destination.name}")

        // 4. Send FILE_COMPLETE confirmation back to sender!
        // Launched asynchronously so that if called within a decrypt commit block,
        // it does not deadlock the relationship SessionActor.
        // UNDISPATCHED: starts immediately on current thread (queuing the encrypt in SessionActor
        // right away) and resumes on scope dispatcher after first suspension. This avoids
        // Dispatchers.IO scheduling delays that cause test flakiness under load.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendCompleteConfirmation(transfer.relationshipId, media, senderIdentity)
        }
    }

    private suspend fun sendCompleteConfirmation(
        relationshipId: String,
        media: MediaEntity,
        recipientBinding: String = ""
    ) {
        val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return
        val completePayload = MediaCompletePayload(
            mediaId = media.mediaId,
            verifiedSha256 = media.encryptedSha256
        )
        val completeBytes = MediaProtocolCodec.encodeComplete(completePayload)
        val senderId = localIdentityIdProvider?.invoke() ?: ""
        try {
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
            }
            Log.i(TAG, "[TX FILE_COMPLETE] sent confirmation for mediaId=${media.mediaId.take(8)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send complete confirmation for ${media.mediaId}: ${e.message}", e)
        }
    }

    suspend fun handleIncomingCompletion(complete: MediaCompletePayload): Boolean {
        val media = mediaDao.getById(complete.mediaId) ?: return false
        if (!media.encryptedSha256.equals(complete.verifiedSha256, ignoreCase = true)) {
            Log.e(TAG, "[COMPLETE VERIFY FAIL] SHA mismatch for ${complete.mediaId}")
            return false
        }
        Log.i(TAG, "[RECEIVER CONFIRMED COMPLETE] mediaId=${complete.mediaId.take(8)}")
        mediaDao.updateStatus(complete.mediaId, MediaStatus.DELIVERED.name, 1.0f)
        mediaTransferDao.updateStatus(complete.mediaId, TransferStatus.COMPLETED.name)
        // Peer confirmed and verified file: safely delete temp encrypted file!
        mediaStorage.cleanupTempTransfer(complete.mediaId)
        return true
    }

    suspend fun handleIncomingCompletion(connection: Connection, envelope: SecureEnvelope, complete: MediaCompletePayload): Boolean {
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
        val allComplete = allTransfers.all {
            it.transferId == transfer.transferId || it.status == TransferStatus.COMPLETED.name
        }
        if (allComplete) {
            mediaDao.updateStatus(complete.mediaId, MediaStatus.DELIVERED.name, 1f)
            mediaStorage.cleanupTempTransfer(complete.mediaId)
        } else {
            val progress = allTransfers.count {
                it.transferId == transfer.transferId || it.status == TransferStatus.COMPLETED.name
            }.toFloat() / allTransfers.size.coerceAtLeast(1)
            mediaDao.updateStatus(complete.mediaId, MediaStatus.SENT.name, progress)
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

    // ═══════════════════════════════════════════════════════════════
    //  Resumability & Missing Chunk Recovery
    // ═══════════════════════════════════════════════════════════════

    suspend fun getMissingChunkIndices(mediaId: String): List<Int> {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return emptyList()
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

    suspend fun resumeUpload(mediaId: String, missingChunkIndices: List<Int>) {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return
        resumeUpload(transfer, missingChunkIndices)
    }

    private suspend fun resumeUpload(transfer: MediaTransferEntity, missingChunkIndices: List<Int>) {
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return

        val localSenderId = localIdentityIdProvider?.invoke() ?: ""
        startChunkUpload(
            mediaId = transfer.mediaId,
            conversationId = transfer.conversationId,
            localIdentityId = localSenderId,
            recipientId = contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId?.takeIf { it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN } ?: return,
            relationshipId = transfer.relationshipId,
            tempEncryptedFile = tempFile,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            totalBytes = transfer.totalBytes,
            chunkIndicesToUpload = missingChunkIndices,
            transferId = transfer.transferId
        )
    }

    suspend fun requestResume(mediaId: String) {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return
        val missing = getMissingChunkIndices(mediaId)
        if (missing.isEmpty()) return

        val connection = connectionManager.getConnectionByRelationship(transfer.relationshipId) ?: return
        val resumePayload = MediaResumeRequest(mediaId = mediaId, missingChunkIndices = missing)
        val resumeBytes = MediaProtocolCodec.encodeResumeRequest(resumePayload)
        val senderId = localIdentityIdProvider?.invoke() ?: ""
        val recipient = contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId
            ?.takeIf { it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN }
            ?: mediaDao.getById(mediaId)?.let { messageDao.getById(it.messageId)?.senderId }
            ?: return
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
                    directionSequence = sequence
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
        Log.i(TAG, "[TX FILE_RESUME] requested ${missing.size} missing chunks for mediaId=${mediaId.take(8)}")
    }

    suspend fun pauseTransfer(mediaId: String) {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return
        if (transfer.status == TransferStatus.COMPLETED.name || transfer.status == TransferStatus.CANCELLED.name) return
        activeTransfers.remove(mediaId)?.cancel()
        mediaTransferDao.updateStatus(mediaId, TransferStatus.PAUSED.name)
        mediaDao.updateStatus(mediaId, MediaStatus.QUEUED.name, transfer.completedChunks.toFloat() / transfer.totalChunks)
    }

    suspend fun resumeTransfer(mediaId: String) {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: error("This attachment is no longer available")
        if (transfer.status != TransferStatus.PAUSED.name && transfer.status != TransferStatus.FAILED.name) return
        val download = transfer.direction == TransferDirection.DOWNLOAD.name
        val connection = if (download) requireNotNull(connectionManager.getConnectionByRelationship(transfer.relationshipId)) {
            "Secure peer connection unavailable. Reopen this chat and try again."
        } else null
        val recipient = if (download && dedicatedMediaTransport != null) requireNotNull(
            contactDao?.getByRelationshipId(transfer.relationshipId)?.remoteIdentityId?.takeIf {
                it.isNotBlank() && it != ContactEntity.REMOTE_IDENTITY_UNKNOWN
            }) { "Peer identity unavailable. Reconnect this contact." } else null
        mediaTransferDao.updateStatus(mediaId, TransferStatus.ACTIVE.name)
        val progress = transfer.completedChunks.toFloat() / transfer.totalChunks.coerceAtLeast(1)
        mediaDao.updateStatus(mediaId, if (download) MediaStatus.DOWNLOADING.name else MediaStatus.UPLOADING.name, progress)
        try {
            if (download) {
                if (dedicatedMediaTransport != null) sendAccept(requireNotNull(connection), transfer.conversationId, requireNotNull(recipient), mediaId)
                requestResume(mediaId)
            } else {
                resumeUpload(mediaId, getMissingChunkIndices(mediaId))
            }
        } catch (error: Exception) {
            mediaTransferDao.updateStatus(mediaId, TransferStatus.PAUSED.name)
            mediaDao.updateStatus(mediaId, MediaStatus.QUEUED.name, progress)
            throw error
        }
    }

    suspend fun handleIncomingResume(resumeReq: MediaResumeRequest): Boolean {
        val transfer = mediaTransferDao.getByMediaId(resumeReq.mediaId) ?: return false
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return false

        Log.i(TAG, "[RESUME REQUEST] Re-uploading ${resumeReq.missingChunkIndices.size} chunks for ${resumeReq.mediaId.take(8)}")
        resumeUpload(resumeReq.mediaId, resumeReq.missingChunkIndices)
        return true
    }

    suspend fun handleIncomingResume(connection: Connection, envelope: SecureEnvelope, resumeReq: MediaResumeRequest): Boolean {
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(resumeReq.mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId || transfer.direction != TransferDirection.UPLOAD.name ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId) ||
            resumeReq.missingChunkIndices.any { it !in 0 until transfer.totalChunks }
        ) return false
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return false
        resumeUpload(transfer, resumeReq.missingChunkIndices)
        return true
    }

    suspend fun handleIncomingCancel(connection: Connection, envelope: SecureEnvelope, mediaId: String): Boolean {
        val transfer = mediaTransferDao.getByMediaIdAndRelationship(mediaId, connection.relationshipId) ?: return false
        if (transfer.relationshipId != connection.relationshipId ||
            transfer.conversationId != expectedConversationId(envelope, connection.relationshipId, transfer.conversationId)
        ) return false
        cancelTransfer(mediaId, notifyPeer = false)
        return true
    }

    suspend fun recoverPendingTransfersOnStartup() {
        try {
            val pendingTransfers = mediaTransferDao.getPendingTransfers()
            val activeMediaIds = pendingTransfers.map { it.mediaId }.toSet()

            // Sweep orphan temp files (files that do not correspond to any active transfer)
            mediaStorage.cleanupOrphanTempTransfers(activeMediaIds)

            for (transfer in pendingTransfers) {
                if (transfer.status == TransferStatus.PAUSED.name) continue
                if (transfer.direction == TransferDirection.DOWNLOAD.name) {
                    val missing = getMissingChunkIndices(transfer)
                    if (missing.isNotEmpty()) {
                        Log.i(TAG, "[STARTUP RECOVERY] Requesting resume for download ${transfer.mediaId.take(8)}, missing ${missing.size} chunks")
                        requestResume(transfer.mediaId)
                    }
                } else if (transfer.direction == TransferDirection.UPLOAD.name) {
                    val tempFile = File(transfer.tempEncryptedPath)
                    if (!tempFile.exists()) {
                        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
                        mediaDao.updateStatus(transfer.mediaId, MediaStatus.FAILED.name, 0f)
                    } else if (transfer.completedChunks < transfer.totalChunks) {
                        val sentIndices = if (transfer.chunkBitmask.isEmpty()) emptySet()
                        else transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                        val remaining = (0 until transfer.totalChunks).filter { !sentIndices.contains(it) }
                        if (remaining.isNotEmpty()) {
                            Log.i(TAG, "[STARTUP RECOVERY] Resuming upload for ${transfer.mediaId.take(8)}, remaining ${remaining.size} chunks")
                            resumeUpload(transfer, remaining)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering pending transfers on startup: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cancel & Deletion
    // ═══════════════════════════════════════════════════════════════

    suspend fun cancelTransfer(mediaId: String, notifyPeer: Boolean = true) {
        val transfers = mediaTransferDao.getAllByMediaId(mediaId)
        if (notifyPeer) {
            transfers.forEach { transfer -> scope.launch { sendCancelControl(transfer) } }
        }
        transfers.forEach { transfer -> activeTransfers.remove(transfer.transferId)?.cancel() }
        mediaDao.updateStatus(mediaId, MediaStatus.CANCELLED.name, 0f)
        transfers.forEach { mediaTransferDao.updateStatus(it.transferId, TransferStatus.CANCELLED.name) }
        mediaStorage.cleanupTempTransfer(mediaId)
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
        sendCoordinator.sendSequenced(
            relationshipId = transfer.relationshipId,
            connection = connection,
            buildEnvelope = { sequence ->
                SecureEnvelope(
                    logicalMessageId = UUID.randomUUID().toString(), conversationId = transfer.conversationId,
                    senderIdentity = sender, recipientBinding = recipient, messageType = MessageType.FILE_CANCEL,
                    timestamp = System.currentTimeMillis(), payload = bytes, directionSequence = sequence
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
