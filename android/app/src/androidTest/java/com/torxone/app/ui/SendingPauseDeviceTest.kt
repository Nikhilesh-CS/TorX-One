package com.torxone.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.profile.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendingPauseDeviceTest {
    @Test fun pausePreferenceSurvivesRepositoryRecreationWithoutChangingOtherChats() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = AppSettingsRepository(context)
        val id = "pause-fixture-" + java.util.UUID.randomUUID()
        val before = first.pausedConversations.first()
        try {
            first.setConversationSendingPaused(id, true)
            val reopened = AppSettingsRepository(context)
            assertEquals(before + id, reopened.pausedConversations.first())
            reopened.setConversationSendingPaused(id, false)
            assertEquals(before, first.pausedConversations.first())
        } finally { first.setConversationSendingPaused(id, false) }
    }
}
