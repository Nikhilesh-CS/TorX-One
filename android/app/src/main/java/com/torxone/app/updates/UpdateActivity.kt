package com.torxone.app.updates

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torxone.app.BuildConfig
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Private update-only activity. It exposes no chats/identity and never bypasses app/vault locking. */
class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = try { UpdateRuntime.get(this) } catch (_: Exception) {
            android.widget.Toast.makeText(this, "App updates are temporarily unavailable.", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        runtime.initialize()
        val appearance = AppSettingsRepository(applicationContext)
        setContent {
            val theme by appearance.themeMode.collectAsStateWithLifecycle("SYSTEM")
            val source by appearance.themeSource.collectAsStateWithLifecycle("TORX")
            val accent by appearance.accentId.collectAsStateWithLifecycle("SAGE")
            val font by appearance.fontSize.collectAsStateWithLifecycle("MEDIUM")
            val dark = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
            TorXOneTheme(darkTheme = dark, dynamicColor = source == "SYSTEM", accentId = accent, fontSize = font) {
                UpdateScreen(runtime, this, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateScreen(runtime: UpdateRuntime, activity: Activity, onBack: () -> Unit) {
    val state by runtime.state.collectAsStateWithLifecycle()
    val preferences by runtime.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val installer = remember(activity) { UpdateInstaller(activity.applicationContext) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var permissionExplanation by rememberSaveable { mutableStateOf(false) }
    var cellularConfirmation by rememberSaveable { mutableStateOf(false) }
    var permissionResume by rememberSaveable { mutableStateOf(false) }
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        busy = false
        feedback = if (result.resultCode == Activity.RESULT_CANCELED) "Installation cancelled. The verified update is still available."
            else "Android has returned from installation. Your installed version is checked on the next launch."
    }
    val launchVerifiedInstall: () -> Unit = {
        if (!busy) scope.launch {
            busy = true; feedback = null
            val release = (runtime.state.value as? UpdateState.ReadyToInstall)?.release
            try {
                if (release == null) throw UpdateFailure("The update is no longer ready. Check for updates again.")
                val file = UpdateFiles.apk(activity, release.versionCode)
                installer.verify(file, release) // Rehash immediately before URI grant, including after permission return.
                if (!installer.canInstall()) {
                    busy = false; permissionExplanation = true
                } else installLauncher.launch(installer.installIntent(file))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy = false
                if (e is UpdateFailure) release?.let { runtime.rejectDownloaded(it, e.userMessage) }
                feedback = (e as? UpdateFailure)?.userMessage ?: "Android couldn't open the installer. Please try again."
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (permissionResume) {
            permissionResume = false
            if (installer.canInstall()) installAfterPermission = true
            else feedback = "Install permission wasn't granted. You can enable it when you're ready."
        }
    }
    LaunchedEffect(installAfterPermission, state) {
        if (installAfterPermission && state is UpdateState.ReadyToInstall) {
            installAfterPermission = false
            launchVerifiedInstall()
        } else if (installAfterPermission && state is UpdateState.Error) {
            installAfterPermission = false
            feedback = "Could not restore the verified update. Please download it again."
        }
    }
    if (permissionExplanation) AlertDialog(onDismissRequest = { permissionExplanation = false },
        title = { Text("Allow update installation") },
        text = { Text("Android requires permission for TorX One to install its verified GitHub update. You'll still confirm the installation in Android's installer.") },
        confirmButton = { TextButton(onClick = {
            permissionExplanation = false; permissionResume = true
            try { permissionLauncher.launch(installer.permissionIntent()) }
            catch (_: Exception) { permissionResume = false; feedback = "Couldn't open Android's install permission settings." }
        }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { permissionExplanation = false }) { Text("Later") } })
    val available = state as? UpdateState.Available
    if (cellularConfirmation && available != null) AlertDialog(onDismissRequest = { cellularConfirmation = false },
        title = { Text("Download update?") },
        text = { Text("This download may use mobile data (${sizeLabel(available.release.size)}). It will run in the background and installation needs your approval.") },
        confirmButton = { TextButton(onClick = { cellularConfirmation = false; runtime.download(available.release) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { cellularConfirmation = false }) { Text("Cancel") } })

    Scaffold(topBar = { TopAppBar(title = { Text("App updates") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Current version", style = MaterialTheme.typography.titleMedium)
            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automatically check for updates", modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(checked = preferences.automatic, onCheckedChange = runtime::automatic,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Automatically check for updates" })
            }
            Text("Stable channel · GitHub Releases")
            Text("Update checks and downloads use normal HTTPS, not Tor. GitHub can see your network address. No contacts, messages or account identity are sent.",
                style = MaterialTheme.typography.bodySmall)
            val working = state == UpdateState.Checking || state is UpdateState.Downloading || state == UpdateState.Verifying
            Button(onClick = { feedback = null; runtime.manualCheck() }, enabled = !working && !busy && state !is UpdateState.ReadyToInstall,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Check for updates") }
            when (val current = state) {
                UpdateState.Idle -> Text("Check for the latest stable release.")
                UpdateState.Checking -> { CircularProgressIndicator(); Text("Checking…") }
                UpdateState.UpToDate -> Text("You're up to date.")
                is UpdateState.Error -> Text(current.userMessage, color = MaterialTheme.colorScheme.error)
                is UpdateState.Available -> {
                    Text(if (current.release.critical) "Important security update available" else "TorX One ${current.release.versionName} is available",
                        style = MaterialTheme.typography.titleLarge)
                    if (BuildConfig.VERSION_CODE < current.release.minimumSupportedVersionCode)
                        Text("This version is below the release's recommended minimum. Updating is recommended.")
                    Text("What's new", style = MaterialTheme.typography.titleMedium)
                    Text(current.release.releaseNotes.ifBlank { "See the full release notes for details." })
                    Text(sizeLabel(current.release.size))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { runtime.later(current.release); onBack() }) { Text("Later") }
                        Button(onClick = { cellularConfirmation = true }) { Text("Update") }
                    }
                }
                is UpdateState.Downloading -> {
                    if (current.progress < 0) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Waiting for network or download retry…") }
                    else { LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${(current.progress * 100).toInt()}%") }
                    OutlinedButton(onClick = runtime::cancelDownload) { Text("Cancel download") }
                }
                UpdateState.Verifying -> { CircularProgressIndicator(); Text("Verifying update…") }
                is UpdateState.ReadyToInstall -> {
                    Text("Version ${current.release.versionName} is verified and ready to install.")
                    Button(onClick = launchVerifiedInstall, enabled = !busy) { Text(if (busy) "Opening installer…" else "Install update") }
                    Text("Android asks you to confirm. Your existing app data stays in place.", style = MaterialTheme.typography.bodySmall)
                }
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (preferences.lastCheck > 0) Text("Last checked: " + android.text.format.DateUtils.getRelativeTimeSpanString(
                preferences.lastCheck, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
            val page = when (val current = state) {
                is UpdateState.Available -> current.release.releaseUrl
                is UpdateState.ReadyToInstall -> current.release.releaseUrl
                else -> UpdatePolicy.SOURCE + "/releases"
            }
            TextButton(onClick = {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page))) }
                catch (_: Exception) { feedback = "No browser is available to open GitHub." }
            }) { Text("View full release notes on GitHub") }
        }
    }
}

private fun sizeLabel(bytes: Long) = "%.1f MB".format(java.util.Locale.getDefault(), bytes / (1024.0 * 1024.0))
