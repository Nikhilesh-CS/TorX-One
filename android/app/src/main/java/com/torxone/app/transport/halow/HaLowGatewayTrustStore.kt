package com.torxone.app.transport.halow

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

class HaLowGatewayTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences("torx_halow_gateway_trust_v1", Context.MODE_PRIVATE)
    fun isTrusted(id: String, key: ByteArray): Boolean = preferences.getString(id, null) == fingerprint(key)
    fun trust(id: String, key: ByteArray) { require(id.isNotBlank() && key.size == 32); preferences.edit().putString(id, fingerprint(key)).apply() }
    private fun fingerprint(key: ByteArray) = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.URL_SAFE
    )
}
