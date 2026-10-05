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
import androidx.compose.runtime.saveable.rememberSaveable
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable(displayName) { mutableStateOf(displayName) }
    var editAbout by rememberSaveable(about) { mutableStateOf(about) }
    var editAvatarUri by rememberSaveable(avatarUri) { mutableStateOf(avatarUri) }
    val avatarUpdate: ProfileAvatarUpdate = when {
        editAvatarUri == avatarUri -> ProfileAvatarUpdate.Unchanged
        editAvatarUri == null -> ProfileAvatarUpdate.Removed
        else -> ProfileAvatarUpdate.Changed(requireNotNull(editAvatarUri))
    }
    var profileError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> editingPhoto = uri }
    editingPhoto?.let { selected ->
        com.torxone.app.ui.components.AvatarEditor(selected, onSaved = { stored ->
            editAvatarUri = stored
            profileError = null; editingPhoto = null
        }, onDismiss = { editingPhoto = null })
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
                    .clickable(enabled = isEditing && !savingProfile) { photoPicker.launch("image/*") }
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                com.torxone.app.ui.components.ProfileAvatar(editName.ifBlank { displayName }, editAvatarUri, Modifier.fillMaxSize())
            }

            if (isEditing) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !savingProfile, onClick = { photoPicker.launch("image/*") }) { Text("Change photo") }
                    if (editAvatarUri != null) {
                        TextButton(enabled = !savingProfile, onClick = {
                            editAvatarUri = null

                        }) { Text("Remove photo") }
                    }
                }
                profileError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                    enabled = !savingProfile,
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
                    enabled = !savingProfile,
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

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !savingProfile,
                        onClick = {
                            editName = displayName
                            editAbout = about
                            editAvatarUri = avatarUri

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

                                    isEditing = false
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    profileError = error.message ?: "Unable to save profile"
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
                        subtitle = if (identityId.isBlank()) "Identity unavailable" else identityId.take(16) + "… · Tap to copy",
                        onClick = {
                            if (identityId.isBlank()) return@ProfileInfoRow
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
