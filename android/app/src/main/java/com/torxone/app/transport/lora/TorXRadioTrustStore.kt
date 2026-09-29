package com.torxone.app.transport.lora

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/** Stores public-key fingerprints; private radio identity material never enters the app. */
class TorXRadioTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("torx_radio_trust_v1", Context.MODE_PRIVATE)

    fun isTrusted(deviceId: String, identityKey: ByteArray): Boolean =
        preferences.getString(deviceId, null) == fingerprint(identityKey)

    fun trust(deviceId: String, identityKey: ByteArray) {
        require(deviceId.isNotBlank() && identityKey.size == 32)
        preferences.edit().putString(deviceId, fingerprint(identityKey)).apply()
    }

    fun forget(deviceId: String) { preferences.edit().remove(deviceId).apply() }

    fun fingerprint(identityKey: ByteArray): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(identityKey), Base64.NO_WRAP or Base64.URL_SAFE
    )
}
