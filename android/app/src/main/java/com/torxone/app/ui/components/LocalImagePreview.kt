package com.torxone.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Bounded decoding only; callers run on Dispatchers.IO. Originals are never rewritten. */
object LocalImagePreview {
    const val THUMBNAIL_DIMENSION = 320
    // Base64 expansion plus descriptor/group headers must fit the existing 32 KiB secure payload.
    const val THUMBNAIL_BYTES = 16 * 1024
    private fun decode(open: () -> InputStream?, maxDimension: Int): Bitmap? {
        if (maxDimension <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try { open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            catch (_: Exception) { null }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
    fun file(path: String?, maxDimension: Int = 1024): Bitmap? = path?.let(::File)?.takeIf(File::isFile)?.let { file ->
        decode({ file.inputStream() }, maxDimension)
    }
    /** A corrupt/stale thumbnail must not hide an otherwise usable downloaded original. */
    fun message(thumbnailData: ByteArray?, localPath: String?): Bitmap? {
        val preview = try {
            thumbnailData?.takeIf { it.size <= 8 * 1024 * 1024 }?.let { bytes -> decode({ ByteArrayInputStream(bytes) }, 640) }
        } catch (_: Exception) { null }
        if (preview != null) return preview
        return try { file(localPath, 1024) } catch (_: Exception) { null }
    }
    fun avatar(context: Context, value: String?, maxDimension: Int): Bitmap? {
        val file = com.torxone.app.profile.ProfileAvatarStorage.resolve(context, value)
            ?: com.torxone.app.groups.GroupAvatarStorage.resolve(context, value)
        if (file != null) return decode({ file.inputStream() }, maxDimension)
        if (value?.startsWith("file:") == true || value?.startsWith("content:") == true) {
            val uri = Uri.parse(value)
            return decode({ context.contentResolver.openInputStream(uri) }, maxDimension)
        }
        return null
    }
    fun thumbnail(bytes: ByteArray): ByteArray? {
        var bitmap = decode({ ByteArrayInputStream(bytes) }, THUMBNAIL_DIMENSION) ?: return null
        try {
            while (true) {
                for (quality in listOf(80, 65, 50, 35, 20)) {
                    val output = ByteArrayOutputStream()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= THUMBNAIL_BYTES)
                        return output.toByteArray()
                }
                if (bitmap.width == 1 && bitmap.height == 1) return null
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 2).coerceAtLeast(1), (bitmap.height / 2).coerceAtLeast(1), true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }
}
