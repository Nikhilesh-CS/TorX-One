package com.astramesh.app.transfer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.TransferStatus
import com.astramesh.app.network.MessageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

class MediaTransferWorker(
    context: Context,
    params: WorkerParameters,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_USE_WIFI_DIRECT = "use_wifi_direct"
        private const val TAG = "MediaTransferWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.failure()
        val useWifiDirect = inputData.getBoolean(KEY_USE_WIFI_DIRECT, false)

        val message = db.messageDao().getMessageById(messageId) ?: return@withContext Result.failure()
        val transfer = db.mediaTransferDao().getTransferSync(messageId) ?: return@withContext Result.failure()

        if (message.localUri == null) return@withContext Result.failure()
        val file = File(message.localUri)
        if (!file.exists()) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
            return@withContext Result.failure()
        }

        db.mediaTransferDao().updateStatus(messageId, TransferStatus.SENDING.name, System.currentTimeMillis())

        val chunkSize = if (useWifiDirect) MediaTransferManager.CHUNK_SIZE_WIFI else MediaTransferManager.CHUNK_SIZE_BT_TOR
        
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(chunkSize)
                
                // Seek to the last completed chunk
                val startChunkIndex = transfer.completedChunks
                raf.seek((startChunkIndex * chunkSize).toLong())

                for (chunkIndex in startChunkIndex until transfer.totalChunks) {
                    val bytesRead = raf.read(buffer)
                    if (bytesRead == -1) break

                    val actualChunk = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                    val base64Chunk = Base64.getEncoder().encodeToString(actualChunk)

                    // Wrap in JSON
                    val chunkPayload = JSONObject().apply {
                        put("msgId", messageId)
                        put("chunkIndex", chunkIndex)
                        put("totalChunks", transfer.totalChunks)
                        put("data", base64Chunk)
                        if (chunkIndex == 0) {
                            put("mimeType", message.mimeType)
                            put("messageType", message.messageType)
                            put("fileName", message.fileName)
                            put("checksum", message.checksum)
                            put("fileSize", message.fileSize)
                        }
                    }.toString()

                    val result = messageRouter.sendRawPayload(message.contactKey, chunkPayload, com.astramesh.app.network.MeshProtocol.TYPE_MEDIA_CHUNK)
                    if (!result.success) {
                        db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                        return@withContext Result.retry()
                    }

                    // Update DB Progress
                    db.mediaTransferDao().updateProgress(messageId, chunkIndex + 1, TransferStatus.SENDING.name, System.currentTimeMillis())
                    
                    // Update UI Progress in messages table
                    val progressPercent = ((chunkIndex + 1).toFloat() / transfer.totalChunks * 100).toInt()
                    db.openHelper.writableDatabase.execSQL("UPDATE messages SET transferProgress = ? WHERE messageId = ?", arrayOf(progressPercent, messageId))
                }
            }

            db.mediaTransferDao().updateStatus(messageId, TransferStatus.COMPLETED.name, System.currentTimeMillis())
            db.messageDao().updateMessageStatus(messageId, "sent")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
            return@withContext Result.failure()
        }
    }
}
