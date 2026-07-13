package com.torxone.app.transfer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.TransferStatus
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile

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
        val contact = db.contactDao().getContact(message.contactKey) ?: return@withContext Result.failure()

        if (message.localUri == null) return@withContext Result.failure()
        val file = File(message.localUri)
        if (!file.exists()) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.FAILED.name, System.currentTimeMillis())
            return@withContext Result.failure()
        }

        val transport = messageRouter.getBestTransport(contact)
        if (transport == com.torxone.app.network.Transport.FAILED) {
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
            return@withContext Result.retry()
        }
        db.mediaTransferDao().updateStatus(messageId, TransferStatus.SENDING.name, System.currentTimeMillis())
        db.mediaTransferDao().updateTransport(messageId, transport.name, System.currentTimeMillis())
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET transferProgress = ? WHERE messageId = ?", arrayOf(0, messageId))

        val chunkSize = when (transport) {
            com.torxone.app.network.Transport.NEARBY_DIRECT -> MediaTransferManager.CHUNK_SIZE_BT_TOR
            com.torxone.app.network.Transport.NEARBY_RELAY -> MediaTransferManager.CHUNK_SIZE_BT_TOR
            com.torxone.app.network.Transport.TOR -> MediaTransferManager.CHUNK_SIZE_BT_TOR
            else -> MediaTransferManager.CHUNK_SIZE_BT_TOR
        }
        
        val finalChunkSize = if (useWifiDirect) MediaTransferManager.CHUNK_SIZE_WIFI else chunkSize
        val windowSize = if (transport == com.torxone.app.network.Transport.TOR) 4 else 8
        
        val fileSize = file.length()
        val totalChunks = kotlin.math.max(1, kotlin.math.ceil(fileSize.toDouble() / finalChunkSize).toInt())

        if (transfer.totalChunks != totalChunks) {
            db.openHelper.writableDatabase.execSQL("UPDATE media_transfers SET totalChunks = ? WHERE messageId = ?", arrayOf(totalChunks, messageId))
        }

        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Transfer Started\nTransport: ${if(useWifiDirect) "Wi-Fi Direct" else transport.name}\nFile Size: $fileSize\nChunk Size: $finalChunkSize\nTotal Chunks: $totalChunks")

        val mediaTransferManager = com.torxone.app.service.TorXOneService.getInstance()?.mediaTransferManager ?: return@withContext Result.retry()

        try {
            // PHASE 1: Send METADATA (OFFER) and wait for ACK
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
            for (attempt in 1..3) {
                messageRouter.sendRawPayload(message.contactKey, metadataPayload, com.torxone.app.network.MeshProtocol.TYPE_MEDIA_OFFER)
                try {
                    kotlinx.coroutines.withTimeout(15_000) {
                        mediaTransferManager.ackFlow.first { it.first == messageId && it.second == -1 }
                    }
                    metadataAckReceived = true
                    break
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG, "Timeout waiting for METADATA ACK, attempt $attempt")
                }
            }
            if (!metadataAckReceived) {
                Log.e(TAG, "Failed to get METADATA ACK")
                db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                return@withContext Result.retry()
            }

            // PHASE 2: Send Chunks with Sliding Window
            RandomAccessFile(file, "r").use { raf ->
                var chunkIndex = transfer.completedChunks
                
                while (chunkIndex < totalChunks) {
                    val batchSize = minOf(windowSize, totalChunks - chunkIndex)
                    val unackedChunks = mutableSetOf<Int>()
                    for (i in 0 until batchSize) unackedChunks.add(chunkIndex + i)

                    var batchAttempts = 0
                    while (unackedChunks.isNotEmpty() && batchAttempts < 5) {
                        batchAttempts++
                        val deferredList = mutableListOf<Deferred<Boolean>>()
                        
                        for (currentChunkIndex in unackedChunks) {
                            deferredList.add(async(Dispatchers.Default) {
                                val buffer = ByteArray(finalChunkSize)
                                var actualChunk: ByteArray
                                val offset = currentChunkIndex.toLong() * finalChunkSize
                                synchronized(raf) {
                                    raf.seek(offset)
                                    val bytesRead = raf.read(buffer)
                                    actualChunk = if (bytesRead == finalChunkSize) buffer else buffer.copyOf(maxOf(0, bytesRead))
                                }
                                
                                val base64Chunk = Base64.getEncoder().encodeToString(actualChunk)
                                
                                val chunkPayload = JSONObject().apply {
                                    put("msgId", messageId)
                                    put("chunkIndex", currentChunkIndex)
                                    put("offset", offset)
                                    put("data", base64Chunk)
                                }.toString()
                                
                                val result = messageRouter.sendRawPayload(message.contactKey, chunkPayload, com.torxone.app.network.MeshProtocol.TYPE_MEDIA_CHUNK)
                                result.success
                            })
                        }
                        
                        val sendResults = deferredList.map { it.await() }
                        if (sendResults.any { !it }) {
                            kotlinx.coroutines.delay(1000)
                            continue
                        }

                        // Wait for ACKs for the current window
                        try {
                            kotlinx.coroutines.withTimeout(20_000) {
                                mediaTransferManager.ackFlow
                                    .takeWhile { unackedChunks.isNotEmpty() }
                                    .collect { ack ->
                                        if (ack.first == messageId) {
                                            unackedChunks.remove(ack.second)
                                        }
                                    }
                            }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            Log.w(TAG, "Timeout waiting for batch ACKs. Unacked: $unackedChunks")
                        }
                    }

                    if (unackedChunks.isNotEmpty()) {
                        Log.e(TAG, "Failed to send batch after 5 attempts. Unacked: $unackedChunks")
                        db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                        return@withContext Result.retry()
                    }
                    
                    chunkIndex += batchSize
                    db.mediaTransferDao().updateProgress(messageId, chunkIndex, TransferStatus.SENDING.name, System.currentTimeMillis())
                    val progressPercent = ((chunkIndex).toFloat() / totalChunks * 100).toInt()
                    db.openHelper.writableDatabase.execSQL("UPDATE messages SET transferProgress = ? WHERE messageId = ?", arrayOf(progressPercent, messageId))
                }
            }

            // PHASE 3: Wait for COMPLETE ACK
            var completeAckReceived = false
            for (attempt in 1..5) {
                try {
                    kotlinx.coroutines.withTimeout(20_000) {
                        mediaTransferManager.ackFlow.first { it.first == messageId && it.second == -2 }
                    }
                    completeAckReceived = true
                    break
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG, "Timeout waiting for COMPLETE ACK, attempt $attempt")
                }
            }

            if (!completeAckReceived) {
                Log.e(TAG, "Failed to receive COMPLETE ACK")
                db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
                return@withContext Result.retry()
            }

            val transferTime = (System.currentTimeMillis() - startTime) / 1000.0
            Log.i(TAG, "Transfer Completed\nTransfer Time: $transferTime seconds")

            db.mediaTransferDao().updateStatus(messageId, TransferStatus.COMPLETED.name, System.currentTimeMillis())
            db.messageDao().updateMessageStatus(messageId, "sent")
            return@withContext Result.success()

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "Transfer cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            db.mediaTransferDao().updateStatus(messageId, TransferStatus.RETRYING.name, System.currentTimeMillis())
            return@withContext Result.retry()
        }
    }
}
