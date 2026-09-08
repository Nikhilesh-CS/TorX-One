package com.torxone.app.ui.screens

import com.torxone.app.ui.theme.AstraTheme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.data.MusicNoteEntity
import com.torxone.app.identity.IdentityManager
import com.torxone.app.music.DetectedMusicTrack
import com.torxone.app.music.MusicNoteRepositoryImpl
import com.torxone.app.music.MusicNoteVisibility
import com.torxone.app.music.MusicProviderResolver
import com.torxone.app.network.ConnectionRequest
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.NearbyDevice
import com.torxone.app.network.TorManager
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.DiscoveryStatusChip
import com.torxone.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val conversationsState by remember(db) {
        db.conversationDao().getUnifiedConversations().map<List<com.torxone.app.data.UnifiedConversation>, List<com.torxone.app.data.UnifiedConversation>?> { it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val conversations = conversationsState.orEmpty()
    
    val contactsState by remember(db) {
        db.contactDao().getAllContacts().map<List<com.torxone.app.data.ContactEntity>, List<com.torxone.app.data.ContactEntity>?> { it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val contacts = contactsState.orEmpty()
    val nearbyDevices by nearbyManager.nearbyDevices.collectAsStateWithLifecycle()
    val pendingRequests by nearbyManager.pendingRequests.collectAsStateWithLifecycle()
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsStateWithLifecycle()
    val connectionStatus by nearbyManager.connectionStatus.collectAsStateWithLifecycle()
    val isTorReady by torManager.isTorReady.collectAsStateWithLifecycle()
    val torStatus by torManager.torStatus.collectAsStateWithLifecycle()
    val onionAddress by torManager.onionAddress.collectAsStateWithLifecycle()
    val musicRepository = remember(db) { MusicNoteRepositoryImpl(db.musicNoteDao()) }
    val musicNotes by musicRepository.observeActiveNotes().collectAsStateWithLifecycle(initialValue = emptyList())
    val presenceStates by (com.torxone.app.service.TorXOneService.getInstance()
        ?.presenceManager
        ?.presence
        ?: kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.torxone.app.presence.PresenceState>>(emptyMap()))
        .collectAsStateWithLifecycle()
    val listenTogetherManager = com.torxone.app.service.TorXOneService.getInstance()?.listenTogetherManager
    val listenTogetherState by (listenTogetherManager?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(com.torxone.app.music.ListenTogetherState()))
        .collectAsStateWithLifecycle()
    val musicResolver = remember(context) { MusicProviderResolver(context) }
    val chatListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    var showAddContact by remember { mutableStateOf(false) }
    var showShareContact by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var contactToDelete by remember { mutableStateOf<ContactEntity?>(null) }
    var showCreateMusicNote by remember { mutableStateOf(false) }
    var selectedMusicNote by remember { mutableStateOf<MusicNoteEntity?>(null) }
    var listenTogetherNote by remember { mutableStateOf<MusicNoteEntity?>(null) }
    val mySigningKey = remember(identityManager) {
        identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) }.orEmpty()
    }

    val myContactString = remember(identityManager, onionAddress) {
        val identity = identityManager.loadIdentity()
        identity?.let {
            CryptoManager.createContactString(it, onionAddress.ifBlank { null })
        } ?: ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        Scaffold(
            containerColor = AppBackground,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddContact = true },
                    containerColor = TorXPrimary,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = "New chat")
                }
            },
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppBackground)
                        .padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "TorX One",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryText
                            )
                            Text(
                                "Private mesh messenger",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SecondaryText
                            )
                        }
                        IconButton(
                            onClick = { showShareContact = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SurfaceCard)
                                .border(1.dp, BorderColor, CircleShape)
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.QrCode, contentDescription = "My Key", tint = TorXPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Clean full-width status card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        com.torxone.app.ui.components.PulsingDot(color = BluetoothAccent, size = 8.dp)
                        Text(
                            text = if (connectionStatus.isNotBlank()) connectionStatus else "Searching nearby...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { paddingValues ->
            LazyColumn(
                state = chatListState,
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentPadding = PaddingValues(bottom = AstraTheme.spacing.massive5, top = 4.dp)
            ) {
            item {
                AstraMusicNotesRow(
                    db = db,
                    notes = musicNotes,
                    mySigningKey = mySigningKey,
                    onCreate = { showCreateMusicNote = true },
                    onOpen = { selectedMusicNote = it }
                )
            }

            if (pendingRequests.isNotEmpty()) {
                item {
                    PremiumSectionHeader("Connection Requests")
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
                    PremiumSectionHeader("Nearby")
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                PremiumSectionHeader(
                    title = "Messages",
                    action = {
                        IconButton(
                            onClick = { navController.navigate("create_group") },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Add,
                                contentDescription = "Create Group",
                                tint = TorXPrimary
                            )
                        }
                    }
                )
            }

            if (conversationsState == null) {
                item {
                    com.torxone.app.ui.components.AstraEmptyState(
                        title = "Loading conversations",
                        message = "Restoring your secure chat list..."
                    )
                }
            } else if (conversations.isEmpty()) {
                item {
                    com.torxone.app.ui.components.AstraEmptyState(
                        title = "No conversations yet",
                        message = "Add a contact key for Tor, or connect to someone nearby"
                    )
                }
            } else {
                items(conversations) { conversation ->
                    val isNearbyOnline = connectedEndpoints.contains(conversation.endpointId)
                    val livePresence = presenceStates[conversation.id]
                    val isContactOnline = isNearbyOnline || livePresence?.activity == "online"
                    val routeLabel = when {
                        conversation.type == "group" -> "Secure Group"
                        isNearbyOnline -> "Nearby route ready"
                        livePresence != null -> livePresence.label
                        conversation.onionAddress.isNotBlank() && isTorReady -> "Tor route standby"
                        conversation.onionAddress.isNotBlank() -> "Tor route offline"
                        else -> "Secure route standby"
                    }
                    
                    val lastMessage by db.messageDao().getLastMessageForConversation(conversation.id, conversation.type).collectAsStateWithLifecycle(initialValue = null)
                    val unreadCount by db.messageDao().getUnreadCountForConversation(conversation.id, conversation.type).collectAsStateWithLifecycle(initialValue = 0)
                    val profile by db.profileDao().getProfile(conversation.id).collectAsStateWithLifecycle(initialValue = null)
                    
                    val lastMessageText = lastMessage?.text ?: "Tap to chat..."
                    val lastMessageTime = lastMessage?.timestamp

                    // Use ContactRow for both types for Phase 1. 
                    // ContactRow takes ContactEntity in the signature, we might need to modify ContactRow or map it back
                    // Actually ContactRow uses ContactEntity for name/signingPublicKey.
                    // Wait, I need to map UnifiedConversation to a ContactEntity temporarily or change ContactRow.
                    // Let's modify ContactRow call to use conversation properties
                    ContactRow(
                        contact = ContactEntity(conversation.id, "", conversation.name, conversation.endpointId, conversation.onionAddress, false, 0L),
                        avatarModel = conversation.avatarUri ?: profile?.avatarLocalPath,
                        isOnline = isContactOnline,
                        routeLabel = routeLabel,
                        lastMessageText = lastMessageText,
                        lastMessageTime = lastMessageTime,
                        unreadCount = unreadCount,
                        onClick = {
                            if (conversation.type == "group") {
                                navController.navigate("chat/${conversation.type}/${conversation.id}")
                            } else {
                                navController.navigate("chat/${conversation.type}/${conversation.id}")
                            }
                        },
                        onLongClick = {
                            if (conversation.type == "direct") {
                                contactToDelete = ContactEntity(conversation.id, "", conversation.name, conversation.endpointId, conversation.onionAddress, false, 0L)
                            }
                        }
                    )
                }
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

    if (showCreateMusicNote) {
        CreateMusicNoteDialog(
            onDismiss = { showCreateMusicNote = false },
            onPublish = { track, text, visibility, durationHours ->
                val manager = com.torxone.app.service.TorXOneService.getInstance()?.musicNoteManager
                if (manager == null) {
                    Toast.makeText(context, "Music sync service not ready", Toast.LENGTH_SHORT).show()
                } else {
                    manager.publishCurrentNote(track, text, visibility, durationHours)
                    showCreateMusicNote = false
                    Toast.makeText(context, "Music Note published", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    selectedMusicNote?.let { note ->
        MusicNoteViewerDialog(
            note = note,
            isOwnNote = note.authorPublicKey == mySigningKey,
            onDismiss = { selectedMusicNote = null },
            onListen = {
                val opened = musicResolver.openTrack(note.toDetectedTrack())
                if (!opened) Toast.makeText(context, "No supported music app found", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                com.torxone.app.service.TorXOneService.getInstance()?.musicNoteManager?.deleteMyNote()
                selectedMusicNote = null
                Toast.makeText(context, "Music Note deleted", Toast.LENGTH_SHORT).show()
            },
            onListenTogether = {
                listenTogetherNote = note
            }
        )
    }

    listenTogetherNote?.let { note ->
        ListenTogetherContactPicker(
            contacts = contacts.filter { it.signingPublicKey != mySigningKey },
            onDismiss = { listenTogetherNote = null },
            onSelect = { contact ->
                listenTogetherManager?.inviteSession(contact.signingPublicKey, note)
                musicResolver.openTrack(note.toDetectedTrack())
                listenTogetherNote = null
                selectedMusicNote = null
                Toast.makeText(context, "Listen Together invite sent to ${contact.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (listenTogetherState.incomingInvite) {
        val inviterName = contacts.firstOrNull { it.signingPublicKey == listenTogetherState.peerKey }?.name ?: "Unknown Contact"
        AlertDialog(
            onDismissRequest = { listenTogetherManager?.rejectIncomingInvite() },
            title = { Text("Listen Together?") },
            text = { Text("$inviterName wants to sync playback with you. TorX One only shares playback metadata, not music audio.") },
            confirmButton = {
                Button(onClick = {
                    val state = listenTogetherState
                    scope.launch {
                        val note = withContext(Dispatchers.IO) { db.musicNoteDao().getNote(state.noteId) }
                        val track = note?.toDetectedTrack() ?: state.lastEvent?.track
                        if (track != null) {
                            musicResolver.openTrack(track)
                            listenTogetherManager?.acceptIncomingInvite()
                            Toast.makeText(context, "Listen Together accepted", Toast.LENGTH_SHORT).show()
                        } else {
                            listenTogetherManager?.rejectIncomingInvite()
                            Toast.makeText(context, "Music note is no longer available", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { listenTogetherManager?.rejectIncomingInvite() }) {
                    Text("Reject")
                }
            }
        )
    }

    if (listenTogetherState.active || listenTogetherState.awaitingResponse) {
        ListenTogetherSessionDialog(
            state = listenTogetherState,
            peerName = contacts.firstOrNull { it.signingPublicKey == listenTogetherState.peerKey }?.name ?: "Unknown Contact",
            onDismiss = { },
            onPlay = {
                val state = listenTogetherState
                listenTogetherManager?.sendEvent(state.peerKey, state.noteId, state.sessionId, com.torxone.app.music.ListenTogetherEventType.PLAY, state.lastEvent?.positionMs ?: 0L)
            },
            onPause = {
                val state = listenTogetherState
                listenTogetherManager?.sendEvent(state.peerKey, state.noteId, state.sessionId, com.torxone.app.music.ListenTogetherEventType.PAUSE, state.lastEvent?.positionMs ?: 0L)
            },
            onSync = {
                val state = listenTogetherState
                listenTogetherManager?.sendEvent(state.peerKey, state.noteId, state.sessionId, com.torxone.app.music.ListenTogetherEventType.POSITION_SYNC, state.lastEvent?.positionMs ?: 0L)
            },
            onEnd = {
                listenTogetherManager?.endSession()
            }
        )
    }

    contactToDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("Delete ${contact.name}?") },
            text = {
                Text("This removes the person, chat history, profile cache, and transfer records from this phone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.mediaTransferDao().deleteTransfersForContact(contact.signingPublicKey)
                            db.messageDao().clearChat(contact.signingPublicKey)
                            db.profileDao().deleteProfile(contact.signingPublicKey)
                            db.contactDao().deleteContact(contact.signingPublicKey)
                            withContext(Dispatchers.Main) {
                                contactToDelete = null
                                Toast.makeText(context, "Deleted ${contact.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.tiny))
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PremiumSectionHeader(
    title: String,
    accent: Color = TorXPrimary,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryText,
            maxLines = 1
        )
        action?.invoke()
    }
}

@Composable
private fun ListenTogetherContactPicker(
    contacts: List<ContactEntity>,
    onDismiss: () -> Unit,
    onSelect: (ContactEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen Together") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose who should receive the listening invite.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (contacts.isEmpty()) {
                    Text("No contacts available.", color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(contacts, key = { it.signingPublicKey }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                                    .clickable { onSelect(contact) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AstraAvatar(name = contact.name, size = 42.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(contact.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (contact.onionAddress.isNotBlank()) "Tor route available" else "Nearby/contact route",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ListenTogetherSessionDialog(
    state: com.torxone.app.music.ListenTogetherState,
    peerName: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSync: () -> Unit,
    onEnd: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.awaitingResponse) "Waiting for $peerName" else "Listening with $peerName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.awaitingResponse) "Invite sent. Waiting for accept or reject."
                    else "Playback events are synchronized as encrypted metadata. Audio stays inside each music app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.lastEvent?.track?.let { track ->
                    Text(track.trackName, fontWeight = FontWeight.Bold)
                    Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (state.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPlay) { Text("Play") }
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onSync) { Text("Sync") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onEnd) {
                Text(if (state.awaitingResponse) "Cancel" else "End")
            }
        }
    )
}

@Composable
private fun AstraMusicNotesRow(
    db: AppDatabase,
    notes: List<MusicNoteEntity>,
    mySigningKey: String,
    onCreate: () -> Unit,
    onOpen: (MusicNoteEntity) -> Unit
) {
    val localProfile by db.profileDao().getProfile("LOCAL_USER").collectAsStateWithLifecycle(initialValue = null)
    val myNote = notes.firstOrNull { it.authorPublicKey == mySigningKey }
    val contactNotes = notes.filterNot { it.authorPublicKey == mySigningKey }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "TorX One Music",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText
                    )
                    Text(
                        "Music notes without sharing audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryText
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(TorXPrimarySoft)
                        .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Metadata only",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = TorXPrimary
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    MusicNoteAvatarCard(
                        title = "Your Note",
                        subtitle = myNote?.trackName ?: "Share music",
                        avatarUri = localProfile?.avatarLocalPath,
                        albumArtUri = myNote?.albumArtUri,
                        onClick = { myNote?.let(onOpen) ?: onCreate() }
                    )
                }
                items(contactNotes, key = { it.noteId }) { note ->
                    val profile by db.profileDao().getProfile(note.authorPublicKey).collectAsStateWithLifecycle(initialValue = null)
                    MusicNoteAvatarCard(
                        title = note.authorName,
                        subtitle = note.trackName,
                        avatarUri = profile?.avatarLocalPath,
                        albumArtUri = note.albumArtUri,
                        onClick = { onOpen(note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicNoteAvatarCard(
    title: String,
    subtitle: String,
    avatarUri: String?,
    albumArtUri: String?,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceSecondary)
            .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(SurfaceCard)
                .border(1.dp, BorderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUri != null) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else if (albumArtUri != null) {
                AsyncImage(
                    model = albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = TorXPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = PrimaryText,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = SecondaryText
        )
    }
}

@Composable
private fun CreateMusicNoteDialog(
    onDismiss: () -> Unit,
    onPublish: (DetectedMusicTrack, String, MusicNoteVisibility, Int) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var durationHours by remember { mutableStateOf(24) }
    var visibility by remember { mutableStateOf(MusicNoteVisibility.CONTACTS) }
    var manualTitle by remember { mutableStateOf("") }
    var manualArtist by remember { mutableStateOf("") }
    var manualProvider by remember { mutableStateOf("Manual") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Music Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the song manually. This release does not request Android notification access, so Play Protect will not see TorX One Music as a sensitive-data feature.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = manualTitle,
                    onValueChange = { manualTitle = it.take(80) },
                    label = { Text("Song name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = manualArtist,
                    onValueChange = { manualArtist = it.take(80) },
                    label = { Text("Artist") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = manualProvider,
                    onValueChange = { manualProvider = it.take(40) },
                    label = { Text("Music app") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it.take(60) },
                    label = { Text("Optional note") },
                    singleLine = true,
                    supportingText = { Text("${noteText.length}/60") }
                )
                SelectorRow(
                    label = "Duration",
                    options = listOf(6, 12, 24, 48).map { it to "${it}h" },
                    selected = durationHours,
                    onSelected = { durationHours = it }
                )
                SelectorRow(
                    label = "Privacy",
                    options = listOf(
                        MusicNoteVisibility.ONLY_ME to "Only Me",
                        MusicNoteVisibility.FAVORITES to "Favorites",
                        MusicNoteVisibility.CONTACTS to "Contacts",
                        MusicNoteVisibility.EVERYONE to "Everyone"
                    ),
                    selected = visibility,
                    onSelected = { visibility = it }
                )
            }
        },
        confirmButton = {
            val publishTrack = manualMusicTrack(manualTitle, manualArtist, manualProvider)
            Button(
                enabled = publishTrack != null,
                onClick = { publishTrack?.let { onPublish(it, noteText, visibility, durationHours) } }
            ) {
                Text("Publish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun <T> SelectorRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(text) }
                )
            }
        }
    }
}

@Composable
private fun MusicNoteViewerDialog(
    note: MusicNoteEntity,
    isOwnNote: Boolean,
    onDismiss: () -> Unit,
    onListen: () -> Unit,
    onDelete: () -> Unit,
    onListenTogether: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(note.authorName, color = PrimaryText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(TorXPrimarySoft)
                        .border(1.dp, BorderColor, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (note.albumArtUri != null) {
                        AsyncImage(
                            model = note.albumArtUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(52.dp), tint = TorXPrimary)
                    }
                }
                if (note.text.isNotBlank()) Text(note.text, fontWeight = FontWeight.SemiBold, color = PrimaryText)
                Text(note.trackName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PrimaryText)
                Text(note.artist, color = SecondaryText)
                Text("${providerLabel(note.provider)} • ${timeLeftLabel(note.expiresAt)} left", style = MaterialTheme.typography.labelMedium, color = TorXPrimary)
                if (isOwnNote) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = ErrorRed)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Note", color = ErrorRed)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onListen,
                colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary, contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Listen")
            }
        },
        dismissButton = {
            TextButton(onClick = onListenTogether) {
                Text("Listen Together", color = TorXPrimary)
            }
        },
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(20.dp)
    )
}

private fun MusicNoteEntity.toDetectedTrack(): DetectedMusicTrack {
    return DetectedMusicTrack(
        trackId = trackId,
        trackName = trackName,
        artist = artist,
        album = album,
        albumArtUri = albumArtUri,
        provider = provider,
        playbackPositionMs = playbackPositionMs
    )
}

private fun providerLabel(provider: String): String {
    return provider.substringAfterLast('.').replaceFirstChar { it.titlecase() }.ifBlank { "Music" }
}

private fun timeLeftLabel(expiresAt: Long): String {
    val remainingMinutes = ((expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
    return when {
        remainingMinutes >= 60L -> "${remainingMinutes / 60L}h"
        else -> "${remainingMinutes}m"
    }
}

@Composable
fun ConnectionRequestCard(
    request: ConnectionRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AstraAvatar(name = request.name, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    request.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryText
                )
                Text(
                    "wants to connect securely",
                    fontSize = 13.sp,
                    color = SecondaryText
                )
            }
            IconButton(
                onClick = onAccept,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(TorXPrimarySoft)
            ) {
                Icon(Icons.Rounded.CheckCircle, "Accept", tint = SuccessGreen, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onReject,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(SurfaceSecondary)
            ) {
                Icon(Icons.Rounded.Close, "Reject", tint = SecondaryText, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun NearbyDeviceChip(device: NearbyDevice, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        AstraAvatar(name = device.name, size = 44.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            device.name,
            fontSize = 12.sp,
            color = PrimaryText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Nearby",
            fontSize = 11.sp,
            color = BluetoothAccent,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: ContactEntity,
    avatarModel: Any?,
    isOnline: Boolean,
    routeLabel: String,
    lastMessageText: String,
    lastMessageTime: Long?,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AstraTheme.spacing.large, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (unreadCount > 0) TorXPrimarySoft else SurfaceCard)
            .border(
                1.dp,
                if (unreadCount > 0) TorXPrimary.copy(alpha = 0.25f) else BorderColor,
                RoundedCornerShape(16.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, model = avatarModel, size = AstraTheme.spacing.massive4, isOnline = isOnline)
        Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, fontSize = AstraTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.SemiBold, color = PrimaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (lastMessageTime != null) {
                    val timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastMessageTime))
                    Text(timeString, fontSize = AstraTheme.typography.labelSmall.fontSize, color = if (unreadCount > 0) TorXPrimary else TextMuted)
                }
            }
            Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(lastMessageText, fontSize = AstraTheme.typography.bodyMedium.fontSize, color = if (unreadCount > 0) PrimaryText else SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier.padding(start = AstraTheme.spacing.small).size(22.dp).clip(CircleShape).background(TorXPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(unreadCount.toString(), fontSize = AstraTheme.typography.labelSmall.fontSize, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                routeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOnline) SuccessGreen else TextMuted,
                maxLines = 1
            )
        }
    }
}

private fun manualMusicTrack(title: String, artist: String, provider: String): DetectedMusicTrack? {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) return null
    val cleanArtist = artist.trim().ifBlank { "Unknown artist" }
    val cleanProvider = provider.trim().ifBlank { "Manual" }
    return DetectedMusicTrack(
        trackId = "manual:${cleanTitle.lowercase()}:${cleanArtist.lowercase()}",
        trackName = cleanTitle,
        artist = cleanArtist,
        album = "",
        albumArtUri = null,
        provider = cleanProvider,
        playbackPositionMs = 0L
    )
}




