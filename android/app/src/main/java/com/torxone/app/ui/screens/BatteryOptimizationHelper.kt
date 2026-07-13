package com.torxone.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.*

@Composable
fun BatteryOptimizationPrompt() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                showDialog = true
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xE6111827),
            titleContentColor = SoftWhite,
            textContentColor = MutedGray,
            shape = RoundedCornerShape(30.dp),
            tonalElevation = 0.dp,
            title = { Text("Keep TorX One Active") },
            text = {
                Text(
                    "TorX One needs to run in the background to receive messages " +
                    "over the Tor network and local mesh. Please disable battery " +
                    "optimization for the best experience."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentCyan)
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MutedGray)
                ) {
                    Text("Later")
                }
            }
        )
    }
}
