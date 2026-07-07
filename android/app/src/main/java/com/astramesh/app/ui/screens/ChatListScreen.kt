package com.astramesh.app.ui.screens

import com.astramesh.app.ui.theme.AstraTheme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.ContactEntity
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.network.ConnectionRequest
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.NearbyDevice
import com.astramesh.app.network.TorManager
import com.astramesh.app.ui.components.AstraAvatar
import com.astramesh.app.ui.components.DiscoveryStatusChip
import com.astramesh.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    identityManager: IdentityManager,
    navController: NavController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager,
    messageRouter: MessageRouter
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by db.contactDao().getAllContacts().collectAsState(initial = null)
    val nearbyDevices by nearbyManager.nearbyDevices.collectAsState()
    val pendingRequests by nearbyManager.pendingRequests.collectAsState()
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsState()
    val connectionStatus by nearbyManager.connectionStatus.collectAsState()
    val isTorReady by torManager.isTorReady.collectAsState()
    val torStatus by torManager.torStatus.collectAsState()
    val onionAddress by torManager.onionAddress.collectAsState()

    var showAddContact by remember { mutableStateOf(false) }
    var showShareContact by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val myContactString = remember(identityManager, onionAddress) {
        val identity = identityManager.loadIdentity()
        identity?.let {
            CryptoManager.createContactString(it, onionAddress.ifBlank { null })
        } ?: ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.small)) {
                FloatingActionButton(
                    onClick = { showShareContact = true },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Share contact")
                }
                FloatingActionButton(
                    onClick = { showAddContact = true },
                    containerColor = MaterialTheme.colorScheme.secondary
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add contact")
                }
            }
        },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(top = AstraTheme.spacing.standard, start = AstraTheme.spacing.large, end = AstraTheme.spacing.large, bottom = AstraTheme.spacing.small)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Messages", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                    Column(horizontalAlignment = Alignment.End) {
                        DiscoveryStatusChip(status = connectionStatus)
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            contentPadding = PaddingValues(bottom = AstraTheme.spacing.massive5)
        ) {
            if (pendingRequests.isNotEmpty()) {
                item {
                    Text(
                        "Connection Requests",
                        fontSize = AstraTheme.typography.bodyMedium.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = AstraTheme.spacing.large, top = AstraTheme.spacing.standard, bottom = AstraTheme.spacing.small)
                    )
                }
                items(pendingRequests) { request ->
                    ConnectionRequestCard(
                        request = request,
                        onAccept = { nearbyManager.acceptConnection(request.endpointId) },
                        onReject = { nearbyManager.rejectConnection(request.endpointId) }
                    )
                }
            }

            if (nearbyDevices.isNotEmpty()) {
                item {
                    Text(
                        "Nearby",
                        fontSize = AstraTheme.typography.bodyMedium.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = AstraTheme.spacing.large, top = AstraTheme.spacing.standard, bottom = AstraTheme.spacing.small)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = AstraTheme.spacing.standard),
                        horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium)
                    ) {
                        items(nearbyDevices) { device ->
                            NearbyDeviceChip(
                                device = device,
                                onClick = { nearbyManager.requestConnection(device.endpointId) }
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Messages",
                    fontSize = AstraTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AstraTheme.spacing.large, top = AstraTheme.spacing.large, bottom = AstraTheme.spacing.small)
                )
            }

            if (contacts == null) {
                item {
                    com.astramesh.app.ui.components.AstraLoadingState(message = "Loading contacts...")
                }
            } else if (contacts!!.isEmpty()) {
                item {
                    com.astramesh.app.ui.components.AstraEmptyState(
                        title = "No conversations yet",
                        message = "Add a contact key for Tor, or connect to someone nearby"
                    )
                }
            } else {
                items(contacts!!) { contact ->
                    val isConnected = connectedEndpoints.contains(contact.endpointId) ||
                        (contact.onionAddress.isNotBlank() && isTorReady)
                    
                    val lastMessage by db.messageDao().getLastMessageForContact(contact.signingPublicKey).collectAsState(initial = null)
                    val unreadCount by db.messageDao().getUnreadCountForContact(contact.signingPublicKey).collectAsState(initial = 0)
                    
                    val lastMessageText = lastMessage?.text ?: "Tap to chat..."
                    val lastMessageTime = lastMessage?.timestamp

                    ContactRow(
                        contact = contact, 
                        isConnected = isConnected, 
                        lastMessageText = lastMessageText,
                        lastMessageTime = lastMessageTime,
                        unreadCount = unreadCount,
                        onClick = {
                            navController.navigate("chat/${contact.signingPublicKey}")
                        }
                    )
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onContactAdded = { contactString ->
                scope.launch {
                    val parsed = CryptoManager.parseContactString(contactString.trim())
                    if (parsed == null) {
                        Toast.makeText(context, "Invalid contact string", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
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
                    showAddContact = false
                    Toast.makeText(context, "Contact added", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showShareContact && myContactString.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showShareContact = false },
            title = { Text("Your Contact Key", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    Text(
                        "Share this with distant contacts. Includes your Tor .onion address when connected.",
                        fontSize = AstraTheme.typography.bodySmall.fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.medium))
                    Text(myContactString, fontSize = AstraTheme.typography.labelSmall.fontSize, color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Astra Contact", myContactString))
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                    showShareContact = false
                }) {
                    Text("Copy", color = MaterialTheme.colorScheme.secondary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareContact = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            torManager = torManager,
            nearbyManager = nearbyManager,
            onDismiss = { showDiagnostics = false }
        )
    }
}

@Composable
fun ConnectionRequestCard(
    request: ConnectionRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.tiny),
        shape = RoundedCornerShape(AstraTheme.spacing.standard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AstraTheme.spacing.standard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AstraAvatar(name = request.name, size = AstraTheme.spacing.massive3)
            Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(request.name, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("wants to connect", fontSize = AstraTheme.typography.bodySmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = onAccept,
                modifier = Modifier.size(AstraTheme.spacing.massive2).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Rounded.CheckCircle, "Accept", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(AstraTheme.spacing.extraLarge))
            }
            Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            IconButton(
                onClick = onReject,
                modifier = Modifier.size(AstraTheme.spacing.massive2).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Rounded.Close, "Reject", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(AstraTheme.spacing.extraLarge))
            }
        }
    }
}

@Composable
fun NearbyDeviceChip(device: NearbyDevice, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        AstraAvatar(name = device.name, size = AstraTheme.spacing.massive5)
        Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
        Text(device.name, fontSize = AstraTheme.typography.labelSmall.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = AstraTheme.spacing.massive5))
    }
}

@Composable
fun ContactRow(contact: ContactEntity, isConnected: Boolean, lastMessageText: String, lastMessageTime: Long?, unreadCount: Int = 0, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = AstraTheme.spacing.large, vertical = AstraTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, size = AstraTheme.spacing.massive4, isOnline = isConnected)
        Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (lastMessageTime != null) {
                    val timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastMessageTime))
                    Text(timeString, fontSize = AstraTheme.typography.labelSmall.fontSize, color = if (unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(lastMessageText, fontSize = AstraTheme.typography.bodyMedium.fontSize, color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier.padding(start = AstraTheme.spacing.small).size(AstraTheme.spacing.large).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(unreadCount.toString(), fontSize = AstraTheme.typography.labelSmall.fontSize, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


