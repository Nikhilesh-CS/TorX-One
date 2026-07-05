package com.astramesh.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
}
