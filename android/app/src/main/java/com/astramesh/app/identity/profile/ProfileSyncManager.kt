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

class ProfileSyncManager(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val messageRouter: MessageRouter
) {
    private val TAG = "ProfileSyncManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val localUserKey = "LOCAL_USER"

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

            val newProfile = ProfileEntity(
                ownerKey = senderKey,
                name = name,
                bio = bio,
                statusMessage = statusMessage,
                avatarHash = avatarHash.ifBlank { null },
                profileHash = profileHash,
                profileVersion = version,
                lastUpdatedAt = lastUpdatedAt,
                avatarLocalPath = currentProfile?.avatarLocalPath
            )

            profileRepository.saveContactProfile(newProfile)

            // Check if we need to request the photo
            if (avatarHash.isNotBlank() && avatarHash != currentProfile?.avatarHash) {
                sendProfilePhotoRequest(senderKey, avatarHash)
            }
        } else {
            Log.d(TAG, "Received profile update from $senderKey but we already have version ${currentProfile.profileVersion}")
        }
    }

    private suspend fun handleRequestProfilePhoto(json: JSONObject, senderKey: String) {
        val requestedHash = json.optString("avatarHash", "")
        if (requestedHash.isBlank()) return

        val myProfile = profileRepository.getLocalProfile().firstOrNull()
        if (myProfile?.avatarHash == requestedHash && myProfile.avatarLocalPath != null) {
            // Read file and send it
            try {
                val file = java.io.File(myProfile.avatarLocalPath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    sendProfilePhotoChunk(senderKey, requestedHash, b64)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read local avatar for sending", e)
            }
        }
    }

    private suspend fun handleProfilePhotoChunk(json: JSONObject, senderKey: String) {
        val avatarHash = json.optString("avatarHash", "")
        val dataB64 = json.optString("data", "")
        
        if (avatarHash.isBlank() || dataB64.isBlank()) return

        val profile = profileRepository.getContactProfile(senderKey).firstOrNull()
        if (profile != null && profile.avatarHash == avatarHash) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(dataB64)
                val cacheManager = ProfileCacheManagerImpl(context)
                val file = cacheManager.saveAvatarBytes(senderKey, "thumb", bytes)

                // Update the database with the local path
                val updatedProfile = profile.copy(avatarLocalPath = file.absolutePath)
                profileRepository.saveContactProfile(updatedProfile)
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

    private suspend fun sendProfilePhotoRequest(targetContactKey: String, avatarHash: String) {
        val json = JSONObject().put("avatarHash", avatarHash)
        messageRouter.sendRawPayload(targetContactKey, json.toString(), MeshProtocol.TYPE_REQUEST_PROFILE_PHOTO)
    }

    private suspend fun sendProfilePhotoChunk(targetContactKey: String, avatarHash: String, dataB64: String) {
        val json = JSONObject()
            .put("avatarHash", avatarHash)
            .put("data", dataB64)
        messageRouter.sendRawPayload(targetContactKey, json.toString(), MeshProtocol.TYPE_PROFILE_PHOTO_CHUNK)
    }
}
