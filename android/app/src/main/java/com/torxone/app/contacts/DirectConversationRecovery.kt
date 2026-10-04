package com.torxone.app.contacts

import androidx.room.withTransaction
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.ConversationType

/** One local chat per authenticated signing identity, retaining every secure lane. */
suspend fun consolidateDirectConversations(database: TorXDatabase) = database.withTransaction {
    val peers = database.contactDao().getAll().filter { it.signingPublicKey.isNotEmpty() }
        .groupBy { it.signingPublicKey.toList() }
    for (contacts in peers.values) {
        if (contacts.size < 2) continue
        val oldIds = contacts.map { it.conversationId }.distinct()
        val conversations = oldIds.mapNotNull { database.conversationDao().getById(it) }
        if (conversations.isEmpty() || conversations.any { it.type != ConversationType.DIRECT }) continue
        // Stable across the two endpoints, independent of scan order.
        val canonicalId = contacts.minOf { it.relationshipId }
        if (oldIds == listOf(canonicalId)) continue
        val latest = conversations.maxBy { it.lastMessageTime ?: 0L }
        val merged = latest.copy(
            conversationId = canonicalId,
            unreadCount = conversations.sumOf { it.unreadCount },
            isPinned = conversations.any { it.isPinned },
            pinnedAt = conversations.mapNotNull { it.pinnedAt }.minOrNull(),
            mutedUntil = conversations.mapNotNull { it.mutedUntil }.maxOrNull(),
            isArchived = conversations.all { it.isArchived },
            manuallyUnread = conversations.any { it.manuallyUnread },
            createdAt = conversations.minOf { it.createdAt }
        )
        database.conversationDao().upsertPreservingMessages(merged)
        val sql = database.openHelper.writableDatabase
        val placeholders = oldIds.joinToString(",") { "?" }
        val expectedMessages = sql.query("SELECT COUNT(*) FROM messages WHERE conversation_id IN ($placeholders)", oldIds.toTypedArray()).use {
            it.moveToFirst()
            it.getLong(0)
        }
        for (oldId in oldIds.filter { it != canonicalId }) {
            for (table in listOf("messages", "outbox", "reactions", "local_message_state", "media", "media_transfers")) {
                sql.execSQL("UPDATE $table SET conversation_id = ? WHERE conversation_id = ?", arrayOf(canonicalId, oldId))
            }
            sql.execSQL("UPDATE call_history SET conversationId = ? WHERE conversationId = ?", arrayOf(canonicalId, oldId))
        }
        contacts.forEach { database.contactDao().upsert(it.copy(conversationId = canonicalId)) }
        oldIds.filter { it != canonicalId }.forEach { database.conversationDao().deleteById(it) }
        val actualMessages = sql.query("SELECT COUNT(*) FROM messages WHERE conversation_id = ?", arrayOf(canonicalId)).use {
            it.moveToFirst()
            it.getLong(0)
        }
        check(actualMessages == expectedMessages) { "Direct chat consolidation must preserve message history" }
        android.util.Log.i("DirectChatRecovery", "Consolidated chats; preserved messages and secure relationships")
    }
}
