package com.astramesh.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class ProcessedAvatar(
    val hash: String,
    val originalBytes: ByteArray,
    val size512Bytes: ByteArray,
    val size256Bytes: ByteArray,
    val thumbBytes: ByteArray
)

class ImageProcessor(private val context: Context) {

    suspend fun processAvatar(uri: Uri): Result<ProcessedAvatar> = withContext(Dispatchers.Default) {
        try {
            // Decode with limits to prevent OOM
            val bitmap = decodeSampledBitmapFromUri(uri, 1024, 1024)
                ?: return@withContext Result.failure(Exception("Failed to decode image"))

            // Scale to different resolutions (center crop logic could be added here, currently just scaling)
            val originalWebP = compressToWebP(bitmap)
            val size512WebP = compressToWebP(Bitmap.createScaledBitmap(bitmap, 512, 512, true))
            val size256WebP = compressToWebP(Bitmap.createScaledBitmap(bitmap, 256, 256, true))
            val thumbWebP = compressToWebP(Bitmap.createScaledBitmap(bitmap, 96, 96, true))

            val hash = generateHash(originalWebP)

            Result.success(ProcessedAvatar(hash, originalWebP, size512WebP, size256WebP, thumbWebP))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun compressToWebP(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        // Use WebP if available, fallback to JPEG
        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, stream)
        return stream.toByteArray()
    }

    private fun generateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
