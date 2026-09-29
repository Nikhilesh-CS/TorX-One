package com.torxone.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Generates local QR code ImageBitmaps using ZXing core.
 */
object QrCodeGenerator {
    fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap? {
        return try {
            val bitMatrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4)
            )
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            drawBrandMark(bitmap, size)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    private fun drawBrandMark(bitmap: Bitmap, size: Int) {
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val badge = size * 0.15f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(RectF(center - badge, center - badge, center + badge, center + badge), badge * 0.28f, badge * 0.28f, paint)
        paint.color = 0xFF07140F.toInt()
        val outer = badge * 0.70f
        val inner = badge * 0.34f
        val path = android.graphics.Path().apply {
            moveTo(center, center - outer); lineTo(center + outer, center); lineTo(center, center + outer)
            lineTo(center - outer, center); close()
        }
        canvas.drawPath(path, paint)
        paint.color = 0xFF35E58D.toInt()
        val core = android.graphics.Path().apply {
            moveTo(center, center - inner); lineTo(center + inner, center); lineTo(center, center + inner)
            lineTo(center - inner, center); close()
        }
        canvas.drawPath(core, paint)
    }
}
