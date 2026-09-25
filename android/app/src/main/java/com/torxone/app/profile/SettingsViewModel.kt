package com.torxone.app.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 *
 * Collects all DataStore flows into a single [SettingsUiState] for the UI.
 * All writes go through [AppSettingsRepository] — no direct Room mutation.
 */
class SettingsViewModel(
    private val settingsRepo: AppSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepo.displayName,
        settingsRepo.about,
        settingsRepo.lastSeenVisible,
        settingsRepo.onlineVisible,
        settingsRepo.readReceiptsEnabled
    ) { name, about, lastSeen, online, readReceipts ->
        PartialState1(name, about, lastSeen, online, readReceipts)
    }.combine(
        combine(
            settingsRepo.notificationsEnabled,
            settingsRepo.soundEnabled,
            settingsRepo.vibrationEnabled,
            settingsRepo.notificationPreviewMode
        ) { notifs, sound, vibration, preview ->
            PartialState2(notifs, sound, vibration, preview)
        }
    ) { p1, p2 ->
        Pair(p1, p2)
    }.combine(
        combine(
            settingsRepo.appLockEnabled,
            settingsRepo.screenSecurityEnabled,
            settingsRepo.autoConnectNearby,
            settingsRepo.lowBandwidthMode
        ) { appLock, screenSec, autoNearby, lowBw ->
            PartialState3(appLock, screenSec, autoNearby, lowBw)
        }
    ) { (p1, p2), p3 ->
        Triple(p1, p2, p3)
    }.combine(
        combine(
            settingsRepo.themeMode,
            settingsRepo.dynamicColorsEnabled,
            settingsRepo.autoDownloadMedia
        ) { theme, dynamic, autoDownload ->
            PartialState4(theme, dynamic, autoDownload)
        }
    ) { (p1, p2, p3), p4 ->
        SettingsUiState(
            displayName = p1.displayName,
            about = p1.about,
            lastSeenVisible = p1.lastSeenVisible,
            onlineVisible = p1.onlineVisible,
            readReceiptsEnabled = p1.readReceiptsEnabled,
            notificationsEnabled = p2.notificationsEnabled,
            soundEnabled = p2.soundEnabled,
            vibrationEnabled = p2.vibrationEnabled,
            notificationPreviewMode = p2.notificationPreviewMode,
            appLockEnabled = p3.appLockEnabled,
            screenSecurityEnabled = p3.screenSecurityEnabled,
            autoConnectNearby = p3.autoConnectNearby,
            lowBandwidthMode = p3.lowBandwidthMode,
            themeMode = p4.themeMode,
            dynamicColorsEnabled = p4.dynamicColorsEnabled,
            autoDownloadMedia = p4.autoDownloadMedia
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // ─── Privacy ─────────────────────────────────────────────────────
    fun setPrivacy(field: String, value: Boolean) {
        scope.launch {
            when (field) {
                "lastSeen" -> settingsRepo.setLastSeenVisible(value)
                "online" -> settingsRepo.setOnlineVisible(value)
                "readReceipts" -> settingsRepo.setReadReceiptsEnabled(value)
            }
        }
    }

    // ─── Notifications ───────────────────────────────────────────────
    fun setNotification(field: String, value: Any) {
        scope.launch {
            when (field) {
                "enabled" -> settingsRepo.setNotificationsEnabled(value as Boolean)
                "sound" -> settingsRepo.setSoundEnabled(value as Boolean)
                "vibration" -> settingsRepo.setVibrationEnabled(value as Boolean)
                "previewMode" -> settingsRepo.setNotificationPreviewMode(value as String)
            }
        }
    }

    // ─── Security ────────────────────────────────────────────────────
    fun setSecurity(field: String, value: Boolean) {
        scope.launch {
            when (field) {
                "appLock" -> settingsRepo.setAppLockEnabled(value)
                "screenSecurity" -> settingsRepo.setScreenSecurityEnabled(value)
            }
        }
    }

    // ─── Connection ──────────────────────────────────────────────────
    fun setConnection(field: String, value: Boolean) {
        scope.launch {
            when (field) {
                "autoNearby" -> settingsRepo.setAutoConnectNearby(value)
                "lowBandwidth" -> settingsRepo.setLowBandwidthMode(value)
            }
        }
    }

    // ─── Appearance ──────────────────────────────────────────────────
    fun setAppearance(field: String, value: Any) {
        scope.launch {
            when (field) {
                "theme" -> settingsRepo.setThemeMode(value as String)
                "dynamicColors" -> settingsRepo.setDynamicColorsEnabled(value as Boolean)
            }
        }
    }

    // ─── Data ────────────────────────────────────────────────────────
    fun setData(field: String, value: Boolean) {
        scope.launch {
            when (field) {
                "autoDownload" -> settingsRepo.setAutoDownloadMedia(value)
            }
        }
    }

    // ─── Profile Update ──────────────────────────────────────────────
    fun updateProfile(name: String, about: String) {
        scope.launch {
            settingsRepo.updateProfile(displayName = name, about = about)
        }
    }
}

data class SettingsUiState(
    val displayName: String = "",
    val about: String = "",
    val lastSeenVisible: Boolean = true,
    val onlineVisible: Boolean = true,
    val readReceiptsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val notificationPreviewMode: String = "FULL",
    val appLockEnabled: Boolean = false,
    val screenSecurityEnabled: Boolean = false,
    val autoConnectNearby: Boolean = true,
    val lowBandwidthMode: Boolean = false,
    val themeMode: String = "SYSTEM",
    val dynamicColorsEnabled: Boolean = true,
    val autoDownloadMedia: Boolean = true
)

// Internal grouping classes for combine()
private data class PartialState1(
    val displayName: String,
    val about: String,
    val lastSeenVisible: Boolean,
    val onlineVisible: Boolean,
    val readReceiptsEnabled: Boolean
)

private data class PartialState2(
    val notificationsEnabled: Boolean,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val notificationPreviewMode: String
)

private data class PartialState3(
    val appLockEnabled: Boolean,
    val screenSecurityEnabled: Boolean,
    val autoConnectNearby: Boolean,
    val lowBandwidthMode: Boolean
)

private data class PartialState4(
    val themeMode: String,
    val dynamicColorsEnabled: Boolean,
    val autoDownloadMedia: Boolean
)
