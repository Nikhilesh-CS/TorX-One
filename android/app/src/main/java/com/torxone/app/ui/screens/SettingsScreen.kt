package com.torxone.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.torxone.app.transport.lora.RadioConnectionState
import com.torxone.app.transport.halow.HaLowConnectionState

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
    avatarUri: String? = null,
    about: String,
    // Privacy
    lastSeenVisible: Boolean,
    onlineVisible: Boolean,
    readReceiptsEnabled: Boolean,
    relayOnlyCalls: Boolean,
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
    radioState: RadioConnectionState,
    haLowState: HaLowConnectionState,
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
    onPairRadio: () -> Unit,
    onPairHaLow: () -> Unit,
    onAppearanceChange: (field: String, value: Any) -> Unit,
    onDataChange: (field: String, value: Boolean) -> Unit,
    onAboutClick: (() -> Unit)? = null,
    onSavedMessages: (() -> Unit)? = null,
    onSearchMessages: (() -> Unit)? = null,
    themeSource: String = "TORX",
    accentId: String = "SAGE",
    fontSize: String = "MEDIUM",
    onChatDefaultsClick: (() -> Unit)? = null,
    onUpdatesClick: (() -> Unit)? = null
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val back = { if (page == SettingsPage.ROOT) onBackClick() else page = SettingsPage.ROOT }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
    if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("TorX One") },
        text = { Text("Private messaging over Tor. Messages are end-to-end encrypted. Direct calls can expose your network address to your contact. Maximum Call Privacy requires a working TURN relay.") },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } })
    Scaffold(topBar = { TopAppBar(title = { Text(page.title) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        pages.SaveableStateProvider(page.name) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (page) {
                    SettingsPage.ROOT -> {
                        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable(onClick = onProfileClick).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            com.torxone.app.ui.components.ProfileAvatar(displayName, avatarUri, Modifier.size(56.dp))
                            Column(Modifier.weight(1f)) {
                                Text(displayName.ifEmpty { "Set up your profile" }, style = MaterialTheme.typography.titleMedium)
                                Text(about.ifBlank { "Profile and TorX identity" }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                        SettingsDivider()
                        SettingsPage.entries.filter { it != SettingsPage.ROOT }.forEach { destination ->
                            SettingsNavigationRow(destination.title, destination.subtitle, destination.icon) { page = destination }
                        }
                    }
                    SettingsPage.PRIVACY -> {
                        SettingsToggle("Last seen", if (lastSeenVisible) "Visible to contacts" else "Hidden", lastSeenVisible) { onPrivacyChange("lastSeen", it) }
                        SettingsToggle("Online status", if (onlineVisible) "Shown when active" else "Hidden", onlineVisible) { onPrivacyChange("online", it) }
                        SettingsToggle("Read receipts", "Let contacts know when you read messages", readReceiptsEnabled) { onPrivacyChange("readReceipts", it) }
                    }
                    SettingsPage.SECURITY -> {
                        SettingsToggle("App lock", "Require device authentication to open", appLockEnabled) { onSecurityChange("appLock", it) }
                        SettingsToggle("Screen security", "Block screenshots and hide content in recents", screenSecurityEnabled) { onSecurityChange("screenSecurity", it) }
                    }
                    SettingsPage.CHATS -> {
                        onSavedMessages?.let { SettingsNavigationRow("Starred messages", "Messages you want to keep handy", Icons.Default.Star, it) }
                        onSearchMessages?.let { SettingsNavigationRow("Search messages", "Find text, links and attachments", Icons.Default.Search, it) }
                        onChatDefaultsClick?.let { SettingsNavigationRow("Default chat theme", "Wallpaper and bubbles for your chats", Icons.Default.Wallpaper, it) }
                    }
                    SettingsPage.APPEARANCE -> {
                        SettingsThemeRow(themeMode) { onAppearanceChange("theme", it) }
                        com.torxone.app.ui.components.SettingsAppearanceControls(themeSource, accentId, fontSize,
                            onAppearanceChange, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        onChatDefaultsClick?.let { SettingsNavigationRow("Default chat theme", "Wallpaper and bubbles for your chats", Icons.Default.Wallpaper, it) }
                    }
                    SettingsPage.NOTIFICATIONS -> {
                        SettingsToggle("Message notifications", "Alerts for new messages", notificationsEnabled) { onNotificationChange("enabled", it) }
                        SettingsToggle("Sound", "Play a sound for new messages", soundEnabled) { onNotificationChange("sound", it) }
                        SettingsToggle("Vibration", "Vibrate for new messages", vibrationEnabled) { onNotificationChange("vibration", it) }
                        SettingsPreviewModeRow(notificationPreviewMode) { onNotificationChange("previewMode", it) }
                    }
                    SettingsPage.STORAGE -> SettingsToggle("Auto-download media", "Download incoming media automatically", autoDownloadMedia) { onDataChange("autoDownload", it) }
                    SettingsPage.CONNECTIONS -> {
                        SettingsToggle("Nearby connections", "Automatically discover nearby peers", autoConnectNearby) { onConnectionChange("autoNearby", it) }
                        SettingsToggle("Low bandwidth mode", "Reduce data usage", lowBandwidthMode) { onConnectionChange("lowBandwidth", it) }
                        SettingsDivider()
                        TorXRadioRow(radioState, onPairRadio)
                        HaLowGatewayRow(haLowState, onPairHaLow)
                    }
                    SettingsPage.CALLS -> SettingsToggle("Maximum Call Privacy",
                        if (relayOnlyCalls) "Calls and files use relay only. A working TURN relay is required. Applies to new sessions."
                        else "Direct calls and files can reveal your network address to your contact. Applies to new sessions.",
                        relayOnlyCalls) { onPrivacyChange("relayOnlyCalls", it) }
                    SettingsPage.ABOUT -> {
                        SettingsNavigationRow("TorX One", "Version ${com.torxone.app.BuildConfig.VERSION_NAME} (${com.torxone.app.BuildConfig.VERSION_CODE})", Icons.Default.Info) {
                            onAboutClick?.invoke() ?: run { showAbout = true }
                        }
                        onUpdatesClick?.let { updates -> SettingsNavigationRow("App updates", "Stable releases from GitHub", Icons.Default.SystemUpdate, updates) }
                    }
                }
            }
        }
    }
}

private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    ROOT("Settings", "", Icons.Default.Settings),
    PRIVACY("Privacy", "Visibility and read receipts", Icons.Default.Lock),
    SECURITY("Security", "App lock and screen protection", Icons.Default.Security),
    CHATS("Chats", "Starred messages and search", Icons.Default.Chat),
    APPEARANCE("Appearance", "Theme, accent and text size", Icons.Default.Palette),
    NOTIFICATIONS("Notifications", "Sounds and message previews", Icons.Default.Notifications),
    STORAGE("Storage & data", "Media downloads", Icons.Default.Storage),
    CONNECTIONS("Connections", "Nearby and external hardware", Icons.Default.WifiTethering),
    CALLS("Calls", "Privacy for calls and files", Icons.Default.Call),
    ABOUT("About", "Version and privacy information", Icons.Default.Info)
}

@Composable
private fun SettingsNavigationRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = "Open $title", onClick = onClick))
}

@Composable
private fun TorXRadioRow(radioState: RadioConnectionState, onPairRadio: () -> Unit) {
    val (title, subtitle) = when (radioState) {
        RadioConnectionState.Idle -> "TorX Radio" to "Radio discovery is stopped"
        RadioConnectionState.Scanning -> "TorX Radio" to "Looking for TorX hardware…"
        is RadioConnectionState.Recognized -> "TorX Radio found" to (radioState.candidate.displayName ?: radioState.candidate.stableId)
        is RadioConnectionState.Connecting -> "Connecting to TorX Radio" to (radioState.candidate.displayName ?: radioState.candidate.stableId)
        is RadioConnectionState.NeedsPairing -> "Pair TorX Radio" to "${radioState.capabilities.deviceId} · ${radioState.capabilities.board} · ${radioState.capabilities.region}"
        is RadioConnectionState.Ready -> "TorX Radio connected" to "${radioState.capabilities.deviceId} · ${radioState.capabilities.radioChip} · ${radioState.capabilities.region}"
        is RadioConnectionState.Failed -> "TorX Radio unavailable" to radioState.reason
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CellTower, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (radioState is RadioConnectionState.NeedsPairing) {
            Button(onClick = onPairRadio) { Text("Pair") }
        }
    }
}

@Composable
private fun HaLowGatewayRow(haLowState: HaLowConnectionState, onPairHaLow: () -> Unit) {
    val (title, subtitle) = when (haLowState) {
        HaLowConnectionState.Idle -> "Wi-Fi HaLow gateway" to "Gateway discovery is stopped"
        HaLowConnectionState.Discovering -> "Wi-Fi HaLow gateway" to "Looking for TorX gateways…"
        is HaLowConnectionState.Connecting -> "Connecting to HaLow gateway" to haLowState.candidate.serviceName
        is HaLowConnectionState.NeedsPairing -> "Pair HaLow gateway" to "${haLowState.capabilities.gatewayId} · ${haLowState.capabilities.chipset} · ${haLowState.capabilities.region}"
        is HaLowConnectionState.Ready -> "HaLow gateway connected" to "${haLowState.capabilities.gatewayId} · ${haLowState.capabilities.chipset} · ${haLowState.capabilities.region}"
        is HaLowConnectionState.Failed -> "HaLow gateway unavailable" to haLowState.reason
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        if (haLowState is HaLowConnectionState.NeedsPairing) {
            Button(onClick = onPairHaLow) { Text("Pair") }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Reusable settings sub-components
// ═════════════════════════════════════════════════════════════════════

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
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
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

@OptIn(ExperimentalLayoutApi::class)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            modes.forEach { (value, label) ->
                FilterChip(
                    selected = currentMode == value,
                    onClick = { onModeChange(value) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            themes.forEach { (value, label) ->
                FilterChip(
                    selected = currentTheme == value,
                    onClick = { onThemeChange(value) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}
