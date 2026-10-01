package com.torxone.app.privacy

import androidx.sqlite.db.SupportSQLiteDatabase

object SecurityPolicySchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_expires_at ON messages(expires_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_security_policy (conversationId TEXT NOT NULL PRIMARY KEY, disappearAfterMs INTEGER, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(conversationId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS expired_media (mediaId TEXT NOT NULL, relationshipId TEXT NOT NULL, conversationId TEXT NOT NULL, expiredAt INTEGER NOT NULL, PRIMARY KEY(mediaId, relationshipId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS privacy_file_cleanup (path TEXT NOT NULL PRIMARY KEY, queuedAt INTEGER NOT NULL)")
    }
}
