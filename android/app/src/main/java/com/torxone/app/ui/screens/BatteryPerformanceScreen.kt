package com.torxone.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torxone.app.data.SettingsManager
import com.torxone.app.ui.theme.AstraTheme
import kotlinx.coroutines.launch

// TorX Dynamic Design Tokens
private val AppBackground: Color @Composable get() = AstraTheme.surfaceApp
private val SurfaceCard: Color @Composable get() = AstraTheme.surfaceCard
private val SurfaceSecondary: Color @Composable get() = if (AstraTheme.isDarkMode) Color(0xFF1E293B) else Color(0xFFF1F5F9)
private val BorderColor: Color @Composable get() = AstraTheme.border
private val PrimaryText: Color @Composable get() = AstraTheme.textPrimary
private val SecondaryText: Color @Composable get() = AstraTheme.textSecondary
private val TextMuted = Color(0xFF94A3B8)
private val TorXPrimary = Color(0xFF2563EB)
private val TorXPrimarySoft = Color(0xFFEFF6FF)
private val SuccessGreen = Color(0xFF16A34A)
private val WarningAmber = Color(0xFFD97706)
private val ErrorRed = Color(0xFFDC2626)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryPerformanceScreen(
    settingsManager: SettingsManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val torEnabled by settingsManager.torEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val bluetoothScanning by settingsManager.bluetoothScanningFlow.collectAsStateWithLifecycle(initialValue = true)
    val wifiDirectScanning by settingsManager.wifiDirectScanningFlow.collectAsStateWithLifecycle(initialValue = true)
    val performanceMode by settingsManager.performanceModeFlow.collectAsStateWithLifecycle(initialValue = "balanced")
    val backgroundSyncFrequency by settingsManager.backgroundSyncFrequencyFlow.collectAsStateWithLifecycle(initialValue = "normal")

    var isBatteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val serviceRunning = com.torxone.app.service.TorXOneService.getInstance() != null

    LaunchedEffect(Unit) {
        isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
    }

    val impact = estimatedBatteryImpact(torEnabled, bluetoothScanning, wifiDirectScanning, performanceMode)

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Battery & Performance",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = PrimaryText
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                    titleContentColor = PrimaryText
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Description
            item {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        "Battery & Performance",
                        color = PrimaryText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Control background networking and power usage",
                        color = SecondaryText,
                        fontSize = 14.sp
                    )
                }
            }

            // Overview Metric Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        BatteryMetricRow("Estimated impact", impact, impactColor(impact))
                        Spacer(Modifier.height(8.dp))
                        BatteryMetricRow(
                            "Background service",
                            if (serviceRunning) "Running" else "Not running",
                            if (serviceRunning) SuccessGreen else ErrorRed
                        )
                        Spacer(Modifier.height(8.dp))
                        BatteryMetricRow(
                            "Android optimization",
                            if (isBatteryOptimizationIgnored) "Unrestricted" else "Optimized",
                            if (isBatteryOptimizationIgnored) SuccessGreen else WarningAmber
                        )
                    }
                }
            }

            // Active Components Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Active components",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        BatteryComponentRow("Tor auto-start", torEnabled, if (torEnabled) "High" else "Off")
                        BatteryComponentRow("Bluetooth discovery", bluetoothScanning, if (bluetoothScanning) componentImpact(performanceMode) else "Off")
                        BatteryComponentRow("Wi-Fi Direct discovery", wifiDirectScanning, if (wifiDirectScanning) componentImpact(performanceMode) else "Off")
                        BatteryComponentRow(
                            "Mesh discovery",
                            bluetoothScanning || wifiDirectScanning,
                            if (bluetoothScanning || wifiDirectScanning) componentImpact(performanceMode) else "Off"
                        )
                    }
                }
            }

            // Performance Mode Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Performance mode",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        PerformanceModeOption(
                            mode = "battery_saver",
                            title = "Battery Saver",
                            subtitle = "Reduce discovery. Best for long battery life.",
                            selectedMode = performanceMode,
                            onSelected = { mode ->
                                scope.launch { settingsManager.setPerformanceMode(mode) }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        PerformanceModeOption(
                            mode = "balanced",
                            title = "Balanced",
                            subtitle = "Recommended. Keeps chat reliable without aggressive scanning.",
                            selectedMode = performanceMode,
                            onSelected = { mode ->
                                scope.launch { settingsManager.setPerformanceMode(mode) }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        PerformanceModeOption(
                            mode = "performance",
                            title = "Performance",
                            subtitle = "Fast discovery and routing. Higher battery usage.",
                            selectedMode = performanceMode,
                            onSelected = { mode ->
                                scope.launch { settingsManager.setPerformanceMode(mode) }
                            }
                        )
                    }
                }
            }

            // Background Services & Sync Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Background services",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        CompactSwitchRow(
                            label = "Bluetooth scanning",
                            checked = bluetoothScanning,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsManager.setBluetoothScanning(enabled) }
                            }
                        )
                        CompactSwitchRow(
                            label = "Wi-Fi Direct scanning",
                            checked = wifiDirectScanning,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsManager.setWifiDirectScanning(enabled) }
                            }
                        )

                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Background sync frequency",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SyncChip(
                                value = "low",
                                label = "Low",
                                selectedValue = backgroundSyncFrequency,
                                onSelected = { freq -> scope.launch { settingsManager.setBackgroundSyncFrequency(freq) } },
                                modifier = Modifier.weight(1f)
                            )
                            SyncChip(
                                value = "normal",
                                label = "Normal",
                                selectedValue = backgroundSyncFrequency,
                                onSelected = { freq -> scope.launch { settingsManager.setBackgroundSyncFrequency(freq) } },
                                modifier = Modifier.weight(1f)
                            )
                            SyncChip(
                                value = "fast",
                                label = "Fast",
                                selectedValue = backgroundSyncFrequency,
                                onSelected = { freq -> scope.launch { settingsManager.setBackgroundSyncFrequency(freq) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Android Battery Optimization
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Android battery optimization",
                            color = PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Disabling Android battery optimization helps Tor and mesh delivery stay alive in the background, especially on Realme, Oppo, Vivo and Xiaomi devices.",
                            color = SecondaryText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                openBatteryOptimizationSettings(context)
                                isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, BorderColor),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TorXPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Android Battery Settings")
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun BatteryMetricRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SecondaryText, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BatteryComponentRow(label: String, enabled: Boolean, impact: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (enabled) SuccessGreen else BorderColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = PrimaryText, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(impact, color = if (enabled) impactColor(impact) else TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun PerformanceModeOption(
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
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) TorXPrimarySoft else SurfaceCard)
            .border(1.dp, if (selected) TorXPrimary else BorderColor, RoundedCornerShape(12.dp))
            .clickable { onSelected(mode) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelected(mode) },
            colors = RadioButtonDefaults.colors(selectedColor = TorXPrimary, unselectedColor = TextMuted)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = SecondaryText, fontSize = 12.sp)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = PrimaryText, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = TorXPrimary,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceSecondary,
                uncheckedBorderColor = BorderColor
            )
        )
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
        "Low" -> SuccessGreen
        "Medium" -> WarningAmber
        "High" -> ErrorRed
        else -> TextMuted
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
