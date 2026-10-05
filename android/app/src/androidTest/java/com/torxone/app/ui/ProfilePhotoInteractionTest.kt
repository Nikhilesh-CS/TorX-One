package com.torxone.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.components.ProfileAvatar
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfilePhotoInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun avatarOpensPhotoWhileNameStillOpensParentDestination() {
        val file = File.createTempFile("profile-viewer-fixture-", ".png", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            var parentClicks = 0
            compose.setContent { TorXOneTheme {
                Row(Modifier.clickable { parentClicks++ }) {
                    ProfileAvatar("Alice", Uri.fromFile(file).toString(), Modifier.size(50.dp), previewOnClick = true)
                    Text("Contact info")
                }
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Profile photo of Alice", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Profile photo of Alice", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithContentDescription("Close profile photo").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(0, parentClicks) }
            compose.onNodeWithText("Contact info").performClick()
            compose.runOnIdle { assertEquals(1, parentClicks) }
        } finally { if (!bitmap.isRecycled) bitmap.recycle(); file.delete() }
    }

    @Test fun missingPhotoNeverOpensFullscreenInitials() {
        compose.setContent { TorXOneTheme {
            ProfileAvatar("Alice", null, Modifier.size(50.dp), previewOnClick = true)
        } }
        compose.onNodeWithText("A").performTouchInput { click() }
        compose.onNodeWithContentDescription("Close profile photo").assertDoesNotExist()
    }
}
