package com.torxone.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Search index maintenance lives at the database boundary, including edits and media arrivals. */
object FeatureSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS peer_capabilities (relationshipId TEXT NOT NULL PRIMARY KEY, features TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_drafts (conversationId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, replyToMessageId TEXT, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(conversationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS starred_messages (messageId TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, starredAt INTEGER NOT NULL, FOREIGN KEY(messageId) REFERENCES messages(logical_message_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_starred_messages_conversationId ON starred_messages(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS contact_aliases (contactId TEXT NOT NULL PRIMARY KEY, alias TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_appearance (conversationId TEXT NOT NULL PRIMARY KEY, theme TEXT NOT NULL, wallpaper TEXT, bubbleStyle TEXT NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(conversationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `message_search` USING FTS4(`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `body` TEXT NOT NULL, `fileName` TEXT NOT NULL, notindexed=`messageId`, notindexed=`conversationId`)")
        installSearchTriggers(db)
        db.execSQL("INSERT INTO message_search(messageId, conversationId, body, fileName) SELECT m.logical_message_id, m.conversation_id, COALESCE(m.body, ''), COALESCE((SELECT file_name FROM media WHERE message_id = m.logical_message_id), '') FROM messages m WHERE m.deleted_at IS NULL")
    }

    fun installSearchTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_insert AFTER INSERT ON messages BEGIN INSERT INTO message_search(messageId, conversationId, body, fileName) SELECT NEW.logical_message_id, NEW.conversation_id, COALESCE(NEW.body, ''), '' WHERE NEW.deleted_at IS NULL; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_delete AFTER DELETE ON messages BEGIN DELETE FROM message_search WHERE messageId = OLD.logical_message_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_update AFTER UPDATE OF body, deleted_at ON messages BEGIN DELETE FROM message_search WHERE messageId = OLD.logical_message_id; INSERT INTO message_search(messageId, conversationId, body, fileName) SELECT NEW.logical_message_id, NEW.conversation_id, COALESCE(NEW.body, ''), COALESCE((SELECT file_name FROM media WHERE message_id = NEW.logical_message_id), '') WHERE NEW.deleted_at IS NULL; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_media_insert AFTER INSERT ON media BEGIN UPDATE message_search SET fileName = NEW.file_name WHERE messageId = NEW.message_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_media_update AFTER UPDATE OF file_name ON media BEGIN UPDATE message_search SET fileName = NEW.file_name WHERE messageId = NEW.message_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_media_delete AFTER DELETE ON media BEGIN UPDATE message_search SET fileName = '' WHERE messageId = OLD.message_id; END")
    }
}
