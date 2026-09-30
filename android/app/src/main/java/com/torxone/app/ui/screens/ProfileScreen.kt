package com.torxone.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.torxone.app.ui.theme.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.profile.FounderIdentity
import com.torxone.app.profile.ProfileAvatarUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Profile screen — view/edit display name, about, and profile photo.
 * Shows QR code for sharing contact invite.
 * Shows Founder badge if the identity signing key matches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    displayName: String,
    about: String,
    avatarUri: String?,
    identityId: String,
    signingPublicKey: ByteArray?,
    onUpdateProfile: suspend (name: String, about: String, avatarUpdate: ProfileAvatarUpdate) -> Unit,
    onBackClick: () -> Unit,
    onShowQr: () -> Unit
) {
    var savingProfile by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember(displayName) { mutableStateOf(displayName) }
    var editAbout by remember(about) { mutableStateOf(about) }
    var editAvatarUri by remember(avatarUri) { mutableStateOf(avatarUri) }
    var avatarUpdate by remember { mutableStateOf<ProfileAvatarUpdate>(ProfileAvatarUpdate.Unchanged) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> editingPhoto = uri }
    editingPhoto?.let { selected ->
        com.torxone.app.ui.components.AvatarEditor(selected, onSaved = { stored ->
            editAvatarUri = stored; avatarUpdate = ProfileAvatarUpdate.Changed(stored)
            avatarError = null; editingPhoto = null
        }, onDismiss = { editingPhoto = null })
    }

    val avatarBitmap = remember(editAvatarUri) {
        editAvatarUri?.let { value ->
            runCatching {
                val uri = Uri.parse(value)
                when (uri.scheme) {
                    "file" -> BitmapFactory.decodeFile(uri.path)
                    else -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }

    val isFounder = remember(signingPublicKey) {
        FounderIdentity.isFounderIdentity(signingPublicKey)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isEditing) {
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .clickable(enabled = isEditing) { photoPicker.launch("image/*") }
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val initials = displayName.take(2).uppercase()
                    Text(
                        text = if (initials.isNotEmpty()) initials else "?",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { photoPicker.launch("image/*") }) { Text("Change photo") }
                    if (editAvatarUri != null) {
                        TextButton(onClick = {
                            editAvatarUri = null
                            avatarUpdate = ProfileAvatarUpdate.Removed
                        }) { Text("Remove photo") }
                    }
                }
                avatarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Founder badge
            if (isFounder) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.goldAccent.copy(alpha = 0.15f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Stars,
                            contentDescription = "Founder",
                            tint = MaterialTheme.colorScheme.goldAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Founder",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.goldAccent
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (isEditing) {
                // Edit mode
                OutlinedTextField(
                    value = editName,
                    onValueChange = { if (it.length <= 40) editName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = editAbout,
                    onValueChange = { if (it.length <= 140) editAbout = it },
                    label = { Text("About") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    supportingText = { Text("${editAbout.length}/140") }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        enabled = !savingProfile,
                        onClick = {
                            editName = displayName
                            editAbout = about
                            editAvatarUri = avatarUri
                            avatarUpdate = ProfileAvatarUpdate.Unchanged
                            isEditing = false
                        },
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            savingProfile = true
                            scope.launch {
                                try {
                                    onUpdateProfile(editName.trim(), editAbout.trim(), avatarUpdate)
                                    avatarUpdate = ProfileAvatarUpdate.Unchanged
                                    isEditing = false
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    avatarError = error.message ?: "Unable to save profile"
                                } finally { savingProfile = false }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        enabled = editName.trim().isNotEmpty() && !savingProfile
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (savingProfile) "Saving…" else "Save")
                    }
                }
            } else {
                // View mode
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (about.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = about,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Info cards
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column {
                    // My QR Code
                    ProfileInfoRow(
                        icon = Icons.Filled.QrCode2,
                        title = "My QR Code",
                        subtitle = "Share your contact invite",
                        onClick = onShowQr
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Identity ID
                    ProfileInfoRow(
                        icon = Icons.Filled.Fingerprint,
                        title = "Identity ID",
                        subtitle = identityId.take(16) + "...",
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Identity ID", identityId))
                            android.widget.Toast.makeText(context, "Identity ID copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}
