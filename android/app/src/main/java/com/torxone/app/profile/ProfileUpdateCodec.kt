package com.torxone.app.profile

import java.io.*
import java.security.MessageDigest
import java.util.Base64

data class ProfileUpdate(val name: String, val about: String, val version: Long = 0,
    val avatar: ByteArray? = null, val hasAvatarUpdate: Boolean = false)

/** Name/about prefix remains readable by older peers. Photos travel inside the authenticated envelope. */
object ProfileUpdateCodec {
    const val MAX_AVATAR_BYTES = 24 * 1024
    private const val MARKER = "TXPROFILE2:"
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun encode(update: ProfileUpdate): ByteArray {
        require(update.name.isNotBlank() && update.name.length <= 40 && '\n' !in update.name)
        require(update.about.length <= 140)
        require((update.avatar?.size ?: 0) <= MAX_AVATAR_BYTES)
        val extension = ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.writeUTF(update.about); it.writeLong(update.version); it.writeBoolean(update.hasAvatarUpdate)
            it.writeInt(update.avatar?.size ?: 0); update.avatar?.let(it::write)
        } }.toByteArray()
        return "${update.name}\n${update.about.replace('\n', ' ')}\n$MARKER${Base64.getEncoder().encodeToString(extension)}".toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes: ByteArray): ProfileUpdate {
        require(bytes.size <= 36 * 1024) { "Profile update too large" }
        val parts = bytes.toString(Charsets.UTF_8).split('\n', limit = 3)
        val name = parts[0].trim()
        require(name.isNotBlank() && name.length <= 40)
        val extension = parts.getOrNull(2)
        if (extension?.startsWith(MARKER) != true) return ProfileUpdate(name, parts.getOrElse(1) { "" }.take(140))
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(extension.removePrefix(MARKER)))).use {
            val about = it.readUTF(); require(about.length <= 140)
            val version = it.readLong(); require(version > 0)
            val changed = it.readBoolean()
            val size = it.readInt(); require(size in 0..MAX_AVATAR_BYTES && size == it.available())
            val avatar = if (size > 0) ByteArray(size).also(it::readFully) else null
            require(changed || avatar == null)
            ProfileUpdate(name, about, version, avatar, changed)
        }
    }
}
