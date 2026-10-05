package com.torxone.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.appearance.ChatAppearanceRepository
import com.torxone.app.ui.components.ChatAppearanceSheet
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatAppearanceEditorInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewDoesNotPersistUntilApply() = withFixture { repository ->
        var dismissed = false
        compose.setContent { TorXOneTheme { ChatAppearanceSheet(repository, onDismiss = { dismissed = true }) } }
        compose.waitUntil { compose.onAllNodesWithText("Depth").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Depth").performScrollTo().performClick()
        assertEquals("GRAPHITE", runBlocking { repository.observeDefault().first().preset })
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil { dismissed }
        assertEquals("DEPTH", runBlocking { repository.observeDefault().first().preset })
    }
    @Test fun cancelLeavesSavedDefaultUnchanged() = withFixture { repository ->
        var dismissed = false
        compose.setContent { TorXOneTheme { ChatAppearanceSheet(repository, onDismiss = { dismissed = true }) } }
        compose.waitUntil { compose.onAllNodesWithText("Depth").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Depth").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Cancel appearance changes").performClick()
        compose.waitUntil { dismissed }
        assertEquals("GRAPHITE", runBlocking { repository.observeDefault().first().preset })
    }
    private fun withFixture(block: (ChatAppearanceRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "appearance-fixture-${java.util.UUID.randomUUID()}.preferences_pb")
        val fixtureFiles = File(context.cacheDir, "appearance-files-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = fixtureFiles
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try { block(ChatAppearanceRepository(isolatedContext, store)) }
        finally { scope.cancel(); file.delete(); fixtureFiles.deleteRecursively() }
    }
}
