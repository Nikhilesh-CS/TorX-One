package com.torxone.app.chat

import androidx.room.withTransaction
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.MessageLinkEntity
import java.net.IDN
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

object MessageLinks {
    private val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    fun extract(body: String): List<Pair<String, String>> = urls.findAll(body.take(64 * 1024)).take(64).mapNotNull {
        val url = it.value.trimEnd('.', ',', ')', ']', '}', ';', '!', '?')
        if (url.length > 2048) return@mapNotNull null
        runCatching {
            val parsed = URI(url)
            require(parsed.scheme.lowercase() in setOf("http", "https"))
            // Userinfo is deliberately excluded: it can disguise the actual destination.
            require(parsed.rawUserInfo == null)
            val host = requireNotNull(parsed.host).lowercase()
            require(host.isNotBlank())
            url to IDN.toASCII(host).lowercase()
        }.getOrNull()
    }.distinct().toList()
}

class MessageLinkIndex(private val db: TorXDatabase) {
    suspend fun drainBatch(): Int {
        val rows = db.featureDao().pendingLinkMessages()
        rows.forEach { message ->
            // Parse outside the writer transaction; each message commits separately
            // so incoming message/ratchet transactions can run between items.
            val links = MessageLinks.extract(message.body.orEmpty())
            db.withTransaction {
                val current = db.messageDao().getById(message.logicalMessageId) ?: return@withTransaction
                if (current.body != message.body || current.conversationId != message.conversationId ||
                    current.createdAt != message.createdAt || current.deletedAt != message.deletedAt) return@withTransaction
                db.featureDao().deleteLinks(message.logicalMessageId)
                if (message.deletedAt == null) links.forEach { (url, host) ->
                    db.featureDao().saveLink(MessageLinkEntity(message.logicalMessageId, message.conversationId, url, host, message.createdAt))
                }
                db.featureDao().finishLinkIndex(message.logicalMessageId)
            }
        }
        return rows.size
    }
    fun start(scope: CoroutineScope) = scope.launch {
        // Triggers use the underlying SQLite connection. Observe messages as well as
        // the queue so Room invalidates this collector for incoming and edited rows.
        db.invalidationTracker.createFlow("messages", "pending_link_index").collect {
            while (true) {
                try { if (drainBatch() == 0) break; yield() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("MessageLinkIndex", "Local link indexing will retry")
                    delay(5_000)
                }
            }
        }
    }
}
