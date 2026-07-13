package com.torxone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.TorManager
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.PremiumAuroraBackground
import com.torxone.app.ui.components.PremiumHeader
import com.torxone.app.ui.components.PremiumPulseDot
import com.torxone.app.ui.components.premiumGlass
import com.torxone.app.ui.theme.AccentCyan
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.DimGray
import com.torxone.app.ui.theme.MutedGray
import com.torxone.app.ui.theme.NeonGreen
import com.torxone.app.ui.theme.SoftWhite

@Composable
fun ContactsScreen(
    navController: NavController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager
) {
    val contacts by db.contactDao().getAllContacts().collectAsStateWithLifecycle(initialValue = emptyList())
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsStateWithLifecycle()
    val isTorReady by torManager.isTorReady.collectAsStateWithLifecycle()
    val presenceStates by (com.torxone.app.service.TorXOneService.getInstance()
        ?.presenceManager
        ?.presence
        ?: kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.torxone.app.presence.PresenceState>>(emptyMap()))
        .collectAsStateWithLifecycle()

    PremiumAuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                PremiumHeader(
                    title = "Contacts",
                    subtitle = "Verified identities and active routes"
                )
            }
        ) { paddingValues ->
            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(AstraTheme.spacing.massive2),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .premiumGlass()
                            .padding(AstraTheme.spacing.extraLarge)
                    ) {
                        Icon(
                            Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(AstraTheme.spacing.massive5),
                            tint = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                        Text("No contacts yet", fontSize = 20.sp, color = SoftWhite, fontWeight = FontWeight.Black)
                        Text(
                            "Discover nearby users or share your onion address.",
                            fontSize = AstraTheme.typography.bodyMedium.fontSize,
                            color = MutedGray,
                            modifier = Modifier.padding(top = AstraTheme.spacing.small),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(contacts) { contact ->
                        val isNearby = connectedEndpoints.contains(contact.endpointId)
                        val isLivePresence = presenceStates[contact.signingPublicKey]?.activity == "online"
                        val isTorRouteAvailable = contact.onionAddress.isNotBlank() && isTorReady
                        val profile by db.profileDao().getProfile(contact.signingPublicKey).collectAsStateWithLifecycle(initialValue = null)

                        ContactItemRow(
                            contact = contact,
                            avatarModel = profile?.avatarLocalPath,
                            isNearby = isNearby,
                            isOnline = isNearby || isLivePresence,
                            isTorRouteAvailable = isTorRouteAvailable,
                            onClick = {
                                navController.navigate("chat/${contact.signingPublicKey}")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItemRow(
    contact: ContactEntity,
    avatarModel: Any?,
    isNearby: Boolean,
    isOnline: Boolean,
    isTorRouteAvailable: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AstraTheme.spacing.large, vertical = 7.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color(0xFF0B1020).copy(alpha = 0.78f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, model = avatarModel, size = AstraTheme.spacing.massive4, isOnline = isOnline)
        Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                fontSize = AstraTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.Black,
                color = Color(0xFFF6F7FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val routeLabel = when {
                contact.onionAddress.isNotBlank() -> "Tor ${contact.onionAddress.take(16)}..."
                contact.endpointId.isNotBlank() -> "Nearby ${contact.endpointId}"
                else -> "Identity saved"
            }
            Text(
                text = routeLabel,
                fontSize = AstraTheme.typography.labelMedium.fontSize,
                color = MutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isNearby -> {
                        PremiumPulseDot(color = AccentCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bluetooth / Wi-Fi Direct", fontSize = AstraTheme.typography.labelSmall.fontSize, color = AccentCyan)
                    }
                    isTorRouteAvailable -> {
                        PremiumPulseDot(color = NeonGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tor route available", fontSize = AstraTheme.typography.labelSmall.fontSize, color = NeonGreen)
                    }
                    else -> {
                        Box(modifier = Modifier.size(AstraTheme.spacing.small).clip(CircleShape).background(DimGray))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Offline", fontSize = AstraTheme.typography.labelSmall.fontSize, color = MutedGray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Encrypted identity", fontSize = AstraTheme.typography.labelSmall.fontSize, color = Color(0xFFB9C3D4))
        }
    }
}
