package com.torxone.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object ExportManager {

    suspend fun exportChatsToJSON(context: Context, db: AppDatabase): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val messages = db.messageDao().getAllMessagesSync()
            val jsonArray = JSONArray()

            for (msg in messages) {
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("messageId", msg.messageId)
                    put("contactKey", msg.contactKey)
                    put("text", msg.text)
                    put("timestamp", msg.timestamp)
                    put("direction", msg.direction)
                    put("status", msg.status)
                    put("transport", msg.transport)
                }
                jsonArray.put(obj)
            }

            val file = File(context.cacheDir, "TorXOne_Export_${System.currentTimeMillis()}.json")
            file.writeText(jsonArray.toString(4))

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(intent, "Export Chats")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ExportManager", "Failed to export chats", e)
            Result.failure(e)
        }
    }

    suspend fun importChatsFromJSON(context: Context, uri: android.net.Uri, db: AppDatabase): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Cannot open stream")
            val jsonString = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            
            var importedCount = 0
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val messageId = obj.getString("messageId")
                
                // Avoid duplicating messages if they already exist
                if (db.messageDao().getMessageById(messageId) == null) {
                    val message = MessageEntity(
                        messageId = messageId,
                        contactKey = obj.getString("contactKey"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        direction = obj.getString("direction"),
                        status = obj.optString("status", "pending"),
                        transport = if (obj.has("transport") && !obj.isNull("transport")) obj.getString("transport") else null
                    )
                    db.messageDao().insertMessage(message)
                    importedCount++
                }
            }
            
            Result.success(importedCount)
        } catch (e: Exception) {
            Log.e("ExportManager", "Failed to import chats", e)
            Result.failure(e)
        }
    }
}
