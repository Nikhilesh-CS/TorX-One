package com.astramesh.app.identity.backup

import android.content.Context
import android.util.Log
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.crypto.Identity
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.network.TorManager
import com.astramesh.app.network.TorState
import com.astramesh.app.service.AstraMeshService
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream

class IdentityRestoreManager(private val context: Context) {
    private val identityManager = IdentityManager(context)
    private val gson = Gson()
    private val TAG = "IdentityRestore"

    suspend fun restoreBackup(inputStream: InputStream, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val encryptedBytes = inputStream.use { it.readBytes() }
            if (encryptedBytes.isEmpty()) {
                return@withContext Result.failure(Exception("Backup file is empty."))
            }

            // 1. Decrypt the payload
            val jsonPayload = BackupCrypto.decryptBackup(encryptedBytes, password)
            val jsonString = String(jsonPayload, Charsets.UTF_8)
            
            // 2. Validate JSON Schema
            val dto = gson.fromJson(jsonString, IdentityBackupDto::class.java)
                ?: return@withContext Result.failure(Exception("Failed to parse backup format."))

            if (dto.schemaVersion > 1) {
                return@withContext Result.failure(Exception("Backup schema v${dto.schemaVersion} is not supported by this version of AstraMesh."))
            }

            if (dto.identityName.isBlank() || dto.onionAddress.isBlank()) {
                return@withContext Result.failure(Exception("Backup is corrupted: Missing essential identity fields."))
            }

            // 3. Validate Identity Keys
            val identity = try {
                Identity(
                    name = dto.identityName,
                    encryptionPublicKey = CryptoManager.fromHex(dto.encPubHex),
                    encryptionSecretKey = CryptoManager.fromHex(dto.encSecHex),
                    signingPublicKey = CryptoManager.fromHex(dto.sigPubHex),
                    signingSecretKey = CryptoManager.fromHex(dto.sigSecHex)
                )
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Backup is corrupted: Invalid cryptographic keys.", e))
            }

            // 4. Validate Onion Keys
            if (dto.torHsEd25519PublicKeyB64.isBlank() || dto.torHsEd25519SecretKeyB64.isBlank()) {
                return@withContext Result.failure(Exception("Backup is corrupted: Missing Tor Hidden Service keys."))
            }

            val service = AstraMeshService.getInstance()
            val torManager = service?.torManager ?: TorManager(context)

            // 5. Create Emergency Rollback Backup
            val previousIdentity = identityManager.loadIdentity()
            val previousOnion = identityManager.loadOnionAddress()
            var previousTorKeys: TorManager.ExportedTorKeys? = null
            try {
                previousTorKeys = torManager.exportHiddenServiceKeys()
            } catch (e: Exception) {
                Log.w(TAG, "No previous Tor keys to backup: ${e.message}")
            }

            try {
                // 6. Perform Atomic Swap
                service?.torManager?.stop()
                
                // Write new Tor keys
                torManager.importHiddenServiceKeys(
                    pubKeyB64 = dto.torHsEd25519PublicKeyB64,
                    secKeyB64 = dto.torHsEd25519SecretKeyB64,
                    onionAddress = dto.onionAddress
                )

                // Write new Identity
                identityManager.saveIdentity(identity)
                identityManager.saveOnionAddress(dto.onionAddress)

                // Restore Profile
                val db = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    com.astramesh.app.data.AppDatabase::class.java,
                    "astra-mesh-db"
                ).build()
                val profileDao = db.profileDao()
                val profileCacheManager = com.astramesh.app.identity.profile.ProfileCacheManagerImpl(context)

                var localAvatarPath: String? = null
                if (dto.avatarWebPB64 != null) {
                    try {
                        val avatarBytes = java.util.Base64.getDecoder().decode(dto.avatarWebPB64)
                        val thumbFile = profileCacheManager.saveAvatarBytes("LOCAL_USER", "thumb", avatarBytes)
                        localAvatarPath = thumbFile.absolutePath
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore avatar from backup", e)
                    }
                }

                val restoredBio = runCatching { dto.bio }.getOrNull().orEmpty()
                val restoredStatus = runCatching { dto.statusMessage }.getOrNull().orEmpty()
                val restoredProfileHash = runCatching { dto.profileHash }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { "${identity.name}:${System.currentTimeMillis()}" }
                val restoredProfileVersion = runCatching { dto.profileVersion }.getOrDefault(1).coerceAtLeast(1)
                val restoredUpdatedAt = runCatching { dto.lastUpdatedAt }.getOrDefault(0L)
                    .takeIf { it > 0L }
                    ?: System.currentTimeMillis()

                val restoredProfile = com.astramesh.app.data.ProfileEntity(
                    ownerKey = "LOCAL_USER",
                    name = identity.name,
                    bio = restoredBio,
                    statusMessage = restoredStatus,
                    avatarHash = dto.avatarHash,
                    profileHash = restoredProfileHash,
                    profileVersion = restoredProfileVersion,
                    lastUpdatedAt = restoredUpdatedAt,
                    avatarLocalPath = localAvatarPath
                )
                profileDao.insertProfile(restoredProfile)
                // db.close() // Room will handle this

                // 7. Restart and Verify Self-Test
                if (service != null) {
                    service.restartNetworking()
                    
                    // Wait for Tor to boot and verify
                    val isTorVerified = verifyTorStartup(torManager)
                    if (!isTorVerified) {
                        throw Exception("Tor failed to initialize with the restored keys.")
                    }
                    
                    val activeOnion = identityManager.loadOnionAddress()
                    if (activeOnion != dto.onionAddress) {
                        throw Exception("Onion address mismatch after restore.")
                    }
                }

                Result.success(Unit)
            } catch (restoreException: Exception) {
                Log.e(TAG, "Restore failed, executing emergency rollback: ${restoreException.message}")
                
                // Execute Rollback
                try {
                    service?.torManager?.stop()
                    
                    if (previousIdentity != null) {
                        identityManager.saveIdentity(previousIdentity)
                    }
                    if (previousOnion != null) {
                        identityManager.saveOnionAddress(previousOnion)
                    }
                    if (previousTorKeys != null) {
                        torManager.importHiddenServiceKeys(
                            pubKeyB64 = previousTorKeys.pubKeyB64,
                            secKeyB64 = previousTorKeys.secKeyB64,
                            onionAddress = previousTorKeys.onionAddress
                        )
                    }
                    
                    service?.restartNetworking()
                } catch (rollbackException: Exception) {
                    Log.e(TAG, "CRITICAL: Emergency rollback failed: ${rollbackException.message}")
                }
                
                Result.failure(Exception("Restore failed. Rolled back to previous identity. Reason: ${restoreException.message}", restoreException))
            }
        } catch (e: Exception) {
            // Check for crypto exceptions like AEADBadTagException
            if (e is javax.crypto.AEADBadTagException || e.message?.contains("mac check") == true) {
                Result.failure(Exception("Incorrect password or corrupted backup file."))
            } else {
                Result.failure(e)
            }
        }
    }

    private suspend fun verifyTorStartup(torManager: TorManager): Boolean {
        // Wait up to 25 seconds for Tor to reach Connected state
        val timeout = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < timeout) {
            if (torManager.isTorReady.value) return true
            if (torManager.torState.value is TorState.Failed) return false
            delay(500)
        }
        return false
    }
}
