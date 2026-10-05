package com.torxone.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/** Only visible image bubbles request their thumbnail; the timeline never loads 500 image blobs. */
val LocalChatThumbnailReader = staticCompositionLocalOf<((String) -> Flow<ByteArray?>)?> { null }

fun decodeChatThumbnail(bytes: ByteArray?, maxDimension: Int = 1024): Bitmap? {
    if (bytes == null || bytes.isEmpty() || bytes.size > 8 * 1024 * 1024 || maxDimension <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > maxDimension || bounds.outHeight / inSampleSize > maxDimension) inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
