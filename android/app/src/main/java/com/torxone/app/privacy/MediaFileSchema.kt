package com.torxone.app.privacy

import androidx.sqlite.db.SupportSQLiteDatabase

object MediaFileSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_media_files (path TEXT NOT NULL PRIMARY KEY, mediaId TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}
