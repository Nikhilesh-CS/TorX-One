package com.astramesh.app.identity.profile

import android.net.Uri
import com.astramesh.app.data.ProfileDao
import com.astramesh.app.data.ProfileEntity
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.media.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface ProfileRepository {
    fun getLocalProfile(): Flow<ProfileEntity?>
    fun getContactProfile(ownerKey: String): Flow<ProfileEntity?>
    suspend fun updateLocalProfile(name: String, bio: String, statusMessage: String, avatarUri: Uri? = null)
    suspend fun saveContactProfile(profileEntity: ProfileEntity)
}

class ProfileRepositoryImpl(
    private val profileDao: ProfileDao,
    private val identityManager: IdentityManager,
    private val profileCacheManager: ProfileCacheManager,
    private val imageProcessor: ImageProcessor
) : ProfileRepository {

    private val localUserKey = "LOCAL_USER"

    override fun getLocalProfile(): Flow<ProfileEntity?> {
        return profileDao.getProfile(localUserKey).flowOn(Dispatchers.IO)
    }

    override fun getContactProfile(ownerKey: String): Flow<ProfileEntity?> {
        return profileDao.getProfile(ownerKey).flowOn(Dispatchers.IO)
    }

    override suspend fun updateLocalProfile(name: String, bio: String, statusMessage: String, avatarUri: Uri?) {
        withContext(Dispatchers.IO) {
        val currentProfile = profileDao.getProfileSync(localUserKey)
        
        var newAvatarHash = currentProfile?.avatarHash
        var newAvatarLocalPath = currentProfile?.avatarLocalPath

        if (avatarUri != null) {
            val processed = imageProcessor.processAvatar(avatarUri).getOrThrow()
            newAvatarHash = processed.hash
            
            // Preserve the original bytes for profile viewing and sync; only derived sizes are compressed.
            val originalFile = profileCacheManager.saveAvatarBytes(
                localUserKey,
                "original",
                processed.originalBytes,
                processed.originalExtension
            )
            profileCacheManager.saveAvatarBytes(localUserKey, "512", processed.size512Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "256", processed.size256Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "thumb", processed.thumbBytes)
            
            newAvatarLocalPath = originalFile.absolutePath
        }

        val profileHash = generateProfileHash(name, bio, statusMessage)
        val newVersion = (currentProfile?.profileVersion ?: 0) + 1

        val updatedProfile = ProfileEntity(
            ownerKey = localUserKey,
            name = name,
            bio = bio,
            statusMessage = statusMessage,
            avatarHash = newAvatarHash,
            profileHash = profileHash,
            profileVersion = newVersion,
            lastUpdatedAt = System.currentTimeMillis(),
            avatarLocalPath = newAvatarLocalPath
        )

        profileDao.insertProfile(updatedProfile)
        }
    }

    override suspend fun saveContactProfile(profileEntity: ProfileEntity) {
        withContext(Dispatchers.IO) {
            profileDao.insertProfile(profileEntity)
        }
    }

    private fun generateProfileHash(name: String, bio: String, status: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val data = "$name|$bio|$status".toByteArray()
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
