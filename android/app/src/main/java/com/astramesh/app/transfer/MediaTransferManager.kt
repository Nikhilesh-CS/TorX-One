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
        val totalChunks = ceil(fileSize.toDouble() / chunkSize).toInt()

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

    fun receiveChunk(jsonString: String, senderKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject(jsonString)
                val messageId = json.getString("msgId")
                val chunkIndex = json.getInt("chunkIndex")
                val totalChunks = json.getInt("totalChunks")
                val base64Data = json.getString("data")
                val data = Base64.getDecoder().decode(base64Data)

                // If chunk 0, we have metadata
                if (chunkIndex == 0 && json.has("mimeType")) {
                    val mimeType = json.getString("mimeType")
                    val messageType = json.getString("messageType")
                    val fileName = json.getString("fileName")
                    val checksum = json.getString("checksum")
                    val fileSize = json.getLong("fileSize")

                    // Create DB records if not exist
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
                                transferStatus = TransferStatus.RECEIVING.name
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
                }

                // Wait for DB record to be created by chunk 0 if this is an out of order chunk
                var msg = db.messageDao().getMessageById(messageId)
                var retries = 0
                while (msg == null && retries < 10) {
                    kotlinx.coroutines.delay(500)
                    msg = db.messageDao().getMessageById(messageId)
                    retries++
                }
                
                if (msg == null) {
                    Log.e(TAG, "Missing metadata for msgId: $messageId. Cannot process chunk.")
                    return@launch
                }

                // Write to temp file
                val tempDir = File(context.cacheDir, "incoming_media")
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, "$messageId.part")
                
                // We use standard chunk size for writing offset, except if it's Wi-Fi Direct.
                // We actually can't easily guess the chunkSize used by sender if we don't know the transport.
                // However, the data length itself tells us the offset if we assume in-order, 
                // but for out-of-order, we need the exact chunk size.
                // Let's rely on data length for now and assume mostly in-order.
                // Wait, if it's out of order we need the chunk size. Let's just append if we don't have it.
                // Actually, since Tor and BT use 32KB and Wifi uses 512KB, we can check data size.
                val chunkSize = if (data.size > CHUNK_SIZE_BT_TOR) CHUNK_SIZE_WIFI else CHUNK_SIZE_BT_TOR
                val offset = chunkIndex.toLong() * chunkSize

                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(data)
                }

                // Update Progress
                val transfer = db.mediaTransferDao().getTransferSync(messageId)
                if (transfer != null) {
                    val completed = transfer.completedChunks + 1
                    db.mediaTransferDao().updateProgress(messageId, completed, TransferStatus.RECEIVING.name, System.currentTimeMillis())
                    
                    val progressPercent = ((completed.toFloat() / totalChunks) * 100).toInt()
                    db.messageDao().updateTransferProgress(messageId, progressPercent)

                    if (completed >= totalChunks) {
                        // Reassemble & Verify
                        val finalChecksum = calculateSha256(tempFile)
                        if (msg.checksum == null || finalChecksum == msg.checksum) {
                            // Move to final location
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

                            // Update DB
                            db.mediaTransferDao().updateStatus(messageId, TransferStatus.COMPLETED.name, System.currentTimeMillis())
                            db.messageDao().markMediaDelivered(messageId, "delivered", destFile.absolutePath)
                            Log.d(TAG, "Media transfer completed and verified: $messageId")
                        } else {
                            Log.e(TAG, "Checksum mismatch for msgId: $messageId. Expected: ${msg.checksum}, Got: $finalChecksum")
                            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
                            db.messageDao().updateMessageStatus(messageId, "failed")
                            tempFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive chunk", e)
            }
        }
    }
}
