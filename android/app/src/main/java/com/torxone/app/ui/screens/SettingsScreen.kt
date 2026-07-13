package com.torxone.app.ui.screens

import com.torxone.app.ui.theme.AstraTheme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ExportManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.UpdateDialog
import com.torxone.app.ui.theme.*
import com.torxone.app.updater.GitHubUpdater
import com.torxone.app.updater.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import com.torxone.app.identity.backup.IdentityBackupManager
import com.torxone.app.identity.backup.IdentityRestoreManager
import com.torxone.app.data.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    identityManager: IdentityManager,
    navController: NavController,
    onionAddress: String,
    db: AppDatabase,
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit = { navController.navigateUp() }
) {
    val context = LocalContext.current
    var identity by remember { mutableStateOf(identityManager.loadIdentity()) }
    val scope = rememberCoroutineScope()

    val torModeEnabled by settingsManager.torEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val hideOnlineStatus by settingsManager.hideOnlineStatusFlow.collectAsStateWithLifecycle(initialValue = false)
    val reduceMotion by settingsManager.reduceMotionFlow.collectAsStateWithLifecycle(initialValue = false)
    val showTransportIcons by settingsManager.showTransportIconsFlow.collectAsStateWithLifecycle(initialValue = true)
    val darkMode by settingsManager.darkModeFlow.collectAsStateWithLifecycle(initialValue = true)
    val performanceMode by settingsManager.performanceModeFlow.collectAsStateWithLifecycle(initialValue = "balanced")
    val bluetoothScanning by settingsManager.bluetoothScanningFlow.collectAsStateWithLifecycle(initialValue = true)
    val wifiDirectScanning by settingsManager.wifiDirectScanningFlow.collectAsStateWithLifecycle(initialValue = true)
    val backgroundSyncFrequency by settingsManager.backgroundSyncFrequencyFlow.collectAsStateWithLifecycle(initialValue = "normal")
    val localProfile by db.profileDao().getProfile("LOCAL_USER").collectAsStateWithLifecycle(initialValue = null)
    var isBatteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    
    // Dialog States
    var showClearChatsDialog by remember { mutableStateOf(false) }
    var showOnionDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    // Update States
    val updater = remember { GitHubUpdater(context) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }

    // Backup States
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf<String?>(null) }
    var isBackupWorking by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            isBackupWorking = true
            backupError = null
            scope.launch {
                try {
                    val profileCacheManager = com.torxone.app.identity.profile.ProfileCacheManagerImpl(context)
                    val imageProcessor = com.torxone.app.media.ImageProcessor(context)
                    val profileRepository = com.torxone.app.identity.profile.ProfileRepositoryImpl(db.profileDao(), identityManager, profileCacheManager, imageProcessor)
                    val manager = IdentityBackupManager(context, profileRepository, profileCacheManager)
                    val outputStream = context.contentResolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val result = manager.exportBackup(outputStream, backupPassword.toCharArray())
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                showExportDialog = false
                                android.widget.Toast.makeText(context, "Backup exported successfully", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                backupError = result.exceptionOrNull()?.message ?: "Unknown error"
                            }
                        }
                    } else {
                        backupError = "Could not create file."
                    }
                } catch (e: Exception) {
                    backupError = "Error: ${e.message}"
                } finally {
                    isBackupWorking = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isBackupWorking = true
            backupError = null
            scope.launch {
                try {
                    val manager = IdentityRestoreManager(context)
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val result = manager.restoreBackup(inputStream, backupPassword.toCharArray())
                        withContext(Dispatchers.Main) {
                            if (result.isSuccess) {
                                showImportDialog = false
                                identity = identityManager.loadIdentity()
                                android.widget.Toast.makeText(context, "Identity restored successfully!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                backupError = result.exceptionOrNull()?.message ?: "Unknown error"
                            }
                        }
                    } else {
                        backupError = "Could not open file."
                    }
                } catch (e: Exception) {
                    backupError = "Error: ${e.message}"
                } finally {
                    isBackupWorking = false
                }
            }
        }
    }

    // State for Storage
    var cacheSize by remember { mutableStateOf("Calculating...") }
    var dbSize by remember { mutableStateOf("Calculating...") }

    fun calculateSizes() {
        scope.launch(Dispatchers.IO) {
            val cacheFolder = context.cacheDir
            val cacheSizeVal = cacheFolder.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            val dbFolder = context.getDatabasePath("torxone_db").parentFile
            val dbSizeVal = dbFolder?.walkTopDown()?.filter { it.isFile }?.map { it.length() }?.sum() ?: 0L

            withContext(Dispatchers.Main) {
                cacheSize = "${cacheSizeVal / (1024 * 1024)} MB"
                dbSize = "${dbSizeVal / (1024 * 1024)} MB"
            }
        }
    }

    LaunchedEffect(Unit) {
        calculateSizes()
        isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
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
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
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
                        .padding(top = AstraTheme.spacing.extraLarge, bottom = AstraTheme.spacing.standard),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AstraAvatar(
                        name = identity?.name ?: localProfile?.name ?: "Unknown",
                        model = localProfile?.avatarLocalPath,
                        size = 120.dp
                    )
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AstraTheme.spacing.large))
                            .background(AstraTheme.colors.primaryContainer) // Darker green for background
                            .clickable { navController.navigate("profile") }
                            .padding(horizontal = AstraTheme.spacing.extraLarge, vertical = AstraTheme.spacing.small)
                    ) {
                        Text(
                            text = "Edit",
                            color = AccentCyan, // Uses the green accent
                            fontSize = AstraTheme.typography.bodyMedium.fontSize,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Group: Profile Details (No headers, just list items like WhatsApp)
            item {
                SettingsItem(
                    icon = Icons.Rounded.Person,
                    title = "Edit Profile",
                    subtitle = "Change your display name, bio, and avatar",
                    onClick = { navController.navigate("profile") }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Info,
                    title = "About",
                    subtitle = "Privacy-first communication",
                    subtitleColor = AccentCyan,
                    onClick = { showPrivacyDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.AlternateEmail,
                    title = "Identity Key",
                    subtitle = identity?.signingPublicKey?.let { com.torxone.app.crypto.CryptoManager.toHex(it).take(20) + "..." } ?: "Unknown",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val hexKey = identity?.signingPublicKey?.let { com.torxone.app.crypto.CryptoManager.toHex(it) } ?: ""
                        clipboard.setPrimaryClip(ClipData.newPlainText("Key", hexKey))
                        showToast("Key copied")
                    }
                )
            }

            item { HorizontalDivider(color = CardSurface, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

            // Group: Network & Privacy
            item {
                SettingsItem(
                    icon = Icons.Rounded.Dashboard,
                    title = "Mesh Dashboard",
                    subtitle = "Network health and topology",
                    onClick = { navController.navigate("mesh_dashboard") }
                )
            }
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

            item { HorizontalDivider(color = CardSurface, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

            item {
                BatteryPerformanceSection(
                    torEnabled = torModeEnabled,
                    bluetoothScanning = bluetoothScanning,
                    wifiDirectScanning = wifiDirectScanning,
                    performanceMode = performanceMode,
                    backgroundSyncFrequency = backgroundSyncFrequency,
                    batteryOptimizationIgnored = isBatteryOptimizationIgnored,
                    serviceRunning = com.torxone.app.service.TorXOneService.getInstance() != null,
                    onOpenBatterySettings = {
                        openBatteryOptimizationSettings(context)
                        isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
                    },
                    onPerformanceModeSelected = { mode ->
                        scope.launch { settingsManager.setPerformanceMode(mode) }
                    },
                    onBluetoothScanningChanged = { enabled ->
                        scope.launch { settingsManager.setBluetoothScanning(enabled) }
                    },
                    onWifiDirectScanningChanged = { enabled ->
                        scope.launch { settingsManager.setWifiDirectScanning(enabled) }
                    },
                    onBackgroundSyncChanged = { frequency ->
                        scope.launch { settingsManager.setBackgroundSyncFrequency(frequency) }
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
                    title = "AMOLED Pure Black",
                    checked = darkMode,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setDarkMode(it) }
                    }
                )
            }

            item { HorizontalDivider(color = CardSurface, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

            // Group: Identity Management
            item {
                SettingsItem(
                    icon = Icons.Rounded.VpnKey,
                    title = "Export Identity Backup",
                    subtitle = "Save a secure copy of your identity",
                    onClick = { showExportDialog = true; backupPassword = ""; backupError = null }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Restore,
                    title = "Restore Identity Backup",
                    subtitle = "Recover an identity from a backup file",
                    onClick = { showImportDialog = true; backupPassword = ""; backupError = null }
                )
            }

            item { HorizontalDivider(color = CardSurface, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

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
                    subtitleColor = AstraTheme.colors.error, // Red
                    onClick = { showClearChatsDialog = true }
                )
            }

            item { HorizontalDivider(color = CardSurface, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

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
                                    showToast("TorX One is up to date!")
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
                    subtitle = "How TorX One handles your data",
                    onClick = { showPrivacyDialog = true }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Rounded.Description,
                    title = "License",
                    subtitle = "MIT License",
                    onClick = { showLicenseDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(AstraTheme.spacing.massive2)) }
        }
    }

    // --- Dialogs ---

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
                    Text("Clear", color = AstraTheme.colors.error)
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
                    fontSize = AstraTheme.typography.bodyLarge.fontSize,
                    modifier = Modifier.padding(vertical = AstraTheme.spacing.small)
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
                    text = "TorX One is designed to keep communication under your control.\n\n" +
                        "We do not run a central chat server, and the app does not collect your messages, contacts, identity keys, onion address, or chat history.\n\n" +
                        "Your identity is created and stored on your device. Messages are sent directly through Tor hidden services or nearby transport such as Bluetooth / Wi-Fi Direct.\n\n" +
                        "Identity backups are created only when you choose to export them. Keep your backup file and password safe, because TorX One cannot recover them for you.",
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

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            containerColor = CardSurface,
            title = { Text("License", color = SoftWhite) },
            text = {
                Text(
                    text = "TorX One is released under the MIT License.\n\nYou may use, modify, and share the software under the license terms included with this project.",
                    color = MutedGray
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Close", color = AccentCyan)
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBackupWorking) showExportDialog = false },
            containerColor = CardSurface,
            title = { Text("Export Identity", color = SoftWhite) },
            text = {
                Column {
                    Text("Secure your backup with a strong password. You will need it to restore this identity.", color = MutedGray)
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup Password", color = MutedGray) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isBackupWorking,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DimGray,
                            focusedTextColor = SoftWhite,
                            unfocusedTextColor = SoftWhite
                        )
                    )
                    if (backupError != null) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        Text(backupError!!, color = AstraTheme.colors.error, fontSize = AstraTheme.typography.labelMedium.fontSize)
                    }
                    if (isBackupWorking) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                        CircularProgressIndicator(color = AccentCyan, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { exportLauncher.launch("TorXOne_Backup_${System.currentTimeMillis()}.astramesh-backup") },
                    enabled = backupPassword.length >= 4 && !isBackupWorking
                ) {
                    Text("Export", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }, enabled = !isBackupWorking) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBackupWorking) showImportDialog = false },
            containerColor = CardSurface,
            title = { Text("Restore Identity", color = SoftWhite) },
            text = {
                Column {
                    Text("Enter the password used to encrypt the backup.", color = MutedGray)
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup Password", color = MutedGray) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isBackupWorking,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DimGray,
                            focusedTextColor = SoftWhite,
                            unfocusedTextColor = SoftWhite
                        )
                    )
                    if (backupError != null) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        Text(backupError!!, color = AstraTheme.colors.error, fontSize = AstraTheme.typography.labelMedium.fontSize)
                    }
                    if (isBackupWorking) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                        CircularProgressIndicator(color = AccentCyan, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = backupPassword.isNotEmpty() && !isBackupWorking
                ) {
                    Text("Select File", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }, enabled = !isBackupWorking) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
    }
}

@Composable
private fun BatteryPerformanceSection(
    torEnabled: Boolean,
    bluetoothScanning: Boolean,
    wifiDirectScanning: Boolean,
    performanceMode: String,
    backgroundSyncFrequency: String,
    batteryOptimizationIgnored: Boolean,
    serviceRunning: Boolean,
    onOpenBatterySettings: () -> Unit,
    onPerformanceModeSelected: (String) -> Unit,
    onBluetoothScanningChanged: (Boolean) -> Unit,
    onWifiDirectScanningChanged: (Boolean) -> Unit,
    onBackgroundSyncChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AstraTheme.spacing.large, vertical = 6.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF101827).copy(alpha = 0.92f),
                        Color(0xFF051914).copy(alpha = 0.86f),
                        Color(0xFF140F25).copy(alpha = 0.90f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(28.dp))
            .padding(AstraTheme.spacing.large)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E5A8).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color(0xFF00E5A8), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(AstraTheme.spacing.standard))
            Column(Modifier.weight(1f)) {
                Text("Battery & Performance", color = SoftWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Control background networking and power usage", color = MutedGray, fontSize = AstraTheme.typography.bodyMedium.fontSize)
            }
        }

        Spacer(Modifier.height(AstraTheme.spacing.large))

        val impact = estimatedBatteryImpact(torEnabled, bluetoothScanning, wifiDirectScanning, performanceMode)
        BatteryMetricRow("Estimated impact", impact, impactColor(impact))
        BatteryMetricRow("Background service", if (serviceRunning) "Running" else "Not running", if (serviceRunning) NeonGreen else AccentPink)
        BatteryMetricRow(
            "Android optimization",
            if (batteryOptimizationIgnored) "Unrestricted" else "Optimized",
            if (batteryOptimizationIgnored) NeonGreen else Color(0xFFFFC857)
        )

        Spacer(Modifier.height(AstraTheme.spacing.standard))
        Text("Active components", color = SoftWhite, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AstraTheme.spacing.small))
        BatteryComponentRow("Tor auto-start", torEnabled, if (torEnabled) "High" else "Off")
        BatteryComponentRow("Bluetooth discovery", bluetoothScanning, if (bluetoothScanning) componentImpact(performanceMode) else "Off")
        BatteryComponentRow("Wi-Fi Direct discovery", wifiDirectScanning, if (wifiDirectScanning) componentImpact(performanceMode) else "Off")
        BatteryComponentRow("Mesh discovery", bluetoothScanning || wifiDirectScanning, if (bluetoothScanning || wifiDirectScanning) componentImpact(performanceMode) else "Off")

        Spacer(Modifier.height(AstraTheme.spacing.large))
        Text("Performance mode", color = SoftWhite, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AstraTheme.spacing.small))
        PerformanceModeRow("battery_saver", "Battery Saver", "Reduce discovery. Best for long battery life.", performanceMode, onPerformanceModeSelected)
        PerformanceModeRow("balanced", "Balanced", "Recommended. Keeps chat reliable without aggressive scanning.", performanceMode, onPerformanceModeSelected)
        PerformanceModeRow("performance", "Performance", "Fast discovery and routing. Higher battery usage.", performanceMode, onPerformanceModeSelected)

        Spacer(Modifier.height(AstraTheme.spacing.large))
        Text("Background services", color = SoftWhite, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AstraTheme.spacing.small))
        CompactSwitchRow("Bluetooth scanning", bluetoothScanning, onBluetoothScanningChanged)
        CompactSwitchRow("Wi-Fi Direct scanning", wifiDirectScanning, onWifiDirectScanningChanged)

        Spacer(Modifier.height(AstraTheme.spacing.large))
        Text("Background sync frequency", color = SoftWhite, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AstraTheme.spacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SyncChip("low", "Low", backgroundSyncFrequency, onBackgroundSyncChanged, Modifier.weight(1f))
            SyncChip("normal", "Normal", backgroundSyncFrequency, onBackgroundSyncChanged, Modifier.weight(1f))
            SyncChip("fast", "Fast", backgroundSyncFrequency, onBackgroundSyncChanged, Modifier.weight(1f))
        }

        Spacer(Modifier.height(AstraTheme.spacing.large))
        Text(
            "Disabling Android battery optimization helps Tor and mesh delivery stay alive in the background, especially on Realme, Oppo, Vivo and Xiaomi devices.",
            color = MutedGray,
            fontSize = AstraTheme.typography.bodySmall.fontSize
        )
        Spacer(Modifier.height(AstraTheme.spacing.small))
        OutlinedButton(
            onClick = onOpenBatterySettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Android Battery Settings")
        }
    }
}

@Composable
private fun BatteryMetricRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MutedGray, fontSize = AstraTheme.typography.bodyMedium.fontSize)
        Text(value, color = valueColor, fontSize = AstraTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BatteryComponentRow(label: String, enabled: Boolean, impact: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (enabled) NeonGreen else DimGray)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = SoftWhite, modifier = Modifier.weight(1f), fontSize = AstraTheme.typography.bodyMedium.fontSize)
        Text(impact, color = if (enabled) impactColor(impact) else MutedGray, fontSize = AstraTheme.typography.labelMedium.fontSize)
    }
}

@Composable
private fun PerformanceModeRow(
    mode: String,
    title: String,
    subtitle: String,
    selectedMode: String,
    onSelected: (String) -> Unit
) {
    val selected = selectedMode == mode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.045f))
            .clickable { onSelected(mode) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = { onSelected(mode) })
        Column(Modifier.weight(1f)) {
            Text(title, color = SoftWhite, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)
        }
    }
}

@Composable
private fun CompactSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SoftWhite, modifier = Modifier.weight(1f), fontSize = AstraTheme.typography.bodyMedium.fontSize)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SyncChip(
    value: String,
    label: String,
    selectedValue: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selectedValue == value,
        onClick = { onSelected(value) },
        label = { Text(label, maxLines = 1) },
        modifier = modifier
    )
}

private fun estimatedBatteryImpact(
    torEnabled: Boolean,
    bluetoothScanning: Boolean,
    wifiDirectScanning: Boolean,
    performanceMode: String
): String {
    if (!torEnabled && !bluetoothScanning && !wifiDirectScanning) return "Low"
    if (performanceMode == "performance" && (torEnabled || bluetoothScanning || wifiDirectScanning)) return "High"
    if (torEnabled && (bluetoothScanning || wifiDirectScanning)) return "Medium"
    return if (performanceMode == "battery_saver") "Low" else "Medium"
}

private fun componentImpact(performanceMode: String): String {
    return when (performanceMode) {
        "battery_saver" -> "Low"
        "performance" -> "High"
        else -> "Medium"
    }
}

private fun impactColor(impact: String): Color {
    return when (impact) {
        "Low" -> NeonGreen
        "Medium" -> Color(0xFFFFC857)
        "High" -> AccentPink
        else -> MutedGray
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
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
            .padding(horizontal = AstraTheme.spacing.large, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.105f),
                        Color(0xFF0B1020).copy(alpha = 0.78f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AccentCyan.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = AccentCyan, modifier = Modifier.size(23.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.large))
        Column {
            Text(title, color = SoftWhite, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = subtitleColor, fontSize = AstraTheme.typography.bodyMedium.fontSize)
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
            .padding(horizontal = AstraTheme.spacing.large, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.105f),
                        Color(0xFF0B1020).copy(alpha = 0.78f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AccentCyan.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = AccentCyan, modifier = Modifier.size(23.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.large))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SoftWhite, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = MutedGray, fontSize = AstraTheme.typography.bodyMedium.fontSize)
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


