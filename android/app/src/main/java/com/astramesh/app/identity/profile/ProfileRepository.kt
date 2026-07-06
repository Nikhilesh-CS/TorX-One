package com.astramesh.app.identity.profile

import android.net.Uri
import com.astramesh.app.data.ProfileDao
import com.astramesh.app.data.ProfileEntity
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.media.ImageProcessor
import kotlinx.coroutines.flow.Flow

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
        return profileDao.getProfile(localUserKey)
    }

    override fun getContactProfile(ownerKey: String): Flow<ProfileEntity?> {
        return profileDao.getProfile(ownerKey)
    }

    override suspend fun updateLocalProfile(name: String, bio: String, statusMessage: String, avatarUri: Uri?) {
        val currentProfile = profileDao.getProfileSync(localUserKey)
        
        var newAvatarHash = currentProfile?.avatarHash
        var newAvatarLocalPath = currentProfile?.avatarLocalPath

        if (avatarUri != null) {
            val processed = imageProcessor.processAvatar(avatarUri).getOrThrow()
            newAvatarHash = processed.hash
            
            // Save multi-res webp to disk cache
            profileCacheManager.saveAvatarBytes(localUserKey, "original", processed.originalBytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "512", processed.size512Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "256", processed.size256Bytes)
            val thumbFile = profileCacheManager.saveAvatarBytes(localUserKey, "thumb", processed.thumbBytes)
            
            newAvatarLocalPath = thumbFile.absolutePath
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

    override suspend fun saveContactProfile(profileEntity: ProfileEntity) {
        profileDao.insertProfile(profileEntity)
    }

    private fun generateProfileHash(name: String, bio: String, status: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val data = "$name|$bio|$status".toByteArray()
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
