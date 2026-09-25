package com.torxone.app.media

import android.content.Context
import java.util.Base64
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
import com.torxone.app.data.entity.*
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaService — Authority for encrypted media attachments and chunked transfers.
 *
 * Invariants:
 * 1. Large media payloads are NEVER stuffed into SecureEnvelope ratchet frames.
 * 2. Media payload is encrypted with a fresh random 256-bit AES key.
 * 3. The symmetric key is exchanged securely inside the initial Double Ratchet message.
 * 4. Transfer is sliced into manageable chunks (e.g. 16 KB) with SHA-256 integrity check.
 * 5. Media transfers NEVER starve or block normal chat traffic, typing, presence, or ACKs.
 * 6. Transfer is fully resumable from last received chunk without restarting from byte 0.
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
    private val mediaStorage: MediaStorage = MediaStorage(context),
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { it() }
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

    // ═══════════════════════════════════════════════════════════════
    //  Outgoing Media Sending
    // ═══════════════════════════════════════════════════════════════

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
        val messageId = UUID.randomUUID().toString()
        Log.i(TAG, "[SEND_MEDIA] mediaId=${mediaId.take(8)} type=$type size=${rawBytes.size}")

        // 1. Generate 32-byte AES-GCM media key
        val mediaKey = MediaCrypto.generateMediaKey()

        // 2. Encrypt plaintext payload with AES-256-GCM
        val encryptedBytes = MediaCrypto.encrypt(mediaKey, rawBytes)
        val encryptedSha256 = MediaCrypto.sha256Hex(encryptedBytes)

        // 3. Save local outgoing plaintext copy for immediate user viewing
        val localFile = mediaStorage.saveIncomingFile(mediaId, fileName, rawBytes)

        // 4. Save encrypted file in temp storage for chunk slicing
        val tempEncryptedFile = mediaStorage.saveOutgoingEncryptedFile(mediaId, encryptedBytes)

        // 5. Calculate chunking
        val chunkSize = DEFAULT_CHUNK_SIZE
        val totalChunks = if (encryptedBytes.isEmpty()) 1 else (encryptedBytes.size + chunkSize - 1) / chunkSize

        val mediaKeyBase64 = Base64.getEncoder().encodeToString(mediaKey)
        val thumbBase64 = thumbnailBytes?.let { Base64.getEncoder().encodeToString(it) }
        val waveBase64 = waveformData?.let { Base64.getEncoder().encodeToString(it) }

        val descriptor = MediaDescriptor(
            mediaId = mediaId,
            type = type,
            mimeType = mimeType,
            fileName = fileName,
            fileSize = rawBytes.size.toLong(),
            encryptedSha256 = encryptedSha256,
            mediaKeyBase64 = mediaKeyBase64,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            durationMs = durationMs,
            thumbnailBase64 = thumbBase64,
            waveformBase64 = waveBase64
        )

        // 6. Map to Protocol MessageType
        val protoMessageType = when (type) {
            MediaType.IMAGE -> MessageType.IMAGE
            MediaType.VIDEO -> MessageType.VIDEO
            MediaType.AUDIO -> MessageType.AUDIO
            MediaType.DOCUMENT -> MessageType.FILE
            MediaType.VOICE_NOTE -> MessageType.VOICE_NOTE
        }

        val now = System.currentTimeMillis()

        // 7. Atomic DB insertion: Message + Media + Transfer entities
        transactionRunner {
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
            messageDao.insertIfAbsent(messageEntity)

            val mediaEntity = MediaEntity(
                mediaId = mediaId,
                messageId = messageId,
                conversationId = conversationId,
                mediaType = type.name,
                mimeType = mimeType,
                fileName = fileName,
                fileSize = rawBytes.size.toLong(),
                localPath = localFile.absolutePath,
                encryptedSha256 = encryptedSha256,
                mediaKey = mediaKey,
                thumbnailData = thumbnailBytes,
                durationMs = durationMs,
                waveformData = waveformData,
                status = MediaStatus.QUEUED.name,
                transferProgress = 0f,
                createdAt = now
            )
            mediaDao.insert(mediaEntity)

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
                totalBytes = encryptedBytes.size.toLong(),
                updatedAt = now
            )
            mediaTransferDao.upsert(transferEntity)

            // Update conversation preview
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
        }

        // 8. Build initial SecureEnvelope carrying the MediaDescriptor via Double Ratchet
        val descriptorBytes = MediaProtocolCodec.encodeDescriptor(descriptor)
        val connection = connectionManager.getConnectionByRelationship(relationshipId)
            ?: throw IllegalStateException("No active connection for relationship $relationshipId")

        val seq = connectionManager.incrementSendSequence(relationshipId)

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
        val ratchetMsg = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)

        val deliveryItem = DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = connection.connectionId,
            queueAddress = connection.sendQueueId,
            ciphertext = ratchetMsg.serialize(),
            queueAuthenticator = connection.sendAuth,
            status = DeliveryStatus.QUEUED,
            priority = DeliveryPriority.NORMAL,
            expectsAck = true
        )
        agent.enqueue(deliveryItem)

        // 9. Start background chunk transfer loop (cooperatively non-blocking)
        startChunkUpload(
            mediaId = mediaId,
            relationshipId = relationshipId,
            tempEncryptedFile = tempEncryptedFile,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            totalBytes = encryptedBytes.size.toLong()
        )

        return messageId
    }

    private fun startChunkUpload(
        mediaId: String,
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

                    // Dispatch chunk envelope over transport with LOW priority so chat traffic is prioritized
                    val connection = connectionManager.getConnectionByRelationship(relationshipId)
                    if (connection != null) {
                        val chunkEnvelope = SecureEnvelope(
                            logicalMessageId = UUID.randomUUID().toString(),
                            conversationId = "",
                            senderIdentity = "",
                            recipientBinding = "",
                            messageType = MessageType.FILE_PROGRESS,
                            timestamp = System.currentTimeMillis(),
                            payload = rawChunkBytes
                        )
                        val chunkEnvBytes = ProtocolCodec.encodeSecureEnvelope(chunkEnvelope)
                        val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)
                        val encryptedChunk = sessionCrypto.encrypt(relationshipId, chunkEnvBytes, aad)

                        val chunkItem = DeliveryItem(
                            deliveryId = UUID.randomUUID().toString(),
                            logicalMessageId = chunkEnvelope.logicalMessageId,
                            conversationId = "",
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
                    mediaDao.updateStatus(mediaId, MediaStatus.UPLOADING.name, progress)
                    mediaTransferDao.updateProgress(
                        transferId = mediaId,
                        completedChunks = bitmaskSet.size,
                        chunkBitmask = bitmaskSet.joinToString(","),
                        bytesTransferred = (bitmaskSet.size * chunkSize).toLong().coerceAtMost(totalBytes),
                        status = TransferStatus.ACTIVE.name
                    )

                    // Cooperative yield: ensures text messages, reactions, typing, and ACKs NEVER starve
                    delay(5)
                }

                if (isActive) {
                    mediaDao.updateStatus(mediaId, MediaStatus.SENT.name, 1.0f)
                    mediaTransferDao.updateStatus(mediaId, TransferStatus.COMPLETED.name)
                    Log.i(TAG, "[UPLOAD COMPLETE] mediaId=${mediaId.take(8)}")
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
        val conversationId = envelope.conversationId
        val now = System.currentTimeMillis()

        Log.i(TAG, "[RX_DESCRIPTOR] mediaId=${mediaId.take(8)} type=${descriptor.type} chunks=${descriptor.totalChunks}")

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
        // If low bandwidth mode is on, auto-download is disabled for videos and large documents
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
            finalizeIncomingMedia(mediaId, media, transfer)
        }

        return true
    }

    private suspend fun finalizeIncomingMedia(
        mediaId: String,
        media: MediaEntity,
        transfer: MediaTransferEntity
    ) {
        val tempFile = File(transfer.tempEncryptedPath)
        if (!tempFile.exists()) {
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            return
        }

        val encryptedBytes = tempFile.readBytes()

        // 1. Verify SHA-256 integrity
        if (!MediaCrypto.verifyIntegrity(encryptedBytes, media.encryptedSha256)) {
            Log.e(TAG, "[INTEGRITY FAILURE] Media hash mismatch for $mediaId")
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            mediaStorage.cleanupTempTransfer(mediaId)
            return
        }

        // 2. Decrypt with AES-256-GCM symmetric key
        val decryptedBytes = try {
            MediaCrypto.decrypt(media.mediaKey, encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "[DECRYPTION FAILURE] Failed to decrypt media $mediaId: ${e.message}")
            mediaDao.updateStatus(mediaId, MediaStatus.FAILED.name, 0f)
            mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.FAILED.name)
            mediaStorage.cleanupTempTransfer(mediaId)
            return
        }

        // 3. Save plaintext file to app-private storage
        val destination = mediaStorage.saveIncomingFile(mediaId, media.fileName, decryptedBytes)

        // 4. Mark Complete & clean up temp buffer
        mediaDao.updateLocalPathAndStatus(mediaId, destination.absolutePath, MediaStatus.COMPLETE.name)
        mediaTransferDao.updateStatus(transfer.transferId, TransferStatus.COMPLETED.name)
        mediaStorage.cleanupTempTransfer(mediaId)

        Log.i(TAG, "[DOWNLOAD COMPLETE] mediaId=${mediaId.take(8)} saved to ${destination.name}")
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

        startChunkUpload(
            mediaId = mediaId,
            relationshipId = transfer.relationshipId,
            tempEncryptedFile = tempFile,
            totalChunks = transfer.totalChunks,
            chunkSize = transfer.chunkSize,
            totalBytes = transfer.totalBytes,
            chunkIndicesToUpload = missingChunkIndices
        )
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
            mediaDao.deleteByMediaId(media.mediaId)
            mediaTransferDao.deleteByMediaId(media.mediaId)
        }
    }
}
