package com.torxone.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val TOR_ENABLED = booleanPreferencesKey("tor_enabled")
        val HIDE_ONLINE_STATUS = booleanPreferencesKey("hide_online_status")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val SHOW_TRANSPORT_ICONS = booleanPreferencesKey("show_transport_icons")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        val BLUETOOTH_SCANNING = booleanPreferencesKey("bluetooth_scanning")
        val WIFI_DIRECT_SCANNING = booleanPreferencesKey("wifi_direct_scanning")
        val BACKGROUND_SYNC_FREQUENCY = stringPreferencesKey("background_sync_frequency")
    }

    val torEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TOR_ENABLED] ?: true
    }

    val hideOnlineStatusFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HIDE_ONLINE_STATUS] ?: false
    }

    val reduceMotionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REDUCE_MOTION] ?: false
    }

    val showTransportIconsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_TRANSPORT_ICONS] ?: true
    }

    val darkModeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DARK_MODE] ?: true
    }

    val performanceModeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PERFORMANCE_MODE] ?: "balanced"
    }

    val bluetoothScanningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLUETOOTH_SCANNING] ?: true
    }

    val wifiDirectScanningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_DIRECT_SCANNING] ?: true
    }

    val backgroundSyncFrequencyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BACKGROUND_SYNC_FREQUENCY] ?: "normal"
    }

    suspend fun setTorEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[TOR_ENABLED] = enabled }
    }

    suspend fun setHideOnlineStatus(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[HIDE_ONLINE_STATUS] = enabled }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[REDUCE_MOTION] = enabled }
    }

    suspend fun setShowTransportIcons(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[SHOW_TRANSPORT_ICONS] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[DARK_MODE] = enabled }
    }

    suspend fun setPerformanceMode(mode: String) {
        context.dataStore.edit { preferences -> preferences[PERFORMANCE_MODE] = mode }
    }

    suspend fun setBluetoothScanning(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[BLUETOOTH_SCANNING] = enabled }
    }

    suspend fun setWifiDirectScanning(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[WIFI_DIRECT_SCANNING] = enabled }
    }

    suspend fun setBackgroundSyncFrequency(frequency: String) {
        context.dataStore.edit { preferences -> preferences[BACKGROUND_SYNC_FREQUENCY] = frequency }
    }
}
