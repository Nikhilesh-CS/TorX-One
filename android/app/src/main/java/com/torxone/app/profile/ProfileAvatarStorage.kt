package com.torxone.app.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object ProfileAvatarStorage {
    fun resolve(context: Context, hash: String?): File? {
        if (hash?.matches(Regex("[a-f0-9]{64}")) != true) return null
        return File(context.filesDir, "profile/avatars/$hash.jpg").takeIf(File::isFile)
    }
    fun save(context: Context, bytes: ByteArray): String {
        require(bytes.size in 1..ProfileUpdateCodec.MAX_AVATAR_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..512 && options.outHeight in 1..512) { "Invalid profile photo" }
        val hash = ProfileUpdateCodec.hash(bytes)
        val file = File(context.filesDir, "profile/avatars/$hash.jpg")
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            val tmp = File.createTempFile("avatar", ".tmp", file.parentFile)
            try { tmp.writeBytes(bytes); check(tmp.renameTo(file)) } finally { tmp.delete() }
        }
        return hash
    }
    fun readForSync(context: Context, uri: String?): ByteArray? {
        if (uri == null) return null
        return context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer); if (count < 0) break
                require(output.size() + count <= 10 * 1024 * 1024) { "Profile image exceeds 10 MB" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Invalid profile image" }
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid profile image")
            try { compress(bitmap) } finally { bitmap.recycle() }
        }
    }
    fun compress(bitmap: Bitmap): ByteArray {
        var quality = 90
        while (quality >= 20) {
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= ProfileUpdateCodec.MAX_AVATAR_BYTES) return bytes
            quality -= 10
        }
        error("Unable to resize profile image")
    }
}
