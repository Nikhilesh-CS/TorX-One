package com.torxone.app.transport.tor

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

data class TorRoute(val onionHost: String, val port: Int = OnionEndpointManager.ONION_PORT) {
    init {
        require(onionHost.matches(Regex("[a-z2-7]{56}\\.onion"))) { "Invalid v3 onion address" }
        require(port in 1..65535)
    }
}

class TorRouteManager(context: Context? = null) {
    private val routes = ConcurrentHashMap<String, TorRoute>()
    private val preferences = context?.let {
        val key = MasterKey.Builder(it).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            it,
            "tor_routes_v1",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        preferences?.all?.forEach { (queue, value) ->
            val encoded = value as? String ?: return@forEach
            val separator = encoded.lastIndexOf(':')
            if (separator > 0) runCatching {
                routes[queue] = TorRoute(encoded.substring(0, separator), encoded.substring(separator + 1).toInt())
            }
        }
    }

    fun bind(queueAddress: String, route: TorRoute) {
        require(queueAddress.isNotBlank() && queueAddress.length <= 128)
        routes[queueAddress] = route
        preferences?.edit()?.putString(queueAddress, "${route.onionHost}:${route.port}")?.apply()
    }

    fun resolve(queueAddress: String): TorRoute? = routes[queueAddress]

    fun remove(queueAddress: String) {
        routes.remove(queueAddress)
        preferences?.edit()?.remove(queueAddress)?.apply()
    }
}
