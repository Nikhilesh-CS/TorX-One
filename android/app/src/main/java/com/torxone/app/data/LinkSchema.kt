package com.torxone.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Queue changes in the same transaction as messages. Parsing resumes after restart. */
object LinkSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_links (messageId TEXT NOT NULL, conversationId TEXT NOT NULL, url TEXT NOT NULL, host TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(messageId, url), FOREIGN KEY(messageId) REFERENCES messages(logical_message_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_message_links_conversationId ON message_links(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_link_index (messageId TEXT NOT NULL PRIMARY KEY, FOREIGN KEY(messageId) REFERENCES messages(logical_message_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        installTriggers(db)
        db.execSQL("INSERT OR IGNORE INTO pending_link_index(messageId) SELECT logical_message_id FROM messages WHERE deleted_at IS NULL AND (instr(lower(COALESCE(body,'')), 'https://') > 0 OR instr(lower(COALESCE(body,'')), 'http://') > 0)")
    }
    fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_links_insert AFTER INSERT ON messages BEGIN INSERT OR IGNORE INTO pending_link_index(messageId) SELECT NEW.logical_message_id WHERE NEW.deleted_at IS NULL AND (instr(lower(COALESCE(NEW.body,'')), 'https://') > 0 OR instr(lower(COALESCE(NEW.body,'')), 'http://') > 0); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tx_links_update AFTER UPDATE OF body, deleted_at, conversation_id, created_at ON messages BEGIN DELETE FROM message_links WHERE messageId = NEW.logical_message_id; DELETE FROM pending_link_index WHERE messageId = NEW.logical_message_id; INSERT OR IGNORE INTO pending_link_index(messageId) SELECT NEW.logical_message_id WHERE NEW.deleted_at IS NULL AND (instr(lower(COALESCE(NEW.body,'')), 'https://') > 0 OR instr(lower(COALESCE(NEW.body,'')), 'http://') > 0); END")
    }
}
