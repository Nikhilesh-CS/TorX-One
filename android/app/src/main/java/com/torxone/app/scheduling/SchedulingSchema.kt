package com.torxone.app.scheduling

import androidx.sqlite.db.SupportSQLiteDatabase

object SchedulingSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_messages` (`scheduleId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `ownerIdentityId` TEXT NOT NULL, `draftPayload` TEXT NOT NULL, `replyToMessageId` TEXT, `scheduledAt` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `state` TEXT NOT NULL, `leaseUntil` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`scheduleId`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`conversationId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_messages_conversationId` ON `scheduled_messages` (`conversationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_messages_state_scheduledAt` ON `scheduled_messages` (`state`, `scheduledAt`)")
    }
}
