package com.torxone.app.ui.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Private UI images are never stored in ProfileAvatarStorage or chat MediaService. */
class WallpaperAssetStorage(private val context: Context) {
    private val directory get() = File(context.filesDir, "ui-wallpapers")
    fun resolve(id: String?): File? {
        if (id == null || !ChatAppearance.assetPattern.matches(id)) return null
        val root = directory.canonicalFile
        val file = File(root, "$id.jpg").canonicalFile
        return file.takeIf { it.parentFile == root && it.isFile }
    }
    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 20 * 1024 * 1024) { "Choose a photo smaller than 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("This photo could not be opened")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Unsupported photo dimensions" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Unsupported photo")
        var oriented: Bitmap? = null
        try {
            val orientation = ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply { when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
            } }
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            save(oriented)
        } finally { if (oriented !== decoded) oriented?.recycle(); decoded.recycle() }
    }
    private fun save(bitmap: Bitmap): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        check(directory.mkdirs() || directory.isDirectory)
        val temp = File.createTempFile("wallpaper-", ".tmp", directory)
        try {
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            require(temp.length() in 1..4 * 1024 * 1024) { "This photo could not be resized" }
            check(temp.renameTo(File(directory, "$id.jpg"))) { "Wallpaper storage unavailable" }
        } finally { temp.delete() }
        return id
    }
    suspend fun decode(id: String?, maxDimension: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        val file = resolve(id) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > maxDimension || bounds.outHeight / inSampleSize > maxDimension) inSampleSize *= 2
        }
        BitmapFactory.decodeFile(file.path, options)
    }
    internal fun delete(id: String) { resolve(id)?.delete() }
    internal fun cleanUnused(references: Set<String>) {
        // Age grace protects imported images still being edited in another preview.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".jpg")
            if (ChatAppearance.assetPattern.matches(id) && id !in references && file.lastModified() < cutoff) resolve(id)?.delete()
        }
    }
}
