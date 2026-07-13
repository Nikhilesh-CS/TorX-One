package com.torxone.app.identity.backup

import android.content.Context
import android.util.Base64
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.identity.profile.ProfileCacheManager
import com.torxone.app.identity.profile.ProfileRepository
import com.torxone.app.network.TorManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Base64 as JavaBase64

class IdentityBackupManager(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val profileCacheManager: ProfileCacheManager
) {
    private val identityManager = IdentityManager(context)
    private val torManager = TorManager(context)
    private val gson = Gson()

    suspend fun exportBackup(outputStream: OutputStream, password: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val identity = identityManager.loadIdentity() 
                ?: return@withContext Result.failure(Exception("No identity found to export."))
            val onionAddress = identityManager.loadOnionAddress()

            if (onionAddress.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Onion address not generated yet."))
            }

            // Export keys directly from TorManager
            val torKeys = try {
                torManager.exportHiddenServiceKeys()
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Tor keys are missing. Ensure Tor has run at least once."))
            }

            // Load Local Profile
            val profile = profileRepository.getLocalProfile().firstOrNull()
            var avatarWebPB64: String? = null

            if (profile?.avatarLocalPath != null) {
                try {
                    val avatarFile = profileCacheManager.getAvatarFile("LOCAL_USER", "thumb")
                    if (avatarFile != null && avatarFile.exists()) {
                        avatarWebPB64 = JavaBase64.getEncoder().encodeToString(avatarFile.readBytes())
                    }
                } catch (e: Exception) {
                    // Ignore missing avatar during backup
                }
            }

            val backupDto = IdentityBackupDto(
                backupVersion = 1,
                appVersion = "1.0.10",
                schemaVersion = 1,
                createdAt = java.time.Instant.now().toString(),
                identityVersion = 1,
                identityName = identity.name,
                creationTimestamp = System.currentTimeMillis(),
                encPubHex = CryptoManager.toHex(identity.encryptionPublicKey),
                encSecHex = CryptoManager.toHex(identity.encryptionSecretKey),
                sigPubHex = CryptoManager.toHex(identity.signingPublicKey),
                sigSecHex = CryptoManager.toHex(identity.signingSecretKey),
                onionAddress = onionAddress,
                torHsEd25519PublicKeyB64 = torKeys.pubKeyB64,
                torHsEd25519SecretKeyB64 = torKeys.secKeyB64,
                bio = profile?.bio ?: "",
                statusMessage = profile?.statusMessage ?: "",
                avatarHash = profile?.avatarHash,
                profileHash = profile?.profileHash ?: "",
                profileVersion = profile?.profileVersion ?: 1,
                lastUpdatedAt = profile?.lastUpdatedAt ?: 0L,
                avatarWebPB64 = avatarWebPB64
            )

            val jsonPayload = gson.toJson(backupDto).toByteArray(Charsets.UTF_8)
            val encryptedPayload = BackupCrypto.encryptBackup(jsonPayload, password)

            outputStream.use { out ->
                out.write(encryptedPayload)
                out.flush()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
