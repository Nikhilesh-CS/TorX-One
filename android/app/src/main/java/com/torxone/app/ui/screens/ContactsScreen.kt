package com.torxone.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.TorManager
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.PremiumPulseDot
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.BluetoothAccent
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.DisconnectedAccent
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorAccent
import com.torxone.app.ui.theme.TorXPrimary

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

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddContact by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppBackground)
                    .padding(top = 16.dp, start = 20.dp, end = 12.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Contacts",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryText
                        )
                        Text(
                            "Verified identities and active routes",
                            fontSize = 14.sp,
                            color = SecondaryText
                        )
                    }
                    IconButton(
                        onClick = { navController.navigate("scan_qr") },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SurfaceCard)
                            .border(1.dp, BorderColor, CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = "Scan QR",
                            tint = TorXPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { showAddContact = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(TorXPrimary)
                    ) {
                        Icon(
                            Icons.Rounded.PersonAdd,
                            contentDescription = "Add Contact",
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddContact = true },
                containerColor = TorXPrimary,
                contentColor = Color.White
            ) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = "Add Contact")
            }
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
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceCard)
                        .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                        .padding(AstraTheme.spacing.extraLarge)
                ) {
                    Icon(
                        Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(AstraTheme.spacing.massive5),
                        tint = TorXPrimary
                    )
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                    Text("No contacts yet", fontSize = 20.sp, color = PrimaryText, fontWeight = FontWeight.Bold)
                    Text(
                        "Add a Contact Key, scan an identity QR, or discover someone nearby.",
                        fontSize = AstraTheme.typography.bodyMedium.fontSize,
                        color = SecondaryText,
                        modifier = Modifier.padding(top = AstraTheme.spacing.small),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.large))
                    Button(
                        onClick = { showAddContact = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Contact", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                    Button(
                        onClick = { navController.navigate("scan_qr") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceCard,
                            contentColor = TorXPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan QR", fontWeight = FontWeight.SemiBold)
                    }
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
                            navController.navigate("chat/direct/${contact.signingPublicKey}")
                        }
                    )
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onScanQrClick = {
                showAddContact = false
                navController.navigate("scan_qr")
            },
            onContactAdded = { contactString ->
                scope.launch {
                    val parsed = CryptoManager.parseContactString(contactString.trim())
                    if (parsed == null) {
                        Toast.makeText(context, "Invalid contact string", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            db.contactDao().insertContact(
                                ContactEntity(
                                    signingPublicKey = CryptoManager.toHex(parsed.signingPublicKey),
                                    encryptionPublicKey = CryptoManager.toHex(parsed.encryptionPublicKey),
                                    name = parsed.name,
                                    onionAddress = parsed.onionAddress ?: "",
                                    isConnected = false
                                )
                            )
                        }
                    }.onSuccess {
                        showAddContact = false
                        Toast.makeText(context, "Contact added", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "Could not add contact", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
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
            .padding(horizontal = AstraTheme.spacing.large, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, model = avatarModel, size = AstraTheme.spacing.massive4, isOnline = isOnline)
        Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                fontSize = AstraTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryText,
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
                color = SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isNearby -> {
                        PremiumPulseDot(color = BluetoothAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bluetooth / Wi-Fi Direct", fontSize = AstraTheme.typography.labelSmall.fontSize, color = BluetoothAccent)
                    }
                    isTorRouteAvailable -> {
                        PremiumPulseDot(color = TorAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tor route available", fontSize = AstraTheme.typography.labelSmall.fontSize, color = TorAccent)
                    }
                    else -> {
                        Box(modifier = Modifier.size(AstraTheme.spacing.small).clip(CircleShape).background(DisconnectedAccent))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Offline", fontSize = AstraTheme.typography.labelSmall.fontSize, color = TextMuted)
                    }
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text("Encrypted identity", fontSize = AstraTheme.typography.labelSmall.fontSize, color = TextMuted)
        }
    }
}
