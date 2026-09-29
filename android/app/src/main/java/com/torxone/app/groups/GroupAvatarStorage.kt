package com.torxone.app.groups

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** App-owned, content-addressed storage for group avatar images. */
object GroupAvatarStorage {
    const val MAX_BYTES = 10L * 1024L * 1024L

    fun save(context: Context, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Group image must be between 1 byte and 10 MB" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "group_avatars").apply { mkdirs() }
        val destination = File(directory, hash)
        if (!destination.exists()) {
            val temporary = File(directory, "$hash.tmp")
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(destination) || destination.exists()) { "Unable to store group image" }
            temporary.delete()
        }
        return hash
    }

    fun resolve(context: Context, hash: String?): File? = hash
        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        ?.let { File(File(context.filesDir, "group_avatars"), it) }
        ?.takeIf(File::isFile)
}
