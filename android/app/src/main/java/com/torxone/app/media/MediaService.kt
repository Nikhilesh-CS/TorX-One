package com.torxone.app.media

import android.content.Context
import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MediaDao
import com.torxone.app.data.dao.MediaTransferDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.entity.*
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
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
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null
) {
    companion object {
        private const val TAG = "MediaService"
        const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB chunks fit comfortably in framing
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val activeTransfers = ConcurrentHashMap<String, Job>()

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

        val seq = connectionManager.allocateSendSequence(relationshipId)

        val envelope = SecureEnvelope(
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

        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

        var opaqueCiphertext: ByteArray? = null

        // Critical Atomic Send Boundary:
        // Ratchet Advance + MessageEntity + MediaEntity + MediaTransferEntity + OutboxEntity in ONE atomic commit!
        sessionCrypto.encryptAndCommit(relationshipId, envelopeBytes, aad) { encrypted, updatedState ->
            val ciphertext = encrypted.serialize()
            opaqueCiphertext = ciphertext

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
                expectsAck = true
            )

            transactionRunner {
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
        }

        val deliveryItem = DeliveryItem(
            deliveryId = deliveryId,
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connection.connectionId,
            queueAddress = connection.sendQueueId,
            ciphertext = opaqueCiphertext!!,
            queueAuthenticator = connection.sendAuth,
            status = DeliveryStatus.QUEUED,
            priority = DeliveryPriority.NORMAL,
            expectsAck = true
        )
        agent.enqueue(deliveryItem)

        // Start background chunk transfer loop (cooperatively non-blocking)
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
        chunkIndicesToUpload: List<Int>? = null
    ) {
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
                        val chunkEnvelope = SecureEnvelope(
                            logicalMessageId = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            senderIdentity = localIdentityId,
                            recipientBinding = recipientId,
                            messageType = MessageType.FILE_PROGRESS,
                            timestamp = System.currentTimeMillis(),
                            payload = rawChunkBytes,
                            directionSequence = 0L
                        )
                        val chunkEnvBytes = ProtocolCodec.encodeSecureEnvelope(chunkEnvelope)
                        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)
                        val encryptedChunk = sessionCrypto.encrypt(relationshipId, chunkEnvBytes, aad)

                        val chunkItem = DeliveryItem(
                            deliveryId = UUID.randomUUID().toString(),
                            logicalMessageId = chunkEnvelope.logicalMessageId,
                            conversationId = conversationId,
                            connectionId = connection.connectionId,
                            queueAddress = connection.sendQueueId,
                            ciphertext = encryptedChunk.serialize(),
                            queueAuthenticator = connection.sendAuth,
                            status = DeliveryStatus.QUEUED,
                            priority = DeliveryPriority.LOW,
                            expectsAck = false
                        )
                        agent.enqueue(chunkItem)
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
                            transferId = mediaId,
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
                mediaTransferDao.updateStatus(mediaId, TransferStatus.CANCELLED.name)
            } catch (e: Exception) {
                Log.e(TAG, "[UPLOAD FAILED] mediaId=${mediaId.take(8)}", e)
                mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
                mediaTransferDao.updateStatus(mediaId, TransferStatus.FAILED.name)
            } finally {
                activeTransfers.remove(mediaId)
            }
        }
        activeTransfers[mediaId] = job
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
                totalBytes = descriptor.fileSize,
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
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return false

        // Write chunk directly to temp file at index offset
        mediaStorage.writeChunk(
            mediaId = mediaId,
            chunkIndex = chunk.chunkIndex,
            chunkSize = transfer.chunkSize,
            chunkData = chunk.chunkData
        )

        // Parse and update bitmask
        val receivedIndices = if (transfer.chunkBitmask.isEmpty()) {
            mutableSetOf()
        } else {
            transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
        }
        receivedIndices.add(chunk.chunkIndex)

        val completedCount = receivedIndices.size
        val progress = completedCount.toFloat() / transfer.totalChunks

        mediaDao.updateStatus(mediaId, MediaStatus.DOWNLOADING.name, progress)
        mediaTransferDao.updateProgress(
            transferId = transfer.transferId,
            completedChunks = completedCount,
            chunkBitmask = receivedIndices.joinToString(","),
            bytesTransferred = (completedCount * transfer.chunkSize).toLong().coerceAtMost(transfer.totalBytes),
            status = TransferStatus.ACTIVE.name
        )

        // Check if all chunks have arrived!
        if (completedCount >= transfer.totalChunks) {
            finalizeIncomingMedia(mediaId, media, transfer, envelope.senderIdentity)
        }

        return true
    }

    private suspend fun finalizeIncomingMedia(
        mediaId: String,
        media: MediaEntity,
        transfer: MediaTransferEntity,
        senderIdentity: String = ""
    ) {
        val tempFile = File(transfer.tempEncryptedPath)
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
            val envelope = SecureEnvelope(
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = media.conversationId,
                senderIdentity = senderId,
                recipientBinding = recipientBinding,
                messageType = MessageType.FILE_COMPLETE,
                timestamp = System.currentTimeMillis(),
                payload = completeBytes
            )
            val envBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)
            val encrypted = sessionCrypto.encrypt(relationshipId, envBytes, aad)

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = media.conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = encrypted.serialize(),
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = DeliveryPriority.HIGH,
                expectsAck = false
            )
            agent.enqueue(deliveryItem)
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

    // ═══════════════════════════════════════════════════════════════
    //  Resumability & Missing Chunk Recovery
    // ═══════════════════════════════════════════════════════════════

    suspend fun getMissingChunkIndices(mediaId: String): List<Int> {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return emptyList()
        val receivedIndices = if (transfer.chunkBitmask.isEmpty()) {
            emptySet()
        } else {
            transfer.chunkBitmask.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        }
        return (0 until transfer.totalChunks).filter { !receivedIndices.contains(it) }
    }

    suspend fun resumeUpload(mediaId: String, missingChunkIndices: List<Int>) {
        val transfer = mediaTransferDao.getByMediaId(mediaId) ?: return
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return

        val localSenderId = localIdentityIdProvider?.invoke() ?: ""
        startChunkUpload(
            mediaId = mediaId,
            conversationId = transfer.conversationId,
            localIdentityId = localSenderId,
            recipientId = "",
            relationshipId = transfer.relationshipId,
            tempEncryptedFile = tempFile,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            totalBytes = transfer.totalBytes,
            chunkIndicesToUpload = missingChunkIndices
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
        val envelope = SecureEnvelope(
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = transfer.conversationId,
            senderIdentity = senderId,
            recipientBinding = "",
            messageType = MessageType.FILE_RESUME,
            timestamp = System.currentTimeMillis(),
            payload = resumeBytes
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)
        val encrypted = sessionCrypto.encrypt(transfer.relationshipId, envelopeBytes, aad)

        agent.enqueue(
            DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = transfer.conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = encrypted.serialize(),
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = DeliveryPriority.HIGH
            )
        )
        Log.i(TAG, "[TX FILE_RESUME] requested ${missing.size} missing chunks for mediaId=${mediaId.take(8)}")
    }

    suspend fun handleIncomingResume(resumeReq: MediaResumeRequest): Boolean {
        val transfer = mediaTransferDao.getByMediaId(resumeReq.mediaId) ?: return false
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) return false

        Log.i(TAG, "[RESUME REQUEST] Re-uploading ${resumeReq.missingChunkIndices.size} chunks for ${resumeReq.mediaId.take(8)}")
        resumeUpload(resumeReq.mediaId, resumeReq.missingChunkIndices)
        return true
    }

    suspend fun recoverPendingTransfersOnStartup() {
        try {
            val pendingTransfers = mediaTransferDao.getPendingTransfers()
            val activeMediaIds = pendingTransfers.map { it.mediaId }.toSet()

            // Sweep orphan temp files (files that do not correspond to any active transfer)
            mediaStorage.cleanupOrphanTempTransfers(activeMediaIds)

            for (transfer in pendingTransfers) {
                if (transfer.direction == TransferDirection.DOWNLOAD.name) {
                    val missing = getMissingChunkIndices(transfer.mediaId)
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
                            resumeUpload(transfer.mediaId, remaining)
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

    suspend fun cancelTransfer(mediaId: String) {
        activeTransfers[mediaId]?.cancel()
        activeTransfers.remove(mediaId)
        mediaDao.updateStatus(mediaId, MediaStatus.CANCELLED.name, 0f)
        mediaTransferDao.updateStatus(mediaId, TransferStatus.CANCELLED.name)
        mediaStorage.cleanupTempTransfer(mediaId)
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
