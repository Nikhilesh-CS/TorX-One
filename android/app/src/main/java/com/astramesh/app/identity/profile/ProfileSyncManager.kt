package com.astramesh.app.identity.profile

import android.content.Context
import android.util.Log
import com.astramesh.app.data.ProfileEntity
import com.astramesh.app.network.MeshProtocol
import com.astramesh.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ProfileSyncManager(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val messageRouter: MessageRouter
) {
    private val TAG = "ProfileSyncManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val localUserKey = "LOCAL_USER"
    private val maxSyncedAvatarBytes = 2L * 1024L * 1024L
    private val lastProfileSyncAt = ConcurrentHashMap<String, Long>()

    fun handleProfilePacket(type: String, payload: String, senderKey: String) {
        scope.launch {
            try {
                val json = JSONObject(payload)
                when (type) {
                    MeshProtocol.TYPE_PROFILE_UPDATE -> handleProfileUpdate(json, senderKey)
                    MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO -> handleRequestProfilePhoto(json, senderKey)
                    MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK -> handleProfilePhotoChunk(json, senderKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse profile packet", e)
            }
        }
    }

    private suspend fun handleProfileUpdate(json: JSONObject, senderKey: String) {
        val version = json.optInt("version", 0)
        val name = json.optString("name", "")
        val bio = json.optString("bio", "")
        val statusMessage = json.optString("statusMessage", "")
        val avatarHash = json.optString("avatarHash", "")
        val profileHash = json.optString("profileHash", "")
        val lastUpdatedAt = json.optLong("lastUpdatedAt", 0L)

        val currentProfile = profileRepository.getContactProfile(senderKey).firstOrNull()

        if (currentProfile == null || version > currentProfile.profileVersion) {
            Log.d(TAG, "Received newer profile version $version from $senderKey")
            val avatarChanged = avatarHash.isNotBlank() && avatarHash != currentProfile?.avatarHash

            val newProfile = ProfileEntity(
                ownerKey = senderKey,
                name = name,
                bio = bio,
                statusMessage = statusMessage,
                avatarHash = avatarHash.ifBlank { null },
                profileHash = profileHash,
                profileVersion = version,
                lastUpdatedAt = lastUpdatedAt,
                avatarLocalPath = currentProfile?.avatarLocalPath.takeUnless { avatarChanged }
            )

            profileRepository.saveContactProfile(newProfile)
            updateContactName(senderKey, name)

            // Check if we need to request the photo
            if (avatarHash.isNotBlank() && avatarHash != currentProfile?.avatarHash) {
                sendProfilePhotoRequest(senderKey, avatarHash)
            }
        } else if (avatarHash.isNotBlank() && currentProfile.avatarHash == avatarHash && currentProfile.avatarLocalPath.isNullOrBlank()) {
            sendProfilePhotoRequest(senderKey, avatarHash)
        } else {
            Log.d(TAG, "Received profile update from $senderKey but we already have version ${currentProfile.profileVersion}")
        }
    }

    private suspend fun handleRequestProfilePhoto(json: JSONObject, senderKey: String) {
        val requestedHash = json.optString("avatarHash", "")
        if (requestedHash.isBlank()) return

        val myProfile = profileRepository.getLocalProfile().firstOrNull()
        if (myProfile != null && myProfile.avatarHash == requestedHash && myProfile.avatarLocalPath != null) {
            try {
                val cacheManager = ProfileCacheManagerImpl(context)
                val file = listOfNotNull(
                    cacheManager.getAvatarFile(localUserKey, "1024"),
                    cacheManager.getAvatarFile(localUserKey, "512"),
                    cacheManager.getAvatarFile(localUserKey, "256"),
                    cacheManager.getAvatarFile(localUserKey, "thumb")
                ).firstOrNull { it.exists() && it.length() <= maxSyncedAvatarBytes }
                    ?: return
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    sendProfilePhotoChunk(senderKey, requestedHash, b64, ".${file.extension.ifBlank { "jpg" }}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read local avatar for sending", e)
            }
        }
    }

    private suspend fun handleProfilePhotoChunk(json: JSONObject, senderKey: String) {
        val avatarHash = json.optString("avatarHash", "")
        val dataB64 = json.optString("data", "")
        val extension = json.optString("extension", ".jpg")
        
        if (avatarHash.isBlank() || dataB64.isBlank()) return

        val profile = profileRepository.getContactProfile(senderKey).firstOrNull()
        if (profile != null && profile.avatarHash == avatarHash) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(dataB64)
                val cacheManager = ProfileCacheManagerImpl(context)
                val file = cacheManager.saveAvatarBytes(senderKey, "1024", bytes, extension)

                // Update the database with the local path
                val updatedProfile = profile.copy(avatarLocalPath = file.absolutePath)
                profileRepository.saveContactProfile(updatedProfile)
                updateContactName(senderKey, profile.name)
                Log.d(TAG, "Successfully received and saved avatar for $senderKey")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save received avatar chunk", e)
            }
        }
    }

    suspend fun broadcastLocalProfile(targetContactKey: String) {
        val myProfile = profileRepository.getLocalProfile().firstOrNull() ?: return
        
        val json = JSONObject()
            .put("version", myProfile.profileVersion)
            .put("name", myProfile.name)
            .put("bio", myProfile.bio)
            .put("statusMessage", myProfile.statusMessage)
            .put("avatarHash", myProfile.avatarHash ?: "")
            .put("profileHash", myProfile.profileHash)
            .put("lastUpdatedAt", myProfile.lastUpdatedAt)
            
        messageRouter.sendRawPayload(targetContactKey, json.toString(), MeshProtocol.TYPE_PROFILE_UPDATE)
    }

    fun syncWithContactSoon(targetContactKey: String) {
        if (targetContactKey.isBlank()) return
        val now = System.currentTimeMillis()
        val lastSync = lastProfileSyncAt[targetContactKey] ?: 0L
        if (now - lastSync < 30L * 60L * 1000L) return
        lastProfileSyncAt[targetContactKey] = now
        scope.launch {
            broadcastLocalProfile(targetContactKey)
        }
    }

    suspend fun broadcastLocalProfileToAll() {
        val myProfile = profileRepository.getLocalProfile().firstOrNull() ?: return
        
        val json = JSONObject()
            .put("version", myProfile.profileVersion)
            .put("name", myProfile.name)
            .put("bio", myProfile.bio)
            .put("statusMessage", myProfile.statusMessage)
            .put("avatarHash", myProfile.avatarHash ?: "")
            .put("profileHash", myProfile.profileHash)
            .put("lastUpdatedAt", myProfile.lastUpdatedAt)

        // For now, let's assume we broadcast to all known contacts, 
        // MessageRouter handles whether to relay or use Tor
        val contacts = com.astramesh.app.service.AstraMeshService.getInstance()?.db?.contactDao()?.getAllContactsSync() ?: return
        for (contact in contacts) {
            messageRouter.sendRawPayload(contact.signingPublicKey, json.toString(), MeshProtocol.TYPE_PROFILE_UPDATE)
        }
    }

    private suspend fun sendProfilePhotoRequest(targetContactKey: String, avatarHash: String) {
        val json = JSONObject().put("avatarHash", avatarHash)
        messageRouter.sendRawPayload(targetContactKey, json.toString(), MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO)
    }

    private suspend fun sendProfilePhotoChunk(targetContactKey: String, avatarHash: String, dataB64: String, extension: String) {
        val json = JSONObject()
            .put("avatarHash", avatarHash)
            .put("extension", extension)
            .put("data", dataB64)
        messageRouter.sendRawPayload(targetContactKey, json.toString(), MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK)
    }

    private fun updateContactName(senderKey: String, name: String) {
        if (name.isBlank()) return
        val service = com.astramesh.app.service.AstraMeshService.getInstance() ?: return
        val contact = service.db.contactDao().getContact(senderKey) ?: return
        if (contact.name != name) {
            service.db.contactDao().insertContact(contact.copy(name = name))
        }
    }
}
