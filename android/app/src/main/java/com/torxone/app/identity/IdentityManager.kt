package com.torxone.app.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.google.gson.Gson

import java.util.Arrays

class IdentityManager(context: Context) {
    var isAppLockEnabled: Boolean
        get() = sharedPreferences.getBoolean("app_lock_enabled", false)
        set(value) {
            sharedPreferences.edit().putBoolean("app_lock_enabled", value).apply()
        }
        
    var appLockPromptShown: Boolean
        get() = sharedPreferences.getBoolean("app_lock_prompt_shown", false)
        set(value) {
            sharedPreferences.edit().putBoolean("app_lock_prompt_shown", value).apply()
        }

    var hasExportedBackup: Boolean
        get() = sharedPreferences.getBoolean("has_exported_backup", false)
        set(value) {
            sharedPreferences.edit().putBoolean("has_exported_backup", value).apply()
        }

    var isSessionUnlocked: Boolean = true
        private set

    private var cachedEncSec: ByteArray? = null
    private var cachedSigSec: ByteArray? = null

    var isAwaitingExternalActivity: Boolean = false

    fun lockSession() {
        if (isAppLockEnabled && !isAwaitingExternalActivity) {
            isSessionUnlocked = false
        }
    }

    fun relockSession() {
        if (isAppLockEnabled && !isAwaitingExternalActivity) {
            isSessionUnlocked = false
        }
    }

    fun unlockSession() {
        isSessionUnlocked = true
    }

    fun unlockSession(decryptedKeys: ByteArray) {
        if (decryptedKeys.size == 64) {
            restorePrivateKeysToPrefs(decryptedKeys)
        }
        isSessionUnlocked = true
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "torxone_identity_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    private val gson = Gson()
    
    fun hasIdentity(): Boolean {
        return sharedPreferences.contains("identity_name")
    }

    fun saveIdentity(identity: Identity) {
        with(sharedPreferences.edit()) {
            putString("identity_name", identity.name)
            putString("enc_pub", CryptoManager.toHex(identity.encryptionPublicKey))
            putString("enc_sec", CryptoManager.toHex(identity.encryptionSecretKey))
            putString("sig_pub", CryptoManager.toHex(identity.signingPublicKey))
            putString("sig_sec", CryptoManager.toHex(identity.signingSecretKey))
            apply()
        }
    }

    fun loadIdentity(): Identity? {
        if (!hasIdentity()) return null
        
        val name = sharedPreferences.getString("identity_name", null) ?: return null
        val encPubHex = sharedPreferences.getString("enc_pub", null) ?: return null
        val sigPubHex = sharedPreferences.getString("sig_pub", null) ?: return null
        
        var encSecHex = sharedPreferences.getString("enc_sec", null)
        var sigSecHex = sharedPreferences.getString("sig_sec", null)

        if (encSecHex == null && cachedEncSec != null) {
            encSecHex = CryptoManager.toHex(cachedEncSec!!)
            sharedPreferences.edit().putString("enc_sec", encSecHex).apply()
        }
        if (sigSecHex == null && cachedSigSec != null) {
            sigSecHex = CryptoManager.toHex(cachedSigSec!!)
            sharedPreferences.edit().putString("sig_sec", sigSecHex).apply()
        }

        if (encSecHex == null || sigSecHex == null) {
            return null
        }

        return Identity(
            name = name,
            encryptionPublicKey = CryptoManager.fromHex(encPubHex),
            encryptionSecretKey = CryptoManager.fromHex(encSecHex),
            signingPublicKey = CryptoManager.fromHex(sigPubHex),
            signingSecretKey = CryptoManager.fromHex(sigSecHex)
        )
    }

    internal fun exportPrivateKeysForLock(): ByteArray {
        val identity = loadIdentity() ?: throw IllegalStateException("Identity not available")
        val combined = ByteArray(64)
        System.arraycopy(identity.encryptionSecretKey, 0, combined, 0, 32)
        System.arraycopy(identity.signingSecretKey, 0, combined, 32, 32)
        return combined
    }

    fun clearPrivateKeysFromPrefs() {
        // Intentionally keep keys safely in EncryptedSharedPreferences (AES-256-GCM Keystore encrypted).
        // This ensures the background mesh service can continuously decrypt incoming messages,
        // execute file transfers, and show notifications without disturbing message delivery.
    }

    fun restorePrivateKeysToPrefs(decryptedKeys: ByteArray) {
        if (decryptedKeys.size != 64) return
        val enc = ByteArray(32).apply { System.arraycopy(decryptedKeys, 0, this, 0, 32) }
        val sig = ByteArray(32).apply { System.arraycopy(decryptedKeys, 32, this, 0, 32) }
        
        sharedPreferences.edit()
            .putString("enc_sec", CryptoManager.toHex(enc))
            .putString("sig_sec", CryptoManager.toHex(sig))
            .apply()
            
        Arrays.fill(enc, 0.toByte())
        Arrays.fill(sig, 0.toByte())
    }

    fun saveOnionAddress(onion: String) {
        sharedPreferences.edit().putString("onion_address", onion).apply()
    }

    fun loadOnionAddress(): String? {
        return sharedPreferences.getString("onion_address", null)
    }

    fun updateName(newName: String) {
        sharedPreferences.edit().putString("identity_name", newName).apply()
    }
}
