package com.astramesh.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.MediaTransferEntity
import com.astramesh.app.data.MessageEntity
import com.astramesh.app.data.TransferStatus
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.Transport
import com.astramesh.app.realtime.AstraFastLane
import com.astramesh.app.realtime.RealtimeSendResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.Base64
import org.json.JSONObject
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import kotlin.math.ceil

/**
 * Handles chunking, encryption, checksum generation, and persistence of media transfers.
 */
class MediaTransferManager(
    private val context: Context,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter,
    private val fastLane: AstraFastLane? = null
) {
    companion object {
        private const val TAG = "MediaTransferManager"
        // Payloads are JSON -> base64 -> encrypted -> hex encoded, then wrapped in JSON again.
        // Keep plaintext chunks well below MeshProtocol.MAX_FRAME_BYTES after expansion.
        const val CHUNK_SIZE_BT_TOR = 12 * 1024
        const val CHUNK_SIZE_WIFI = 12 * 1024
    }

    private val _ackFlow = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 100)
    val ackFlow: SharedFlow<Pair<String, Int>> = _ackFlow

    private val receivedChunksMap = ConcurrentHashMap<String, MutableSet<Int>>()
    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun queueMediaTransfer(
        contactKey: String,
        fileUri: Uri,
        mimeType: String,
        messageType: String,
        forceWifiDirect: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val identity = messageRouter.identity ?: return@withContext null
        val contact = db.contactDao().getContact(contactKey) ?: return@withContext null

        val messageId = UUID.randomUUID().toString()
        
        // 1. Copy file to sandboxed storage
        val localFile = copyToSandbox(fileUri, messageType, messageId) ?: return@withContext null
        val displayName = getDisplayName(fileUri) ?: localFile.name
        val fileSize = localFile.length()
        
        // 2. Generate Checksum
        val checksum = calculateSha256(localFile)
        
        // 3. Determine Chunk Size
        val chunkSize = if (forceWifiDirect) CHUNK_SIZE_WIFI else CHUNK_SIZE_BT_TOR
        val totalChunks = kotlin.math.max(1, ceil(fileSize.toDouble() / chunkSize).toInt())

        // 4. Save to Database
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = messageId,
                contactKey = contactKey,
                text = "Media Message ($messageType)",
                timestamp = System.currentTimeMillis(),
                direction = "sent",
                status = "pending",
                messageType = messageType,
                fileName = displayName,
                fileSize = fileSize,
                mimeType = mimeType,
                localUri = localFile.absolutePath,
                checksum = checksum,
                transferStatus = TransferStatus.PREPARING.name
            )
        )

        db.mediaTransferDao().insertTransfer(
            MediaTransferEntity(
                messageId = messageId,
                contactKey = contactKey,
                direction = "sent",
                totalChunks = totalChunks,
                completedChunks = 0,
                status = TransferStatus.PREPARING.name,
                lastUpdatedAt = System.currentTimeMillis()
            )
        )

        startTransferJob(messageId, forceWifiDirect)

        return@withContext messageId
    }

    private fun startTransferJob(messageId: String, forceWifiDirect: Boolean) {
        transferScope.launch {
            runCatching {
                sendQueuedTransfer(messageId, forceWifiDirect)
            }.onFailure { e ->
                Log.e(TAG, "Transfer job crashed for $messageId", e)
                db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                db.messageDao().updateMessageStatus(messageId, "pending")
            }
        }
    }

    private fun copyToSandbox(uri: Uri, messageType: String, messageId: String): File? {
        return try {
            val typeDir = when (messageType) {
                "IMAGE" -> "images"
                "VIDEO" -> "videos"
                "AUDIO" -> "audio"
                "VOICE" -> "voice_notes"
                "DOCUMENT" -> "documents"
                "APK" -> "apks"
                else -> "misc"
            }
            
            val sandboxDir = File(context.getExternalFilesDir("media"), typeDir)
            if (!sandboxDir.exists()) sandboxDir.mkdirs()

            if (messageType == "IMAGE") {
                val destFile = File(sandboxDir, "${messageId}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input) ?: return null
                    val maxDim = 1920
                    val width = bitmap.width
                    val height = bitmap.height
                    
                    val scaledBitmap = if (width > maxDim || height > maxDim) {
                        val ratio = kotlin.math.min(maxDim.toFloat() / width, maxDim.toFloat() / height)
                        val newWidth = (width * ratio).toInt()
                        val newHeight = (height * ratio).toInt()
                        android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    } else {
                        bitmap
                    }
                    
                    FileOutputStream(destFile).use { output ->
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
                    }
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                    bitmap.recycle()
                }
                return destFile
            }

            // Guess extension from mime type or URI. Simplification: just use messageId.
            val extension = context.contentResolver.getType(uri)?.split("/")?.lastOrNull() ?: "bin"
            val destFile = File(sandboxDir, "${messageId}.${extension}")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file to sandbox", e)
            null
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun sendQueuedTransfer(messageId: String, forceWifiDirect: Boolean) = withContext(Dispatchers.IO) {
        val message = db.messageDao().getMessageById(messageId) ?: return@withContext
        val transfer = db.mediaTransferDao().getTransferSync(messageId) ?: return@withContext
        val contact = db.contactDao().getContact(message.contactKey) ?: return@withContext
        val localPath = message.localUri ?: return@withContext
        val file = File(localPath)
        if (!file.exists()) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
            db.messageDao().updateMessageStatus(messageId, "failed")
            return@withContext
        }

        val transport = messageRouter.getBestTransport(contact)
        if (transport == Transport.FAILED) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
            return@withContext
        }

        db.mediaTransferDao().updateStatus(messageId, TransferStatus.SENDING.name, System.currentTimeMillis())
        db.mediaTransferDao().updateTransport(messageId, transport.name, System.currentTimeMillis())
        db.messageDao().updateTransferProgress(messageId, 0)

        val fastLaneResult = tryFastLaneTransfer(contact, file)
        if (fastLaneResult is RealtimeSendResult.Sent) {
            db.mediaTransferDao().updateProgress(messageId, 1, TransferStatus.COMPLETED.name, System.currentTimeMillis())
            db.messageDao().updateTransferProgress(messageId, 100)
            db.messageDao().updateMessageStatus(messageId, "sent", "FAST_LANE_${fastLaneResult.engineType.name}")
            Log.i(TAG, "Fast Lane media transfer completed via ${fastLaneResult.engineType}")
            return@withContext
        }

        val finalChunkSize = if (forceWifiDirect) CHUNK_SIZE_WIFI else CHUNK_SIZE_BT_TOR
        val windowSize = if (transport == Transport.TOR) 3 else 5
        val totalChunks = kotlin.math.max(1, ceil(file.length().toDouble() / finalChunkSize).toInt())
        if (transfer.totalChunks != totalChunks) {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE media_transfers SET totalChunks = ? WHERE messageId = ?",
                arrayOf(totalChunks, messageId)
            )
        }

        Log.i(TAG, "Transfer Started transport=${transport.name} file=${file.length()} chunk=$finalChunkSize total=$totalChunks")

        val metadataPayload = JSONObject().apply {
            put("msgId", messageId)
            put("mimeType", message.mimeType)
            put("messageType", message.messageType)
            put("fileName", message.fileName)
            put("checksum", message.checksum)
            put("fileSize", message.fileSize)
            put("totalChunks", totalChunks)
        }.toString()

        var metadataAckReceived = false
        repeat(4) { attempt ->
            val result = messageRouter.sendRawPayload(message.contactKey, metadataPayload, com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_OFFER)
            if (!result.success) {
                Log.w(TAG, "Media offer send failed attempt=${attempt + 1}: ${result.error}")
                kotlinx.coroutines.delay(1000)
                return@repeat
            }
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    ackFlow.first { it.first == messageId && it.second == -1 }
                }
                metadataAckReceived = true
                return@repeat
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Timeout waiting for media offer ACK attempt=${attempt + 1}")
            }
        }
        if (!metadataAckReceived) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
            return@withContext
        }

        RandomAccessFile(file, "r").use { raf ->
            var chunkIndex = transfer.completedChunks.coerceAtMost(totalChunks)
            while (chunkIndex < totalChunks) {
                val batchSize = minOf(windowSize, totalChunks - chunkIndex)
                val unackedChunks = mutableSetOf<Int>().apply {
                    repeat(batchSize) { add(chunkIndex + it) }
                }
                var attempts = 0
                while (unackedChunks.isNotEmpty() && attempts < 5) {
                    attempts++
                    val sends = mutableListOf<Deferred<Boolean>>()
                    unackedChunks.toList().forEach { currentIndex ->
                        sends.add(async(Dispatchers.Default) {
                            val offset = currentIndex.toLong() * finalChunkSize
                            val buffer = ByteArray(finalChunkSize)
                            val actualChunk = synchronized(raf) {
                                raf.seek(offset)
                                val bytesRead = raf.read(buffer)
                                if (bytesRead == finalChunkSize) buffer else buffer.copyOf(maxOf(0, bytesRead))
                            }
                            val payload = JSONObject().apply {
                                put("msgId", messageId)
                                put("chunkIndex", currentIndex)
                                put("offset", offset)
                                put("data", Base64.getEncoder().encodeToString(actualChunk))
                            }.toString()
                            messageRouter.sendRawPayload(message.contactKey, payload, com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_CHUNK).success
                        })
                    }

                    if (sends.map { it.await() }.any { !it }) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }

                    try {
                        kotlinx.coroutines.withTimeout(20_000) {
                            ackFlow.takeWhile { unackedChunks.isNotEmpty() }.collect { ack ->
                                if (ack.first == messageId) unackedChunks.remove(ack.second)
                            }
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Timeout waiting for media chunk ACKs: $unackedChunks")
                    }
                }

                if (unackedChunks.isNotEmpty()) {
                    db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                    return@withContext
                }

                chunkIndex += batchSize
                val progressPercent = ((chunkIndex.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                db.mediaTransferDao().updateProgress(messageId, chunkIndex, TransferStatus.SENDING.name, System.currentTimeMillis())
                db.messageDao().updateTransferProgress(messageId, progressPercent)
            }
        }

        var completeAckReceived = false
        repeat(5) {
            try {
                kotlinx.coroutines.withTimeout(20_000) {
                    ackFlow.first { it.first == messageId && it.second == -2 }
                }
                completeAckReceived = true
                return@repeat
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "Timeout waiting for media COMPLETE ACK")
            }
        }

        if (completeAckReceived) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.COMPLETED.name, System.currentTimeMillis())
            db.messageDao().updateMessageStatus(messageId, "sent")
            Log.i(TAG, "Transfer Completed messageId=$messageId")
        } else {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
        }
    }

    private suspend fun tryFastLaneTransfer(contact: com.astramesh.app.data.ContactEntity, file: File): RealtimeSendResult {
        val lane = fastLane ?: return RealtimeSendResult.Unavailable("Fast Lane not configured.")
        if (!lane.canAttempt(contact)) {
            return RealtimeSendResult.Unavailable("No realtime DataChannel is available.")
        }
        if (file.length() > Int.MAX_VALUE) {
            return RealtimeSendResult.Unavailable("File too large for current DataChannel staging buffer.")
        }
        return runCatching {
            lane.trySendMedia(contact, file.readBytes())
        }.getOrElse { e ->
            Log.w(TAG, "Fast Lane unavailable; falling back to encrypted chunks", e)
            RealtimeSendResult.Failed(e.message ?: "Fast Lane failed")
        }
    }

    fun handleMediaPacket(packetType: String, jsonString: String, senderKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject(jsonString)
                when (packetType) {
                    com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_OFFER -> handleMediaOffer(json, senderKey)
                    com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_CHUNK -> handleMediaChunk(json, senderKey)
                    com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_ACK -> handleMediaAck(json)
                    com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_COMPLETE -> handleMediaComplete(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle media packet: $packetType", e)
            }
        }
    }

    private suspend fun handleMediaOffer(json: JSONObject, senderKey: String) {
        val messageId = json.getString("msgId")
        val mimeType = json.getString("mimeType")
        val messageType = json.getString("messageType")
        val fileName = json.getString("fileName")
        val checksum = json.getString("checksum")
        val fileSize = json.getLong("fileSize")
        val totalChunks = json.getInt("totalChunks")

        var msg = db.messageDao().getMessageById(messageId)
        if (msg == null) {
            db.messageDao().insertMessage(
                MessageEntity(
                    messageId = messageId,
                    contactKey = senderKey,
                    text = "Receiving $messageType...",
                    timestamp = System.currentTimeMillis(),
                    direction = "received",
                    status = "receiving",
                    messageType = messageType,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    checksum = checksum,
                    transferStatus = TransferStatus.RECEIVING.name,
                    transferProgress = 0
                )
            )
            db.mediaTransferDao().insertTransfer(
                MediaTransferEntity(
                    messageId = messageId,
                    contactKey = senderKey,
                    direction = "received",
                    totalChunks = totalChunks,
                    completedChunks = 0,
                    status = TransferStatus.RECEIVING.name,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )
        }
        
        // Initialize in-memory set
        receivedChunksMap[messageId] = Collections.synchronizedSet(mutableSetOf<Int>())
        
        // Send ACK for offer (chunkIndex = -1)
        sendMediaAck(messageId, -1, senderKey)
    }

    private fun sendMediaAck(messageId: String, chunkIndex: Int, senderKey: String) {
        val payload = JSONObject().apply {
            put("msgId", messageId)
            put("chunkIndex", chunkIndex)
        }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                messageRouter.sendRawPayload(senderKey, payload, com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_ACK)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send media ACK", e)
            }
        }
    }

    private suspend fun handleMediaChunk(json: JSONObject, senderKey: String) {
        val messageId = json.getString("msgId")
        val chunkIndex = json.getInt("chunkIndex")
        val offset = json.getLong("offset")
        val base64Data = json.getString("data")
        val data = Base64.getDecoder().decode(base64Data)

        val msg = db.messageDao().getMessageById(messageId)
        if (msg == null) {
            Log.e(TAG, "Missing metadata for chunk $chunkIndex of msgId: $messageId. Dropping chunk.")
            return
        }

        val transfer = db.mediaTransferDao().getTransferSync(messageId) ?: return
        if (transfer.status == TransferStatus.COMPLETED.name) {
            sendMediaAck(messageId, chunkIndex, senderKey)
            return
        }

        val tempDir = File(context.cacheDir, "incoming_media")
        if (!tempDir.exists()) tempDir.mkdirs()
        val tempFile = File(tempDir, "$messageId.part")

        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(data)
        }

        val receivedSet = receivedChunksMap.getOrPut(messageId) { 
            val set = Collections.synchronizedSet(mutableSetOf<Int>())
            for (i in 0 until transfer.completedChunks) set.add(i)
            set
        }

        if (!receivedSet.add(chunkIndex)) {
            // Duplicate chunk, just send ACK
            sendMediaAck(messageId, chunkIndex, senderKey)
            return
        }

        var newCompleted = 0
        synchronized(receivedSet) {
            while (receivedSet.contains(newCompleted)) {
                newCompleted++
            }
        }
        if (newCompleted <= transfer.totalChunks) {
            db.mediaTransferDao().updateProgress(messageId, newCompleted, TransferStatus.RECEIVING.name, System.currentTimeMillis())
            
            val progressPercent = ((newCompleted.toFloat() / transfer.totalChunks) * 100).toInt()
            if (newCompleted % 5 == 0 || newCompleted == transfer.totalChunks) {
                db.messageDao().updateTransferProgress(messageId, progressPercent)
            }

            if (newCompleted == transfer.totalChunks) {
                verifyAndCompleteTransfer(messageId, msg, tempFile, senderKey)
                receivedChunksMap.remove(messageId)
            }
        }
        sendMediaAck(messageId, chunkIndex, senderKey)
    }

    private suspend fun verifyAndCompleteTransfer(messageId: String, msg: MessageEntity, tempFile: File, senderKey: String) {
        val finalChecksum = calculateSha256(tempFile)
        if (msg.checksum == null || finalChecksum == msg.checksum) {
            val typeDir = when (msg.messageType) {
                "IMAGE" -> "images"
                "VIDEO" -> "videos"
                "AUDIO" -> "audio"
                "VOICE" -> "voice_notes"
                "DOCUMENT" -> "documents"
                "APK" -> "apks"
                else -> "misc"
            }
            val sandboxDir = File(context.getExternalFilesDir("media"), typeDir)
            if (!sandboxDir.exists()) sandboxDir.mkdirs()
            val destFile = File(sandboxDir, msg.fileName ?: "$messageId.bin")
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()

            db.mediaTransferDao().updateStatus(messageId, TransferStatus.COMPLETED.name, System.currentTimeMillis())
            db.messageDao().markMediaDelivered(messageId, "delivered", destFile.absolutePath)
            
            // Send COMPLETE ACK back
            val payload = JSONObject().apply { put("msgId", messageId) }.toString()
            messageRouter.sendRawPayload(senderKey, payload, com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_COMPLETE)
            Log.d(TAG, "Media transfer completed and verified: $messageId")
        } else {
            Log.e(TAG, "Checksum mismatch for msgId: $messageId. Expected: ${msg.checksum}, Got: $finalChecksum")
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
            db.messageDao().updateMessageStatus(messageId, "failed")
            tempFile.delete()
        }
    }

    private suspend fun handleMediaAck(json: JSONObject) {
        val messageId = json.getString("msgId")
        val chunkIndex = json.getInt("chunkIndex")
        _ackFlow.emit(Pair(messageId, chunkIndex))
    }

    private suspend fun handleMediaComplete(json: JSONObject) {
        val messageId = json.getString("msgId")
        _ackFlow.emit(Pair(messageId, -2)) // -2 signifies COMPLETE
    }
}
