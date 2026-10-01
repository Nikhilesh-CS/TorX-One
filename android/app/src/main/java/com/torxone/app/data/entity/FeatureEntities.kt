package com.torxone.app.data.entity

import androidx.room.*

@Entity(tableName = "peer_capabilities")
data class PeerCapabilitiesEntity(@PrimaryKey val relationshipId: String, val features: String, val updatedAt: Long)

@Entity(tableName = "conversation_drafts", foreignKeys = [ForeignKey(entity = ConversationEntity::class,
    parentColumns = ["conversationId"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class ConversationDraftEntity(@PrimaryKey val conversationId: String, val text: String,
    val replyToMessageId: String? = null, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "starred_messages", indices = [Index("conversationId")], foreignKeys = [
    ForeignKey(entity = MessageEntity::class, parentColumns = ["logical_message_id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class StarredMessageEntity(@PrimaryKey val messageId: String, val conversationId: String, val starredAt: Long)

@Entity(tableName = "contact_aliases")
data class ContactAliasEntity(@PrimaryKey val contactId: String, val alias: String)

@Entity(tableName = "conversation_appearance", foreignKeys = [ForeignKey(entity = ConversationEntity::class,
    parentColumns = ["conversationId"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class ConversationAppearanceEntity(@PrimaryKey val conversationId: String, val theme: String = "SYSTEM",
    val wallpaper: String? = null, val bubbleStyle: String = "ROUNDED")

@Fts4(notIndexed = ["messageId", "conversationId"])
@Entity(tableName = "message_search")
data class MessageFtsEntity(@PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val messageId: String, val conversationId: String, val body: String, val fileName: String)

@Entity(tableName = "message_links", primaryKeys = ["messageId", "url"], indices = [Index("conversationId")],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["logical_message_id"],
        childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class MessageLinkEntity(val messageId: String, val conversationId: String, val url: String,
    val host: String, val createdAt: Long)

@Entity(tableName = "pending_link_index", foreignKeys = [ForeignKey(entity = MessageEntity::class,
    parentColumns = ["logical_message_id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class PendingLinkIndexEntity(@PrimaryKey val messageId: String)
