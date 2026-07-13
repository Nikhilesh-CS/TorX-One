package com.torxone.app.debug

import com.torxone.app.data.AppDatabase
import com.torxone.app.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class StressTestEngine(
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {
    suspend fun injectMassivePayload(contactKey: String) {
        val totalMessages = 20000
        val chunkSize = 500
        val imageCount = 1000
        val fileCount = 500

        var currentImage = 0
        var currentFile = 0

        for (i in 0 until totalMessages step chunkSize) {
            val chunk = mutableListOf<MessageEntity>()
            for (j in 0 until chunkSize) {
                val index = i + j
                if (index >= totalMessages) break

                val isReceived = index % 2 == 0
                val type = when {
                    currentImage < imageCount && index % 20 == 0 -> {
                        currentImage++
                        "IMAGE"
                    }
                    currentFile < fileCount && index % 40 == 0 -> {
                        currentFile++
                        "DOCUMENT"
                    }
                    else -> "TEXT"
                }

                val content = when (type) {
                    "IMAGE" -> "content://mock/image_$index.jpg"
                    "DOCUMENT" -> "content://mock/doc_$index.pdf"
                    else -> if (index % 5 == 0) "Hello world \uD83D\uDE00" else "This is a much longer message designed to test multiline wrapping and layout constraints within the chat bubble UI. It should properly wrap without breaking the bubble or timestamp alignment."
                }

                chunk.add(
                    MessageEntity(
                        messageId = UUID.randomUUID().toString(),
                        contactKey = contactKey,
                        text = content,
                        timestamp = System.currentTimeMillis() - (totalMessages - index) * 1000L,
                        direction = if (isReceived) "received" else "sent",
                        status = "delivered",
                        messageType = type,
                        transport = "TOR"
                    )
                )
            }
            database.messageDao().insertAll(chunk)
            delay(50) // Prevent OOM/locking
        }
    }
}
