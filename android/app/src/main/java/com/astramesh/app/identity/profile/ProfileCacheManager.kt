package com.astramesh.app.identity.profile

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ProfileCacheManager {
    suspend fun getAvatarFile(ownerKey: String, resolution: String = "original"): File?
    suspend fun saveAvatarBytes(ownerKey: String, resolution: String, data: ByteArray, extension: String = ".webp"): File
    suspend fun clearCache(ownerKey: String)
}

class ProfileCacheManagerImpl(private val context: Context) : ProfileCacheManager {
    
    private val profileDir: File
        get() = File(context.filesDir, "profile").apply {
            if (!exists()) mkdirs()
        }

    override suspend fun getAvatarFile(ownerKey: String, resolution: String): File? = withContext(Dispatchers.IO) {
        profileDir
            .listFiles { _, name -> name.startsWith("${ownerKey}_avatar_$resolution.") }
            ?.firstOrNull()
    }

    override suspend fun saveAvatarBytes(ownerKey: String, resolution: String, data: ByteArray, extension: String): File = withContext(Dispatchers.IO) {
        val safeExtension = extension.takeIf { it.startsWith(".") && it.length <= 8 } ?: ".webp"
        profileDir.listFiles { _, name -> name.startsWith("${ownerKey}_avatar_$resolution.") }?.forEach { it.delete() }
        val file = File(profileDir, "${ownerKey}_avatar_$resolution$safeExtension")
        file.writeBytes(data)
        file
    }

    override suspend fun clearCache(ownerKey: String) {
        withContext(Dispatchers.IO) {
            val prefix = "${ownerKey}_avatar_"
            profileDir.listFiles { _, name -> name.startsWith(prefix) }?.forEach {
                it.delete()
            }
        }
    }
}
