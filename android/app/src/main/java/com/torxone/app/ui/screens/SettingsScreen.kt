package com.torxone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings screen — full control center for the app.
 *
 * Sections:
 *   Profile, Privacy, Notifications, Security,
 *   Connection, Data & Storage, Appearance, About
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String,
    about: String,
    // Privacy
    lastSeenVisible: Boolean,
    onlineVisible: Boolean,
    readReceiptsEnabled: Boolean,
    // Notifications
    notificationsEnabled: Boolean,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    notificationPreviewMode: String,
    // Security
    appLockEnabled: Boolean,
    screenSecurityEnabled: Boolean,
    // Connection
    autoConnectNearby: Boolean,
    lowBandwidthMode: Boolean,
    // Appearance
    themeMode: String,
    dynamicColorsEnabled: Boolean,
    // Data
    autoDownloadMedia: Boolean,
    // Callbacks
    onBackClick: () -> Unit,
    onProfileClick: () -> Unit,
    onPrivacyChange: (field: String, value: Boolean) -> Unit,
    onNotificationChange: (field: String, value: Any) -> Unit,
    onSecurityChange: (field: String, value: Boolean) -> Unit,
    onConnectionChange: (field: String, value: Boolean) -> Unit,
    onAppearanceChange: (field: String, value: Any) -> Unit,
    onDataChange: (field: String, value: Boolean) -> Unit,
    onAboutClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ─── Profile Banner ──────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(onClick = onProfileClick),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayName.take(2).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName.ifEmpty { "Set up your profile" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (about.isNotEmpty()) {
                            Text(
                                text = about,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }

            // ─── Privacy ─────────────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Lock,
                title = "Privacy",
                color = MaterialTheme.colorScheme.primary
            )

            SettingsToggle(
                title = "Last Seen & Online",
                subtitle = if (lastSeenVisible) "Visible to contacts" else "Hidden",
                checked = lastSeenVisible,
                onCheckedChange = { onPrivacyChange("lastSeen", it) }
            )

            SettingsToggle(
                title = "Online Status",
                subtitle = if (onlineVisible) "Shown when active" else "Hidden",
                checked = onlineVisible,
                onCheckedChange = { onPrivacyChange("online", it) }
            )

            SettingsToggle(
                title = "Read Receipts",
                subtitle = if (readReceiptsEnabled) "Contacts see when you read" else "Disabled",
                checked = readReceiptsEnabled,
                onCheckedChange = { onPrivacyChange("readReceipts", it) }
            )

            SettingsDivider()

            // ─── Notifications ───────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                color = MaterialTheme.colorScheme.secondary
            )

            SettingsToggle(
                title = "Message Notifications",
                subtitle = if (notificationsEnabled) "On" else "Off",
                checked = notificationsEnabled,
                onCheckedChange = { onNotificationChange("enabled", it) }
            )

            SettingsToggle(
                title = "Sound",
                subtitle = if (soundEnabled) "On" else "Off",
                checked = soundEnabled,
                onCheckedChange = { onNotificationChange("sound", it) }
            )

            SettingsToggle(
                title = "Vibration",
                subtitle = if (vibrationEnabled) "On" else "Off",
                checked = vibrationEnabled,
                onCheckedChange = { onNotificationChange("vibration", it) }
            )

            // Preview mode selector
            SettingsPreviewModeRow(
                currentMode = notificationPreviewMode,
                onModeChange = { onNotificationChange("previewMode", it) }
            )

            SettingsDivider()

            // ─── Security ────────────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Security,
                title = "Security",
                color = MaterialTheme.colorScheme.tertiary
            )

            SettingsToggle(
                title = "App Lock",
                subtitle = if (appLockEnabled) "Require biometric to open" else "Disabled",
                checked = appLockEnabled,
                onCheckedChange = { onSecurityChange("appLock", it) }
            )

            SettingsToggle(
                title = "Screen Security",
                subtitle = if (screenSecurityEnabled) "Block screenshots & recents" else "Off",
                checked = screenSecurityEnabled,
                onCheckedChange = { onSecurityChange("screenSecurity", it) }
            )

            SettingsDivider()

            // ─── Connection ──────────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.WifiTethering,
                title = "Connection",
                color = MaterialTheme.colorScheme.primary
            )

            SettingsToggle(
                title = "Auto-Connect Nearby",
                subtitle = if (autoConnectNearby) "Automatically discover nearby peers" else "Manual only",
                checked = autoConnectNearby,
                onCheckedChange = { onConnectionChange("autoNearby", it) }
            )

            SettingsToggle(
                title = "Low Bandwidth Mode",
                subtitle = if (lowBandwidthMode) "Reduced data usage" else "Normal",
                checked = lowBandwidthMode,
                onCheckedChange = { onConnectionChange("lowBandwidth", it) }
            )

            SettingsDivider()

            // ─── Appearance ──────────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Palette,
                title = "Appearance",
                color = MaterialTheme.colorScheme.secondary
            )

            SettingsThemeRow(
                currentTheme = themeMode,
                onThemeChange = { onAppearanceChange("theme", it) }
            )

            SettingsToggle(
                title = "Dynamic Colors",
                subtitle = if (dynamicColorsEnabled) "Material You colors from wallpaper" else "Use default palette",
                checked = dynamicColorsEnabled,
                onCheckedChange = { onAppearanceChange("dynamicColors", it) }
            )

            SettingsDivider()

            // ─── Data & Storage ──────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Storage,
                title = "Data & Storage",
                color = MaterialTheme.colorScheme.error
            )

            SettingsToggle(
                title = "Auto-Download Media",
                subtitle = if (autoDownloadMedia) "Download media automatically" else "Tap to download",
                checked = autoDownloadMedia,
                onCheckedChange = { onDataChange("autoDownload", it) }
            )

            SettingsDivider()

            // ─── About ──────────────────────────────────────────────
            SettingsSectionHeader(
                icon = Icons.Filled.Info,
                title = "About",
                color = MaterialTheme.colorScheme.outline
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAboutClick)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = Color.Transparent
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "TorX One",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Version 0.1.0 · Privacy-first mesh messenger",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Reusable settings sub-components
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}

@Composable
private fun SettingsPreviewModeRow(
    currentMode: String,
    onModeChange: (String) -> Unit
) {
    val modes = listOf("FULL" to "Full", "SENDER_ONLY" to "Sender Only", "HIDDEN" to "Hidden")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Notification Preview",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Content shown in notification",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEach { (value, label) ->
                FilterChip(
                    selected = currentMode == value,
                    onClick = { onModeChange(value) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsThemeRow(
    currentTheme: String,
    onThemeChange: (String) -> Unit
) {
    val themes = listOf("SYSTEM" to "System", "DARK" to "Dark", "LIGHT" to "Light")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            themes.forEach { (value, label) ->
                FilterChip(
                    selected = currentTheme == value,
                    onClick = { onThemeChange(value) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}
