package com.torxone.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.components.LocalImagePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaPreviewDeviceTest {
    private fun image(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun thumbnailIsBoundedWithoutChangingOriginal() = runBlocking {
        withContext(Dispatchers.IO) {
            val original = image(1600, 800, Color.RED)
            val snapshot = original.copyOf()
            val thumbnail = requireNotNull(LocalImagePreview.thumbnail(original))
            assertTrue(thumbnail.size <= 16 * 1024)
            assertArrayEquals(snapshot, original)
            val preview = requireNotNull(LocalImagePreview.message(thumbnail, null))
            try {
                assertTrue(maxOf(preview.width, preview.height) <= 320)
                assertEquals(2f, preview.width.toFloat() / preview.height, 0.02f)
            } finally { preview.recycle() }
        }
    }

    @Test fun existingThumbnailWinsOverLocalOriginal() = runBlocking {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("preview-fixture-", ".png", context().cacheDir)
            try {
                file.writeBytes(image(600, 600, Color.BLUE))
                val preview = requireNotNull(LocalImagePreview.message(image(100, 50, Color.RED), file.path))
                try { assertEquals(Color.RED, preview.getPixel(10, 10)); assertEquals(100, preview.width) }
                finally { preview.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun missingAndCorruptThumbnailUseSampledOriginal() = runBlocking {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("preview-fixture-", ".png", context().cacheDir)
            try {
                file.writeBytes(image(2400, 1200, Color.BLUE))
                for (thumbnail in listOf(null, byteArrayOf(1, 2, 3))) {
                    val preview = requireNotNull(LocalImagePreview.message(thumbnail, file.path))
                    try {
                        assertTrue(maxOf(preview.width, preview.height) <= 1024)
                        assertEquals(2f, preview.width.toFloat() / preview.height, 0.02f)
                        assertEquals(Color.BLUE, preview.getPixel(10, 10))
                    } finally { preview.recycle() }
                }
            } finally { file.delete() }
        }
    }

    @Test fun viewerReadsUnderlyingPhotoSeparatelyFromListSize() = runBlocking {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("avatar-fixture-", ".png", context().cacheDir)
            try {
                file.writeBytes(image(512, 256, Color.GREEN))
                val uri = Uri.fromFile(file).toString()
                val header = requireNotNull(LocalImagePreview.avatar(context(), uri, 256))
                val viewer = requireNotNull(LocalImagePreview.avatar(context(), uri, 2048))
                try { assertEquals(256, header.width); assertEquals(512, viewer.width) }
                finally { header.recycle(); viewer.recycle() }
            } finally { file.delete() }
        }
    }

    @Test fun placeholderOnlyWhenNeitherSourceDecodes() = runBlocking {
        withContext(Dispatchers.IO) {
            assertNull(LocalImagePreview.message(null, null))
            assertNull(LocalImagePreview.message(byteArrayOf(0), "missing-fixture.png"))
            assertNull(LocalImagePreview.avatar(context(), null, 2048))
        }
    }
}
