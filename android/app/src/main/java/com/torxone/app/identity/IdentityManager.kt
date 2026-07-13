package com.torxone.app.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.google.gson.Gson

class IdentityManager(context: Context) {
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
        val encSecHex = sharedPreferences.getString("enc_sec", null) ?: return null
        val sigPubHex = sharedPreferences.getString("sig_pub", null) ?: return null
        val sigSecHex = sharedPreferences.getString("sig_sec", null) ?: return null
        
        return Identity(
            name = name,
            encryptionPublicKey = CryptoManager.fromHex(encPubHex),
            encryptionSecretKey = CryptoManager.fromHex(encSecHex),
            signingPublicKey = CryptoManager.fromHex(sigPubHex),
            signingSecretKey = CryptoManager.fromHex(sigSecHex)
        )
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
