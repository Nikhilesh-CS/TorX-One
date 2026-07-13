package com.torxone.app.ui.screens

import com.torxone.app.ui.theme.AstraTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.TorManager
import com.torxone.app.network.TorState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.torxone.app.debug.BatteryProfiler
import com.torxone.app.debug.CrashRecoveryEngine
import com.torxone.app.debug.NetworkChaosMonkey
import com.torxone.app.debug.StressTestEngine
import com.torxone.app.debug.UIMicroAuditController
import com.torxone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.Build
import android.widget.Toast
import com.torxone.app.debug.*
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    navController: NavController,
    torManager: TorManager
) {
    val torState by torManager.torState.collectAsStateWithLifecycle()
    val onionAddress by torManager.onionAddress.collectAsStateWithLifecycle()
    val torLogs by torManager.torLogs.collectAsStateWithLifecycle()
    val lastError by torManager.lastError.collectAsStateWithLifecycle()
    val lastPing by torManager.lastPing.collectAsStateWithLifecycle()

    var testOnion by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var torVersionResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // QA Tester States
    var isOverlayEnabled by remember { mutableStateOf(false) }
    var isChaosMonkeyEnabled by remember { mutableStateOf(false) }

    // Watch for pong response
    LaunchedEffect(lastPing) {
        if (lastPing != null && testResult?.contains("Waiting") == true) {
            testResult = "Success ($lastPing)"
        }
    }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("Tor Diagnostics", color = SoftWhite) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = SoftWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepBlack)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AstraTheme.spacing.standard),
            verticalArrangement = Arrangement.spacedBy(AstraTheme.spacing.standard)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                    Column(modifier = Modifier.padding(AstraTheme.spacing.standard)) {
                        val torStatusLabel = when (torState) {
                            is TorState.Connected -> "Connected"
                            is TorState.Reconnecting -> "Reconnecting"
                            is TorState.Failed -> "Failed"
                            is TorState.Starting -> "Starting"
                            TorState.Idle -> "Idle"
                            TorState.Stopped -> "Stopped"
                        }
                        Text("Tor Status: $torStatusLabel", color = AccentCyan)
                        val progress = if (torState is TorState.Starting) (torState as TorState.Starting).progress else if (torState is TorState.Connected) 100 else 0
                        Text("Bootstrap: $progress%", color = SoftWhite)
                        Text("My Onion: ${if (onionAddress.isNotBlank()) onionAddress else "N/A"}", color = SoftWhite)
                        Text("SOCKS Port: 9050", color = SoftWhite)
                        Text("Hidden Service Port: 8765", color = SoftWhite)
                        Text("Last Error: ${lastError ?: "None"}", color = AccentPink)
                        Text("Last Ping: ${lastPing ?: "None"}", color = NeonGreen)
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                    Column(modifier = Modifier.padding(AstraTheme.spacing.standard)) {
                        Text("Binary Path: ${torManager.torBinaryPath}", color = SoftWhite, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        Text("File Exists: ${torManager.torBinaryExists}", color = SoftWhite, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        Text("Can Execute: ${torManager.torBinaryExecutable}", color = SoftWhite, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        Text("Binary Type: ${torManager.torBinaryType}", color = SoftWhite, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        Text("Detected ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"}", color = SoftWhite, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    torVersionResult = torManager.testTorBinary()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Test Tor Binary")
                        }
                        if (torVersionResult != null) {
                            Text("Tor Test: $torVersionResult", color = AccentCyan, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        }
                    }
                }
            }

            item {
                Text("QA Certification Tools (Section 02)", color = AccentViolet, fontWeight = FontWeight.Bold)
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                    Column(modifier = Modifier.padding(AstraTheme.spacing.standard), verticalArrangement = Arrangement.spacedBy(AstraTheme.spacing.small)) {
                        Text("Phase 1 & 3: Performance & Flow", color = AccentCyan)
                        Button(
                            onClick = { 
                                Toast.makeText(context, "Stress test requires database wiring", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Inject 20,000 Messages (Stress Test)")
                        }

                        Text("Phase 2: UI Micro Audit", color = AccentCyan)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("8dp Grid & Touch Overlay", color = SoftWhite, modifier = Modifier.weight(1f))
                            Switch(
                                checked = isOverlayEnabled,
                                onCheckedChange = { isOverlayEnabled = it }
                            )
                        }

                        Text("Phase 4: Mesh Reliability", color = AccentCyan)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Network Chaos Monkey", color = SoftWhite, modifier = Modifier.weight(1f))
                            Switch(
                                checked = isChaosMonkeyEnabled,
                                onCheckedChange = { 
                                    isChaosMonkeyEnabled = it
                                    // NetworkChaosMonkey.isActive = it
                                }
                            )
                        }

                        Text("Phase X: Crash Recovery & Battery", color = AccentCyan)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                scope.launch { CrashRecoveryEngine.triggerProcessDeath(500) }
                            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) {
                                Text("Kill Process")
                            }
                            Button(onClick = {
                                scope.launch(Dispatchers.Default) { CrashRecoveryEngine.triggerOOM() }
                            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))) {
                                Text("Trigger OOM")
                            }
                        }
                    }
                }
            }

            item {
                Text("Test Tor Connection", color = AccentViolet, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = testOnion,
                    onValueChange = { testOnion = it },
                    label = { Text("Peer Onion Address") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SoftWhite,
                        unfocusedTextColor = SoftWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                Button(
                    onClick = {
                        testResult = "Connecting..."
                        scope.launch(Dispatchers.IO) {
                            val payload = MeshProtocol.encodePing(System.currentTimeMillis(), onionAddress)
                            val ok = torManager.sendToOnion(testOnion, payload)
                            if (ok) {
                                testResult = "Ping sent... Waiting for pong..."
                            } else {
                                testResult = "Connection Timeout"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ping")
                }
                if (testResult != null) {
                    Text("Result: $testResult", color = MutedGray)
                }
            }

            item {
                Text("Tor Logs", color = AccentViolet, fontWeight = FontWeight.Bold)
            }

            items(torLogs.reversed()) { log ->
                Text(log, color = MutedGray, fontSize = AstraTheme.typography.labelSmall.fontSize, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

