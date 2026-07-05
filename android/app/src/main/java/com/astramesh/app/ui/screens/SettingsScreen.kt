package com.astramesh.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.ExportManager
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.ui.components.AstraAvatar
import com.astramesh.app.ui.components.UpdateDialog
import com.astramesh.app.ui.theme.*
import com.astramesh.app.updater.GitHubUpdater
import com.astramesh.app.updater.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

import com.astramesh.app.data.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    identityManager: IdentityManager,
    navController: NavController,
    onionAddress: String,
    db: AppDatabase,
    settingsManager: SettingsManager
) {
    val context = LocalContext.current
    var identity by remember { mutableStateOf(identityManager.loadIdentity()) }
    val scope = rememberCoroutineScope()

    val torModeEnabled by settingsManager.torEnabledFlow.collectAsState(initial = true)
    val hideOnlineStatus by settingsManager.hideOnlineStatusFlow.collectAsState(initial = false)
    val reduceMotion by settingsManager.reduceMotionFlow.collectAsState(initial = false)
    val showTransportIcons by settingsManager.showTransportIconsFlow.collectAsState(initial = true)
    val darkMode by settingsManager.darkModeFlow.collectAsState(initial = true)
    
    // Dialog States
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showClearChatsDialog by remember { mutableStateOf(false) }
    var showOnionDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    // Update States
    val updater = remember { GitHubUpdater(context) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }

    // State for Storage
    var cacheSize by remember { mutableStateOf("Calculating...") }
    var dbSize by remember { mutableStateOf("Calculating...") }

    fun calculateSizes() {
        scope.launch(Dispatchers.IO) {
            val cacheFolder = context.cacheDir
            val cacheSizeVal = cacheFolder.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val dbFolder = context.getDatabasePath("astramesh_db").parentFile
            val dbSizeVal = dbFolder?.walkTopDown()?.filter { it.isFile }?.map { it.length() }?.sum() ?: 0L

            withContext(Dispatchers.Main) {
                cacheSize = "${cacheSizeVal / (1024 * 1024)} MB"
                dbSize = "${dbSizeVal / (1024 * 1024)} MB"
            }
        }
    }

    LaunchedEffect(Unit) {
        calculateSizes()
    }

    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // Update Dialog
    updateInfo?.let { info ->
        UpdateDialog(
            updateInfo = info,
            isDownloading = isDownloadingUpdate,
            onConfirm = {
                isDownloadingUpdate = true
                updater.downloadAndInstallUpdate(
                    updateInfo = info,
                    onProgress = { },
                    onComplete = { isDownloadingUpdate = false; updateInfo = null },
                    onError = { err ->
                        isDownloadingUpdate = false
                        updateInfo = null
                        showToast(err)
                    }
                )
            },
            onDismiss = { updateInfo = null }
        )
    }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AstraAvatar(name = identity?.name ?: "Unknown", size = 120.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF003B2F)) // Darker green for background
                            .clickable { showEditProfileDialog = true }
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Edit",
                            color = AccentCyan, // Uses the green accent
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Group: Profile Details (No headers, just list items like WhatsApp)
            item {
                SettingsItem(
                    icon = Icons.Rounded.PersonOutline,
                    title = "Name",
                    subtitle = identity?.name ?: "Unknown",
                    onClick = { showEditProfileDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    title = "About",
                    subtitle = "Privacy-first communication",
                    subtitleColor = AccentCyan,
                    onClick = { showEditProfileDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.AlternateEmail,
                    title = "Identity Key",
                    subtitle = identity?.signingPublicKey?.let { com.astramesh.app.crypto.CryptoManager.toHex(it).take(20) + "..." } ?: "Unknown",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val hexKey = identity?.signingPublicKey?.let { com.astramesh.app.crypto.CryptoManager.toHex(it) } ?: ""
                        clipboard.setPrimaryClip(ClipData.newPlainText("Key", hexKey))
                        showToast("Key copied")
                    }
                )
            }

            item { Divider(color = CardSurface, modifier = Modifier.padding(vertical = 8.dp)) }

            // Group: Network & Privacy
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.WifiTethering,
                    title = "Tor Network",
                    subtitle = "Always active",
                    checked = torModeEnabled,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setTorEnabled(it) } 
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Link,
                    title = "Onion Address",
                    subtitle = onionAddress.ifBlank { "Connecting..." },
                    subtitleColor = if (onionAddress.isNotBlank()) AccentCyan else MutedGray,
                    onClick = {
                        if (onionAddress.isNotBlank()) showOnionDialog = true
                        else showToast("Tor not connected yet")
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.NetworkCheck,
                    title = "Test Tor Connection",
                    onClick = {
                        showToast("Testing connection via Tor...")
                        scope.launch(Dispatchers.IO) {
                            try {
                                val url = URL("https://check.torproject.org")
                                val connection = url.openConnection() as HttpsURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 10000
                                connection.readTimeout = 10000
                                val responseCode = connection.responseCode
                                withContext(Dispatchers.Main) {
                                    if (responseCode == 200) showToast("Connection Successful!")
                                    else showToast("Connection failed ($responseCode)")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { showToast("Connection failed: ${e.message}") }
                            }
                        }
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.VisibilityOff,
                    title = "Hide Online Status",
                    checked = hideOnlineStatus,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setHideOnlineStatus(it) }
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Animation,
                    title = "Reduce Motion",
                    checked = reduceMotion,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setReduceMotion(it) }
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.SwapHoriz,
                    title = "Show Transport Icons",
                    checked = showTransportIcons,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setShowTransportIcons(it) }
                    }
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Brightness4,
                    title = "Dark Mode",
                    checked = darkMode,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setDarkMode(it) }
                    }
                )
            }

            item { Divider(color = CardSurface, modifier = Modifier.padding(vertical = 8.dp)) }

            // Group: Data
            item {
                SettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Storage Usage",
                    subtitle = "$dbSize (Database)",
                    onClick = { calculateSizes() }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.CleaningServices,
                    title = "Clear Cache",
                    subtitle = "$cacheSize",
                    onClick = {
                        context.cacheDir.deleteRecursively()
                        calculateSizes()
                        showToast("Cache cleared")
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.ImportExport,
                    title = "Export Chats",
                    onClick = {
                        scope.launch {
                            val result = ExportManager.exportChatsToJSON(context, db)
                            if (result.isSuccess) showToast("Exporting...")
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Clear All Chats",
                    subtitle = "Irreversible action",
                    subtitleColor = Color(0xFFE53935), // Red
                    onClick = { showClearChatsDialog = true }
                )
            }

            item { Divider(color = CardSurface, modifier = Modifier.padding(vertical = 8.dp)) }

            // Group: App Details
            item {
                SettingsItem(
                    icon = Icons.Rounded.Update,
                    title = "Check for Updates",
                    subtitle = if (isCheckingUpdate) "Checking..." else "Verify latest release",
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            scope.launch {
                                val info = updater.checkForUpdates(manual = true)
                                isCheckingUpdate = false
                                if (info != null && info.isUpdateAvailable) {
                                    updateInfo = info
                                } else {
                                    showToast("AstraMesh is up to date!")
                                }
                            }
                        }
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.PrivacyTip,
                    title = "Privacy Policy",
                    onClick = { showPrivacyDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }

    // --- Dialogs ---

    if (showEditProfileDialog) {
        var nameInput by remember { mutableStateOf(identity?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            containerColor = CardSurface,
            title = { Text("Edit Profile", color = SoftWhite) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Name", color = MutedGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DimGray,
                        focusedTextColor = SoftWhite,
                        unfocusedTextColor = SoftWhite
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nameInput.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            identityManager.updateName(nameInput)
                            identity = identityManager.loadIdentity()
                            withContext(Dispatchers.Main) { showEditProfileDialog = false }
                        }
                    }
                }) {
                    Text("Save", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
    }

    if (showClearChatsDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatsDialog = false },
            containerColor = CardSurface,
            title = { Text("Clear All Chats?", color = SoftWhite) },
            text = { Text("Are you sure you want to delete all messages? This cannot be undone.", color = MutedGray) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.messageDao().deleteAllMessages()
                        withContext(Dispatchers.Main) {
                            showToast("All chats cleared")
                            showClearChatsDialog = false
                            calculateSizes()
                        }
                    }
                }) {
                    Text("Clear", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatsDialog = false }) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
    }

    if (showOnionDialog) {
        AlertDialog(
            onDismissRequest = { showOnionDialog = false },
            containerColor = CardSurface,
            title = { Text("Onion Address", color = SoftWhite) },
            text = { 
                Text(
                    text = onionAddress,
                    color = SoftWhite,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) 
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Onion", onionAddress))
                    showToast("Copied to clipboard")
                    showOnionDialog = false
                }) {
                    Text("Copy", color = AccentCyan)
                }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = CardSurface,
            title = { Text("Privacy Policy", color = SoftWhite) },
            text = { 
                Text(
                    text = "AstraMesh is a decentralized P2P application. We do not collect, store, or transmit your data to any central servers. All communication routes through Tor hidden services directly to your peers.", 
                    color = MutedGray 
                ) 
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Close", color = AccentCyan)
                }
            }
        )
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MutedGray,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = MutedGray, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(20.dp))
        Column {
            Text(title, color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = subtitleColor, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = MutedGray, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Normal)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = MutedGray, fontSize = 14.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepBlack,
                checkedTrackColor = AccentCyan, // Green
                uncheckedThumbColor = MutedGray,
                uncheckedTrackColor = DeepBlack,
                uncheckedBorderColor = MutedGray
            )
        )
    }
}
