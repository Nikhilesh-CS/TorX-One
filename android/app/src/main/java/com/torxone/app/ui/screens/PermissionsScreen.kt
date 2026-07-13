package com.torxone.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.torxone.app.ui.theme.AstraTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.ui.theme.*

@Composable
fun PermissionsScreen(onRetry: () -> Unit) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(AstraTheme.spacing.massive1),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(AstraTheme.spacing.massive5),
                tint = AccentPink
            )
            Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))
            Text(
                "Permissions Required",
                fontSize = AstraTheme.typography.headlineMedium.fontSize,
                fontWeight = FontWeight.Bold,
                color = SoftWhite
            )
            Spacer(modifier = Modifier.height(AstraTheme.spacing.medium))
            Text(
                "TorX One needs Bluetooth and Location to discover nearby devices over Wi-Fi Direct and Bluetooth.\n\nTor (via Orbot) is used for secure distant messaging — no central server.",
                fontSize = AstraTheme.typography.bodyMedium.fontSize,
                color = MutedGray,
                textAlign = TextAlign.Center,
                lineHeight = AstraTheme.typography.titleLarge.fontSize
            )
            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
                Text("Location is required by Android for BLE scanning", fontSize = AstraTheme.typography.labelMedium.fontSize, color = DimGray)
            }
            Spacer(modifier = Modifier.height(AstraTheme.spacing.massive1))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(AstraTheme.spacing.standard),
                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)
            ) {
                Text("Grant Permissions", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(AstraTheme.spacing.medium))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(AstraTheme.spacing.standard)
            ) {
                Text("Open Settings", color = AccentCyan)
            }
        }
    }
}
