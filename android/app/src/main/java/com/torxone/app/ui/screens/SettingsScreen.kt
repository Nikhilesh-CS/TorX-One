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
import androidx.compose.foundation.BorderStroke
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
    val darkMode by settingsManager.darkModeFlow.collectAsStateWithLifecycle(initialValue = false)
    val performanceMode by settingsManager.performanceModeFlow.collectAsStateWithLifecycle(initialValue = "balanced")
    val appLockEnabled by settingsManager.appLockEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
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

    // App Lock States
    var showAppLockSetup by remember { mutableStateOf(false) }
    var appLockPassword by remember { mutableStateOf("") }
    var appLockError by remember { mutableStateOf<String?>(null) }
    val biometricAuthManager = remember { com.torxone.app.security.BiometricAuthManager(context as androidx.fragment.app.FragmentActivity) }

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
                    onInstallerLaunched = {
                        isDownloadingUpdate = false
                        updateInfo = null
                        showToast("Android installer opened. Confirm the update to finish.")
                    },
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
        containerColor = AstraTheme.surfaceApp,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AstraTheme.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AstraTheme.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AstraTheme.surfaceApp,
                    titleContentColor = AstraTheme.textPrimary
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
                            .background(TorXPrimarySoft)
                            .clickable { navController.navigate("profile") }
                            .padding(horizontal = AstraTheme.spacing.extraLarge, vertical = AstraTheme.spacing.small)
                    ) {
                        Text(
                            text = "Edit",
                            color = TorXPrimary,
                            fontSize = AstraTheme.typography.bodyMedium.fontSize,
                            fontWeight = FontWeight.SemiBold
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

            item { HorizontalDivider(color = AstraTheme.border, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

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
                SettingsSwitchItem(
                    icon = Icons.Rounded.Lock,
                    title = "App Lock",
                    subtitle = "Require authentication to open",
                    checked = appLockEnabled,
                    onCheckedChange = { checked -> 
                        if (checked) {
                            showAppLockSetup = true
                        } else {
                            scope.launch { settingsManager.setAppLockEnabled(false) }
                        }
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
                SettingsSwitchItem(
                    icon = Icons.Rounded.VisibilityOff,
                    title = "Hide Online Status",
                    checked = hideOnlineStatus,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setHideOnlineStatus(it) }
                    }
                )
            }

            item { HorizontalDivider(color = AstraTheme.border, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

            item {
                val modeLabel = when (performanceMode) {
                    "battery_saver" -> "Battery Saver"
                    "performance" -> "Performance"
                    else -> "Balanced"
                }
                val serviceState = if (com.torxone.app.service.TorXOneService.getInstance() != null) "Background service running" else "Service idle"
                val summary = "$modeLabel · $serviceState"

                SettingsItem(
                    icon = Icons.Rounded.Bolt,
                    title = "Battery & Performance",
                    subtitle = summary,
                    showChevron = true,
                    onClick = { navController.navigate("battery_performance") }
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
                    subtitle = if (darkMode) "Dark theme enabled" else "Switch between light and dark theme",
                    checked = darkMode,
                    onCheckedChange = { 
                        scope.launch { settingsManager.setDarkMode(it) }
                    }
                )
            }

            item { HorizontalDivider(color = AstraTheme.border, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

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

            item { HorizontalDivider(color = AstraTheme.border, modifier = Modifier.padding(vertical = AstraTheme.spacing.small)) }

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
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Clear All Chats?", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete all messages? This cannot be undone.", color = SecondaryText) },
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
                    Text("Clear", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatsDialog = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    if (showOnionDialog) {
        AlertDialog(
            onDismissRequest = { showOnionDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Onion Address", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = { 
                Text(
                    text = onionAddress,
                    color = PrimaryText,
                    fontSize = AstraTheme.typography.bodyMedium.fontSize,
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
                    Text("Copy", color = TorXPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Privacy Policy", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = { 
                Text(
                    text = "TorX One is designed to keep communication under your control.\n\n" +
                        "We do not run a central chat server, and the app does not collect your messages, contacts, identity keys, onion address, or chat history.\n\n" +
                        "Your identity is created and stored on your device. Messages are sent directly through Tor hidden services or nearby transport such as Bluetooth / Wi-Fi Direct.\n\n" +
                        "Identity backups are created only when you choose to export them. Keep your backup file and password safe, because TorX One cannot recover them for you.",
                    color = SecondaryText 
                ) 
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Close", color = TorXPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("License", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "TorX One is released under the MIT License.\n\nYou may use, modify, and share the software under the license terms included with this project.",
                    color = SecondaryText
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Close", color = TorXPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBackupWorking) showExportDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Export Identity", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Secure your backup with a strong password. You will need it to restore this identity.", color = SecondaryText)
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup Password", color = TextMuted) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isBackupWorking,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TorXPrimary,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = PrimaryText,
                            unfocusedTextColor = PrimaryText,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceSecondary
                        )
                    )
                    if (backupError != null) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        Text(backupError!!, color = ErrorRed, fontSize = AstraTheme.typography.labelMedium.fontSize)
                    }
                    if (isBackupWorking) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                        CircularProgressIndicator(color = TorXPrimary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { exportLauncher.launch("TorXOne_Backup_${System.currentTimeMillis()}.torxone-backup") },
                    enabled = backupPassword.length >= 4 && !isBackupWorking
                ) {
                    Text("Export", color = TorXPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }, enabled = !isBackupWorking) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBackupWorking) showImportDialog = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Restore Identity", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter the password used to encrypt the backup.", color = SecondaryText)
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup Password", color = TextMuted) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !isBackupWorking,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TorXPrimary,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = PrimaryText,
                            unfocusedTextColor = PrimaryText,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceSecondary
                        )
                    )
                    if (backupError != null) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        Text(backupError!!, color = ErrorRed, fontSize = AstraTheme.typography.labelMedium.fontSize)
                    }
                    if (isBackupWorking) {
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                        CircularProgressIndicator(color = TorXPrimary, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = backupPassword.isNotEmpty() && !isBackupWorking
                ) {
                    Text("Select File", color = TorXPrimary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }, enabled = !isBackupWorking) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    if (showAppLockSetup) {
        AlertDialog(
            onDismissRequest = { showAppLockSetup = false; appLockPassword = ""; appLockError = null },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Setup App Lock", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enable biometric authentication and set a fallback password.", color = SecondaryText)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = appLockPassword,
                        onValueChange = { appLockPassword = it },
                        label = { Text("Fallback Password", color = TextMuted) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TorXPrimary,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = PrimaryText,
                            unfocusedTextColor = PrimaryText,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceSecondary
                        )
                    )
                    if (appLockError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(appLockError ?: "", color = ErrorRed)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            biometricAuthManager.setupAppLockWithPassword(appLockPassword)
                            scope.launch { settingsManager.setAppLockEnabled(true) }
                            showAppLockSetup = false
                            appLockPassword = ""
                            appLockError = null
                            showToast("App Lock Enabled")
                        } catch (e: Exception) {
                            appLockError = "Failed to enable lock: ${e.message}"
                        }
                    },
                    enabled = appLockPassword.length >= 4
                ) {
                    Text("Enable", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppLockSetup = false; appLockPassword = ""; appLockError = null }) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
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
    subtitleColor: Color = AstraTheme.textSecondary,
    showChevron: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AstraTheme.spacing.large, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AstraTheme.surfaceCard)
            .border(1.dp, AstraTheme.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TorXPrimarySoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = TorXPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AstraTheme.textPrimary, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = subtitleColor, fontSize = AstraTheme.typography.bodyMedium.fontSize)
            }
        }
        if (showChevron) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
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
            .padding(horizontal = AstraTheme.spacing.large, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AstraTheme.surfaceCard)
            .border(1.dp, AstraTheme.border, RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TorXPrimarySoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = TorXPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AstraTheme.textPrimary, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = AstraTheme.textSecondary, fontSize = AstraTheme.typography.bodyMedium.fontSize)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = TorXPrimary,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = if (AstraTheme.isDarkMode) Color(0xFF334155) else SurfaceSecondary,
                uncheckedBorderColor = AstraTheme.border
            )
        )
    }
}


