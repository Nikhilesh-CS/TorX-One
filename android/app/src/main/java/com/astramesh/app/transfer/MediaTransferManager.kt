package com.astramesh.app.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.MediaTransferEntity
import com.astramesh.app.data.MessageEntity
import com.astramesh.app.data.TransferStatus
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.Transport
import kotlinx.coroutines.Dispatchers
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
    private val messageRouter: MessageRouter
) {
    companion object {
        private const val TAG = "MediaTransferManager"
        const val CHUNK_SIZE_BT_TOR = 32 * 1024 // 32 KB
        const val CHUNK_SIZE_WIFI = 512 * 1024 // 512 KB
    }

    private val _ackFlow = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 100)
    val ackFlow: SharedFlow<Pair<String, Int>> = _ackFlow

    private val receivedChunksMap = ConcurrentHashMap<String, MutableSet<Int>>()

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
                fileName = localFile.name,
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

        // 5. Enqueue WorkManager Job
        startTransferWorker(messageId, forceWifiDirect)

        return@withContext messageId
    }

    private fun startTransferWorker(messageId: String, forceWifiDirect: Boolean) {
        val workRequest = OneTimeWorkRequestBuilder<MediaTransferWorker>()
            .setInputData(workDataOf(
                MediaTransferWorker.KEY_MESSAGE_ID to messageId,
                MediaTransferWorker.KEY_USE_WIFI_DIRECT to forceWifiDirect
            ))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
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
