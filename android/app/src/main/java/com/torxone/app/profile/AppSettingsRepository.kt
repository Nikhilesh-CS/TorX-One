package com.torxone.app.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Central settings & profile state layer using Jetpack DataStore.
 *
 * Room handles relational/chat data.
 * DataStore handles user preferences, privacy toggles, and profile metadata.
 */

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "torx_app_settings"
)

class AppSettingsRepository(context: Context) {

    private val store = context.settingsDataStore

    // ─── Profile Keys ────────────────────────────────────────────────
    private object ProfileKeys {
        val DISPLAY_NAME = stringPreferencesKey("profile_display_name")
        val ABOUT = stringPreferencesKey("profile_about")
        val AVATAR_URI = stringPreferencesKey("profile_avatar_uri")
        val PROFILE_VERSION = intPreferencesKey("profile_version")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }

    // ─── Privacy Keys ────────────────────────────────────────────────
    private object PrivacyKeys {
        val LAST_SEEN_VISIBLE = booleanPreferencesKey("privacy_last_seen_visible")
        val ONLINE_VISIBLE = booleanPreferencesKey("privacy_online_visible")
        val READ_RECEIPTS_ENABLED = booleanPreferencesKey("privacy_read_receipts_enabled")
        val PROFILE_PHOTO_VISIBILITY = stringPreferencesKey("privacy_profile_photo_visibility")
        val ABOUT_VISIBILITY = stringPreferencesKey("privacy_about_visibility")
    }

    // ─── Notification Keys ───────────────────────────────────────────
    private object NotificationKeys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notif_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("notif_sound_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("notif_vibration_enabled")
        val PREVIEW_MODE = stringPreferencesKey("notif_preview_mode") // FULL | SENDER_ONLY | HIDDEN
    }

    // ─── Appearance Keys ─────────────────────────────────────────────
    private object AppearanceKeys {
        val THEME_MODE = stringPreferencesKey("appearance_theme") // SYSTEM | DARK | LIGHT
        val DYNAMIC_COLORS = booleanPreferencesKey("appearance_dynamic_colors")
        val FONT_SIZE = stringPreferencesKey("appearance_font_size") // SMALL | MEDIUM | LARGE
    }

    // ─── Security Keys ──────────────────────────────────────────────
    private object SecurityKeys {
        val APP_LOCK_ENABLED = booleanPreferencesKey("security_app_lock")
        val APP_LOCK_TIMEOUT = longPreferencesKey("security_app_lock_timeout")
        val SCREEN_SECURITY = booleanPreferencesKey("security_screen_security")
    }

    // ─── Connection Keys ─────────────────────────────────────────────
    private object ConnectionKeys {
        val AUTO_CONNECT_NEARBY = booleanPreferencesKey("conn_auto_nearby")
        val LOW_BANDWIDTH_MODE = booleanPreferencesKey("conn_low_bandwidth")
    }

    // ─── Data & Storage Keys ─────────────────────────────────────────
    private object DataKeys {
        val AUTO_DOWNLOAD_MEDIA = booleanPreferencesKey("data_auto_download")
    }

    // =========================================================================
    //  Onboarding
    // =========================================================================

    val isOnboardingComplete: Flow<Boolean> = store.data.map { prefs ->
        prefs[ProfileKeys.ONBOARDING_COMPLETE] ?: false
    }

    suspend fun completeOnboarding() {
        store.edit { prefs ->
            prefs[ProfileKeys.ONBOARDING_COMPLETE] = true
        }
    }

    suspend fun isOnboardingDone(): Boolean {
        return store.data.first()[ProfileKeys.ONBOARDING_COMPLETE] ?: false
    }

    // =========================================================================
    //  Profile
    // =========================================================================

    val displayName: Flow<String> = store.data.map { prefs ->
        prefs[ProfileKeys.DISPLAY_NAME] ?: ""
    }

    val about: Flow<String> = store.data.map { prefs ->
        prefs[ProfileKeys.ABOUT] ?: ""
    }

    val avatarUri: Flow<String?> = store.data.map { prefs ->
        prefs[ProfileKeys.AVATAR_URI]
    }

    val profileVersion: Flow<Int> = store.data.map { prefs ->
        prefs[ProfileKeys.PROFILE_VERSION] ?: 1
    }

    suspend fun updateProfile(displayName: String, about: String = "", avatarUri: String? = null) {
        store.edit { prefs ->
            prefs[ProfileKeys.DISPLAY_NAME] = displayName
            prefs[ProfileKeys.ABOUT] = about
            if (avatarUri != null) {
                prefs[ProfileKeys.AVATAR_URI] = avatarUri
            } else {
                prefs.remove(ProfileKeys.AVATAR_URI)
            }
            prefs[ProfileKeys.PROFILE_VERSION] = (prefs[ProfileKeys.PROFILE_VERSION] ?: 0) + 1
        }
    }

    suspend fun getDisplayName(): String {
        return store.data.first()[ProfileKeys.DISPLAY_NAME] ?: ""
    }

    // =========================================================================
    //  Privacy
    // =========================================================================

    val lastSeenVisible: Flow<Boolean> = store.data.map { prefs ->
        prefs[PrivacyKeys.LAST_SEEN_VISIBLE] ?: true
    }

    val onlineVisible: Flow<Boolean> = store.data.map { prefs ->
        prefs[PrivacyKeys.ONLINE_VISIBLE] ?: true
    }

    val readReceiptsEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[PrivacyKeys.READ_RECEIPTS_ENABLED] ?: true
    }

    val profilePhotoVisibility: Flow<String> = store.data.map { prefs ->
        prefs[PrivacyKeys.PROFILE_PHOTO_VISIBILITY] ?: "EVERYONE"
    }

    val aboutVisibility: Flow<String> = store.data.map { prefs ->
        prefs[PrivacyKeys.ABOUT_VISIBILITY] ?: "EVERYONE"
    }

    suspend fun setLastSeenVisible(visible: Boolean) {
        store.edit { it[PrivacyKeys.LAST_SEEN_VISIBLE] = visible }
    }

    suspend fun setOnlineVisible(visible: Boolean) {
        store.edit { it[PrivacyKeys.ONLINE_VISIBLE] = visible }
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean) {
        store.edit { it[PrivacyKeys.READ_RECEIPTS_ENABLED] = enabled }
    }

    suspend fun setProfilePhotoVisibility(visibility: String) {
        store.edit { it[PrivacyKeys.PROFILE_PHOTO_VISIBILITY] = visibility }
    }

    suspend fun setAboutVisibility(visibility: String) {
        store.edit { it[PrivacyKeys.ABOUT_VISIBILITY] = visibility }
    }

    // =========================================================================
    //  Notifications
    // =========================================================================

    val notificationsEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[NotificationKeys.NOTIFICATIONS_ENABLED] ?: true
    }

    val soundEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[NotificationKeys.SOUND_ENABLED] ?: true
    }

    val vibrationEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[NotificationKeys.VIBRATION_ENABLED] ?: true
    }

    val notificationPreviewMode: Flow<String> = store.data.map { prefs ->
        prefs[NotificationKeys.PREVIEW_MODE] ?: "FULL"
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.edit { it[NotificationKeys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        store.edit { it[NotificationKeys.SOUND_ENABLED] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        store.edit { it[NotificationKeys.VIBRATION_ENABLED] = enabled }
    }

    suspend fun setNotificationPreviewMode(mode: String) {
        store.edit { it[NotificationKeys.PREVIEW_MODE] = mode }
    }

    // =========================================================================
    //  Appearance
    // =========================================================================

    val themeMode: Flow<String> = store.data.map { prefs ->
        prefs[AppearanceKeys.THEME_MODE] ?: "SYSTEM"
    }

    val dynamicColorsEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[AppearanceKeys.DYNAMIC_COLORS] ?: true
    }

    val fontSize: Flow<String> = store.data.map { prefs ->
        prefs[AppearanceKeys.FONT_SIZE] ?: "MEDIUM"
    }

    suspend fun setThemeMode(mode: String) {
        store.edit { it[AppearanceKeys.THEME_MODE] = mode }
    }

    suspend fun setDynamicColorsEnabled(enabled: Boolean) {
        store.edit { it[AppearanceKeys.DYNAMIC_COLORS] = enabled }
    }

    suspend fun setFontSize(size: String) {
        store.edit { it[AppearanceKeys.FONT_SIZE] = size }
    }

    // =========================================================================
    //  Security
    // =========================================================================

    val appLockEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[SecurityKeys.APP_LOCK_ENABLED] ?: false
    }

    val appLockTimeout: Flow<Long> = store.data.map { prefs ->
        prefs[SecurityKeys.APP_LOCK_TIMEOUT] ?: 0L
    }

    val screenSecurityEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[SecurityKeys.SCREEN_SECURITY] ?: false
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        store.edit { it[SecurityKeys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setAppLockTimeout(timeoutMs: Long) {
        store.edit { it[SecurityKeys.APP_LOCK_TIMEOUT] = timeoutMs }
    }

    suspend fun setScreenSecurityEnabled(enabled: Boolean) {
        store.edit { it[SecurityKeys.SCREEN_SECURITY] = enabled }
    }

    // =========================================================================
    //  Connection
    // =========================================================================

    val autoConnectNearby: Flow<Boolean> = store.data.map { prefs ->
        prefs[ConnectionKeys.AUTO_CONNECT_NEARBY] ?: true
    }

    val lowBandwidthMode: Flow<Boolean> = store.data.map { prefs ->
        prefs[ConnectionKeys.LOW_BANDWIDTH_MODE] ?: false
    }

    suspend fun setAutoConnectNearby(enabled: Boolean) {
        store.edit { it[ConnectionKeys.AUTO_CONNECT_NEARBY] = enabled }
    }

    suspend fun setLowBandwidthMode(enabled: Boolean) {
        store.edit { it[ConnectionKeys.LOW_BANDWIDTH_MODE] = enabled }
    }

    suspend fun isLowBandwidthMode(): Boolean {
        return store.data.first()[ConnectionKeys.LOW_BANDWIDTH_MODE] ?: false
    }

    // =========================================================================
    //  Data & Storage
    // =========================================================================

    val autoDownloadMedia: Flow<Boolean> = store.data.map { prefs ->
        prefs[DataKeys.AUTO_DOWNLOAD_MEDIA] ?: true
    }

    suspend fun setAutoDownloadMedia(enabled: Boolean) {
        store.edit { it[DataKeys.AUTO_DOWNLOAD_MEDIA] = enabled }
    }
}
