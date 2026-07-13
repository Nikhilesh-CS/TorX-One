package com.torxone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.data.MessageEntity
import com.torxone.app.data.ProfileEntity
import com.torxone.app.identity.profile.FounderProfile
import com.torxone.app.ui.components.FounderBadge
import com.torxone.app.ui.components.FounderProfileCard
import com.torxone.app.ui.theme.AstraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ContactProfileUiState(
    val contact: ContactEntity? = null,
    val profile: ProfileEntity? = null,
    val mediaCount: Int = 0,
    val fileCount: Int = 0,
    val linkCount: Int = 0,
    val mediaItems: List<MessageEntity> = emptyList(),
    val fileItems: List<MessageEntity> = emptyList(),
    val linkItems: List<SharedLinkItem> = emptyList(),
    val isLoading: Boolean = true
)

private data class SharedLinkItem(
    val messageId: String,
    val url: String,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    navController: NavController,
    contactKey: String,
    db: AppDatabase
) {
    val scope = rememberCoroutineScope()
    var uiState by remember(contactKey) { mutableStateOf(ContactProfileUiState()) }

    LaunchedEffect(contactKey) {
        uiState = withContext(Dispatchers.IO) {
            val contact = db.contactDao().getContact(contactKey)
            val profile = db.profileDao().getProfileSync(contactKey)
            val messages = db.messageDao().getMessagesForContactSync(contactKey)
            val mediaItems = messages.filter { it.messageType in setOf("IMAGE", "VIDEO", "GIF", "STICKER") }
            val fileItems = messages.filter { it.messageType in setOf("DOCUMENT", "APK", "AUDIO", "VOICE") }
            val linkItems = messages.flatMap { message ->
                extractLinks(message.text).map { SharedLinkItem(message.messageId, it, message.timestamp) }
            }
            ContactProfileUiState(
                contact = contact,
                profile = profile,
                mediaCount = mediaItems.size,
                fileCount = fileItems.size,
                linkCount = linkItems.size,
                mediaItems = mediaItems,
                fileItems = fileItems,
                linkItems = linkItems,
                isLoading = false
            )
        }
    }

    val contact = uiState.contact
    val profile = uiState.profile
    val displayName = profile?.name?.takeIf { it.isNotBlank() } ?: contact?.name ?: "TorX One contact"
    val isFounderProfile = FounderProfile.isFounderProfile(contactKey)
    val avatarPath = profile?.avatarLocalPath
    val status = when {
        isFounderProfile -> profile?.statusMessage?.takeIf { it.isNotBlank() } ?: FounderProfile.statusMessage
        else -> profile?.statusMessage?.takeIf { it.isNotBlank() } ?: if (contact?.isConnected == true) "Online" else "Offline"
    }
    val bio = when {
        isFounderProfile -> profile?.bio?.takeIf { it.isNotBlank() } ?: FounderProfile.bio
        else -> profile?.bio?.takeIf { it.isNotBlank() } ?: "No bio shared yet."
    }
    val fingerprint = contactKey.chunked(4).take(8).joinToString(" ")
    var showAvatarViewer by remember { mutableStateOf(false) }
    var showSharedMedia by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
            val scrollState = rememberScrollState()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Hero Avatar with Parallax and Scale
                val scrollOffset = scrollState.value
                val scale = (1f - (scrollOffset / 1000f)).coerceIn(0.6f, 1f)
                val alpha = (1f - (scrollOffset / 500f)).coerceIn(0f, 1f)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationY = scrollOffset * 0.5f
                            this.alpha = alpha
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Blurred Background Fallback
                    AsyncImage(
                        model = avatarPath,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(50.dp) // Blur effect
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    
                    AsyncImage(
                        model = avatarPath,
                        contentDescription = "Contact Avatar",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = avatarPath != null) { showAvatarViewer = true },
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.large))

                // Name & Status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AstraTheme.spacing.standard),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = AstraTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isFounderProfile) {
                        Spacer(Modifier.width(10.dp))
                        FounderBadge()
                    }
                }
                
                Text(
                    text = status,
                    style = AstraTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

                if (isFounderProfile) {
                    FounderProfileCard(
                        modifier = Modifier.padding(horizontal = AstraTheme.spacing.standard),
                        torConnected = contact?.onionAddress?.isNotBlank() == true,
                        decentralizedEnabled = true
                    )
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))
                }

                // Action Row (Audio, Video, Search)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AstraTheme.spacing.extraLarge),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionChip(icon = Icons.Rounded.Phone, label = "Audio", enabled = false)
                    ActionChip(icon = Icons.Rounded.Videocam, label = "Video", enabled = false)
                    ActionChip(icon = Icons.Rounded.Search, label = "Search", enabled = true)
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

                // Media & Docs section preview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AstraTheme.spacing.standard)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showSharedMedia = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Media, Links, and Docs",
                            style = AstraTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Icon(Icons.Rounded.ChevronRight, "View All", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "${uiState.mediaCount} media • ${uiState.fileCount} files • ${uiState.linkCount} links",
                        style = AstraTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.small))

                // Bio Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AstraTheme.spacing.standard)
                ) {
                    Text(
                        text = "Bio",
                        style = AstraTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = bio,
                        style = AstraTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.small))

                // Identity Section
                ListItem(
                    headlineContent = { Text("Identity Fingerprint") },
                    supportingContent = { Text(fingerprint) },
                    leadingContent = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) }
                )
                ListItem(
                    headlineContent = { Text("Onion Address") },
                    supportingContent = { Text(contact?.onionAddress?.takeIf { it.isNotBlank() } ?: "Not shared") },
                    leadingContent = { Icon(Icons.Rounded.Router, contentDescription = null) }
                )
                ListItem(
                    headlineContent = { Text("Encryption") },
                    supportingContent = { Text("End-to-end encrypted identity ${if (contact?.encryptionPublicKey?.isNotBlank() == true) "verified" else "pending"}") },
                    leadingContent = { Icon(Icons.Rounded.Security, contentDescription = null) }
                )

                Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.small))

                // Destructive Actions
                ListItem(
                    headlineContent = { Text("Mute Notifications") },
                    leadingContent = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                    modifier = Modifier.clickable { }
                )
                ListItem(
                    headlineContent = { Text("Delete Chat") },
                    leadingContent = {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            db.messageDao().clearChat(contactKey)
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Block Contact") },
                    leadingContent = { 
                        Icon(Icons.Rounded.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error) 
                    },
                    colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.clickable { }
                )
                
                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))
            }

            if (showAvatarViewer && avatarPath != null) {
                Dialog(
                    onDismissRequest = { showAvatarViewer = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f))
                            .clickable { showAvatarViewer = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = avatarPath,
                            contentDescription = "Full size profile photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            if (showSharedMedia) {
                SharedMediaDialog(
                    uiState = uiState,
                    onDismiss = { showSharedMedia = false }
                )
            }
        }
}

@Composable
private fun SharedMediaDialog(
    uiState: ContactProfileUiState,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Media", "Files", "Links")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Media, Links, and Docs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${uiState.mediaCount} media • ${uiState.fileCount} files • ${uiState.linkCount} links",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> SharedMessageList(
                        emptyText = "No shared images or videos yet.",
                        messages = uiState.mediaItems
                    )
                    1 -> SharedMessageList(
                        emptyText = "No shared documents or audio yet.",
                        messages = uiState.fileItems
                    )
                    else -> SharedLinkList(uiState.linkItems)
                }
            }
        }
    }
}

@Composable
private fun SharedMessageList(
    emptyText: String,
    messages: List<MessageEntity>
) {
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.messageId }) { message ->
            SharedMessageRow(message)
        }
    }
}

@Composable
private fun SharedMessageRow(message: MessageEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            if (message.thumbnailUri != null || message.localUri != null) {
                AsyncImage(
                    model = message.thumbnailUri ?: message.localUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = when (message.messageType) {
                        "VIDEO" -> Icons.Rounded.Videocam
                        "AUDIO", "VOICE" -> Icons.Rounded.GraphicEq
                        "DOCUMENT", "APK" -> Icons.Rounded.Description
                        else -> Icons.Rounded.Image
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = message.fileName ?: message.text.takeIf { it.isNotBlank() } ?: message.messageType,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(message.messageType, message.fileSize?.let { formatFileSize(it) }, message.transferStatus).joinToString(" • "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SharedLinkList(links: List<SharedLinkItem>) {
    if (links.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No shared links yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(links, key = { it.messageId + it.url }) { link ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    link.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun extractLinks(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')') }.toList()
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
}

@Composable
fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { }
            .padding(8.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = label,
            style = AstraTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
