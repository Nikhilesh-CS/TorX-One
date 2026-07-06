package com.astramesh.app.identity.profile

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ProfileCacheManager {
    suspend fun getAvatarFile(ownerKey: String, resolution: String = "original"): File?
    suspend fun saveAvatarBytes(ownerKey: String, resolution: String, data: ByteArray): File
    suspend fun clearCache(ownerKey: String)
}

class ProfileCacheManagerImpl(private val context: Context) : ProfileCacheManager {
    
    private val profileDir: File
        get() = File(context.filesDir, "profile").apply {
            if (!exists()) mkdirs()
        }

    override suspend fun getAvatarFile(ownerKey: String, resolution: String): File? = withContext(Dispatchers.IO) {
        val file = File(profileDir, "${ownerKey}_avatar_$resolution.webp")
        if (file.exists()) file else null
    }

    override suspend fun saveAvatarBytes(ownerKey: String, resolution: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val file = File(profileDir, "${ownerKey}_avatar_$resolution.webp")
        file.writeBytes(data)
        file
    }

    override suspend fun clearCache(ownerKey: String) = withContext(Dispatchers.IO) {
        val prefix = "${ownerKey}_avatar_"
        profileDir.listFiles { _, name -> name.startsWith(prefix) }?.forEach {
            it.delete()
        }
    }
}
