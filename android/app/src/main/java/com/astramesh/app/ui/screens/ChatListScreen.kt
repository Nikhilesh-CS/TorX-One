package com.astramesh.app.ui.screens

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
    val contacts by db.contactDao().getAllContacts().collectAsState(initial = emptyList())
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
        containerColor = DeepBlack,
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { showShareContact = true },
                    containerColor = CardSurface,
                    contentColor = AccentCyan
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Share contact")
                }
                FloatingActionButton(
                    onClick = { showAddContact = true },
                    containerColor = AccentViolet
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
                    .padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 8.dp)
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
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            if (pendingRequests.isNotEmpty()) {
                item {
                    Text(
                        "Connection Requests",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentPink,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentCyan,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MutedGray,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
                )
            }

            if (contacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Bluetooth, null, modifier = Modifier.size(48.dp), tint = DimGray)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No conversations yet", fontSize = 16.sp, color = MutedGray)
                            Text(
                                "Add a contact key for Tor, or connect to someone nearby",
                                fontSize = 13.sp,
                                color = DimGray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(contacts) { contact ->
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
            title = { Text("Your Contact Key", color = SoftWhite) },
            text = {
                Column {
                    Text(
                        "Share this with distant contacts. Includes your Tor .onion address when connected.",
                        fontSize = 13.sp,
                        color = MutedGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(myContactString, fontSize = 11.sp, color = AccentCyan)
                }
            },
            containerColor = CardSurface,
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Astra Contact", myContactString))
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                    showShareContact = false
                }) {
                    Text("Copy", color = AccentViolet)
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareContact = false }) {
                    Text("Close", color = MutedGray)
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AstraAvatar(name = request.name, size = 48.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(request.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SoftWhite)
                Text("wants to connect", fontSize = 13.sp, color = MutedGray)
            }
            IconButton(
                onClick = onAccept,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(NeonGreen.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Rounded.CheckCircle, "Accept", tint = NeonGreen, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onReject,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(AccentPink.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Rounded.Close, "Reject", tint = AccentPink, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun NearbyDeviceChip(device: NearbyDevice, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        AstraAvatar(name = device.name, size = 64.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(device.name, fontSize = 11.sp, color = MutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 70.dp))
    }
}

@Composable
fun ContactRow(contact: ContactEntity, isConnected: Boolean, lastMessageText: String, lastMessageTime: Long?, unreadCount: Int = 0, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, size = 56.dp, isOnline = isConnected)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = SoftWhite, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (lastMessageTime != null) {
                    val timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastMessageTime))
                    Text(timeString, fontSize = 11.sp, color = if (unreadCount > 0) AccentCyan else MutedGray)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(lastMessageText, fontSize = 14.sp, color = if (unreadCount > 0) SoftWhite else DimGray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier.padding(start = 8.dp).size(20.dp).clip(CircleShape).background(AccentCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(unreadCount.toString(), fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
