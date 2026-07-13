package com.torxone.app.identity.profile

import android.net.Uri
import com.torxone.app.data.ProfileDao
import com.torxone.app.data.ProfileEntity
import com.torxone.app.identity.IdentityManager
import com.torxone.app.media.ImageProcessor
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
        identityManager.updateName(name)
        val currentProfile = profileDao.getProfileSync(localUserKey)
        
        var newAvatarHash = currentProfile?.avatarHash
        var newAvatarLocalPath = currentProfile?.avatarLocalPath

        if (avatarUri != null) {
            val processed = imageProcessor.processAvatar(avatarUri).getOrThrow()
            newAvatarHash = processed.hash
            
            // Preserve original bytes locally for profile viewing. Contacts receive optimized derivatives.
            val originalFile = profileCacheManager.saveAvatarBytes(
                localUserKey,
                "original",
                processed.originalBytes,
                processed.originalExtension
            )
            profileCacheManager.saveAvatarBytes(localUserKey, "512", processed.size512Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "1024", processed.size1024Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "256", processed.size256Bytes)
            profileCacheManager.saveAvatarBytes(localUserKey, "thumb", processed.thumbBytes)
            
            newAvatarLocalPath = originalFile.absolutePath
        }

        val localSigningKey = identityManager.loadIdentity()?.let { identity ->
            com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)
        }.orEmpty()
        val isFounder = FounderProfile.isFounderSigningKey(localSigningKey)
        val effectiveBio = if (isFounder && bio.isBlank()) FounderProfile.bio else bio
        val effectiveStatus = if (isFounder && statusMessage.isBlank()) FounderProfile.statusMessage else statusMessage
        val profileHash = generateProfileHash(name, effectiveBio, effectiveStatus)
        val newVersion = (currentProfile?.profileVersion ?: 0) + 1

        val updatedProfile = ProfileEntity(
            ownerKey = localUserKey,
            name = name,
            bio = effectiveBio,
            statusMessage = effectiveStatus,
            avatarHash = newAvatarHash,
            profileHash = profileHash,
            profileVersion = newVersion,
            lastUpdatedAt = System.currentTimeMillis(),
            avatarLocalPath = newAvatarLocalPath,
            verifiedBadge = isFounder
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
