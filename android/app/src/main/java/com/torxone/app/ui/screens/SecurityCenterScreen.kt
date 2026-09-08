package com.torxone.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.network.TorManager
import com.torxone.app.ui.components.PremiumHeader
import com.torxone.app.ui.components.PremiumPulseDot
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.BluetoothAccent
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SuccessGreen
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorAccent
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.WarningAmber

@Composable
fun SecurityCenterScreen(
    identityManager: IdentityManager,
    torManager: TorManager,
    settingsManager: com.torxone.app.data.SettingsManager
) {
    val context = LocalContext.current
    val appLockEnabled by settingsManager.appLockEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val torReady by torManager.isTorReady.collectAsStateWithLifecycle()
    val torStatus by torManager.torStatus.collectAsStateWithLifecycle()
    val onionAddress by torManager.onionAddress.collectAsStateWithLifecycle()
    val identity = identityManager.loadIdentity()
    val identityKey = identity?.signingPublicKey?.let { CryptoManager.toHex(it) } ?: "Unknown"

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            PremiumHeader(
                title = "Security",
                subtitle = "Identity, encryption, Tor, and route trust"
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AstraTheme.spacing.large),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AstraTheme.spacing.standard)
        ) {
            SecurityCard(
                title = "End-to-end encryption",
                value = "Active - Double Ratchet + X25519",
                statusColor = SuccessGreen,
                icon = Icons.Rounded.Lock
            )

            SecurityCard(
                title = "App Lock",
                value = if (appLockEnabled) "Active - Biometrics / PIN" else "Disabled",
                statusColor = if (appLockEnabled) SuccessGreen else TextMuted,
                icon = Icons.Rounded.Lock
            )

            SecurityCard(
                title = "Identity fingerprint",
                value = identityKey,
                statusColor = TorXPrimary,
                icon = Icons.Rounded.Key,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Identity Key", identityKey))
                    Toast.makeText(context, "Identity Key copied", Toast.LENGTH_SHORT).show()
                }
            )

            SecurityCard(
                title = "Tor network status",
                value = if (torReady) "Connected & Routing" else "Connecting / Offline ($torStatus)",
                statusColor = if (torReady) TorAccent else WarningAmber,
                icon = Icons.Rounded.Public
            )

            SecurityCard(
                title = "Onion address",
                value = onionAddress.ifBlank { "Initializing..." },
                statusColor = if (onionAddress.isNotBlank()) TorAccent else TextMuted,
                icon = Icons.Rounded.Router,
                onCopy = {
                    if (onionAddress.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Onion Address", onionAddress))
                        Toast.makeText(context, "Onion Address copied", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            SecurityCard(
                title = "Nearby mesh transport",
                value = "Bluetooth Low Energy & Wi-Fi Direct active",
                statusColor = BluetoothAccent,
                icon = Icons.Rounded.Hub
            )
        }
    }
}

@Composable
fun SecurityCard(
    title: String,
    value: String,
    statusColor: Color,
    icon: ImageVector,
    onCopy: (() -> Unit)? = null
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(SurfaceCard)
        .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
        .then(if (onCopy != null) Modifier.clickable { onCopy() } else Modifier)

    Row(
        modifier = modifier.padding(AstraTheme.spacing.standard),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PremiumPulseDot(statusColor, size = 7.dp)
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = SecondaryText,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = PrimaryText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
