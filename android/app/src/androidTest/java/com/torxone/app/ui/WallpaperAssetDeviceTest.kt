package com.torxone.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.appearance.WallpaperAssetStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WallpaperAssetDeviceTest {
    @Test fun importIsPrivateBoundedAndDoesNotRetainPickerFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = WallpaperAssetStorage(context)
        val source = File.createTempFile("wallpaper-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var asset: File? = null
        try {
            val id = storage.importPhoto(Uri.fromFile(source))
            asset = storage.resolve(id)
            assertNotNull(asset)
            assertEquals(File(context.filesDir, "ui-wallpapers").canonicalFile, asset!!.parentFile.canonicalFile)
            source.delete()
            val decoded = storage.decode(id)
            assertNotNull(decoded)
            assertEquals(128, decoded!!.width)
            decoded.recycle()
            assertNull(storage.resolve("../../profile/avatar"))
        } finally { source.delete(); asset?.delete() }
    }
}
