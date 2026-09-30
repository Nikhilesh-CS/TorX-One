package com.torxone.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.groups.GroupAvatarStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NewGroupStep {
    SELECT_MEMBERS,
    ENTER_DETAILS
}

/**
 * NewGroupScreen — Two-step group creation flow:
 * 1. Multi-contact selection with search and chips.
 * 2. Group details (name, avatar, summary) and creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    contacts: List<ContactEntity>,
    onCreateGroup: suspend (title: String, selectedMembers: List<ContactEntity>, avatarHash: String?) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(NewGroupStep.SELECT_MEMBERS) }
    val selectedContactIds = remember { mutableStateListOf<String>() }
    var searchQuery by remember { mutableStateOf("") }
    var groupTitle by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var avatarHash by remember { mutableStateOf<String?>(null) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size().toLong() + read <= GroupAvatarStorage.MAX_BYTES) { "Group image exceeds 10 MB" }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: error("Unable to read selected image")
                }
                GroupAvatarStorage.save(context, bytes)
            }.onSuccess {
                avatarHash = it
                avatarError = null
            }.onFailure { avatarError = it.message ?: "Unable to use selected image" }
        }
    }
    val avatarBitmap = remember(avatarHash) {
        GroupAvatarStorage.resolve(context, avatarHash)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
    }

    val filteredContacts = remember(contacts, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            contacts
        } else {
            contacts.filter { it.displayName.lowercase().contains(query) }
        }
    }

    val selectedContacts = remember(contacts, selectedContactIds.toList()) {
        contacts.filter { it.contactId in selectedContactIds }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (step == NewGroupStep.ENTER_DETAILS) {
                                step = NewGroupStep.SELECT_MEMBERS
                            } else {
                                onBackClick()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (step == NewGroupStep.SELECT_MEMBERS) "New group" else "Name your group",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (step == NewGroupStep.SELECT_MEMBERS) {
                                if (selectedContacts.isEmpty()) "Add members" else "${selectedContacts.size} selected"
                            } else {
                                "${selectedContacts.size} members"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (step == NewGroupStep.SELECT_MEMBERS) {
                if (selectedContactIds.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { step = NewGroupStep.ENTER_DETAILS }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                    }
                }
            } else {
                FloatingActionButton(
                    onClick = {
                        if (groupTitle.isNotBlank() && !isCreating) {
                            isCreating = true
                            scope.launch {
                                try { onCreateGroup(groupTitle.trim(), selectedContacts, avatarHash) }
                                catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    avatarError = error.message ?: "Group creation failed"
                                    android.widget.Toast.makeText(context, avatarError, android.widget.Toast.LENGTH_LONG).show()
                                } finally { isCreating = false }
                            }
                        }
                    },
                    containerColor = if (groupTitle.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = "Create group")
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        if (step == NewGroupStep.SELECT_MEMBERS) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Search bar
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search contacts...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Selected contact chips row
                AnimatedVisibility(visible = selectedContacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedContacts.forEach { contact ->
                            InputChip(
                                selected = true,
                                onClick = { selectedContactIds.remove(contact.contactId) },
                                label = { Text(contact.displayName) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                if (selectedContacts.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                // Contact list
                if (filteredContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isEmpty()) "No direct contacts available.\nAdd contacts via QR code first." else "No matching contacts found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredContacts, key = { it.contactId }) { contact ->
                            val isSelected = contact.contactId in selectedContactIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) {
                                            selectedContactIds.remove(contact.contactId)
                                        } else {
                                            selectedContactIds.add(contact.contactId)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.displayName.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Mesh peer",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selectedContactIds.add(contact.contactId)
                                        } else {
                                            selectedContactIds.remove(contact.contactId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // STEP 2: ENTER_DETAILS
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header with Avatar and Title Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .clickable { avatarPicker.launch("image/*") }
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Group image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = "Choose group image",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    OutlinedTextField(
                        value = groupTitle,
                        onValueChange = { if (it.length <= 64) groupTitle = it },
                        label = { Text("Group name") },
                        placeholder = { Text("e.g. Project Alpha") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Text(
                    text = "Provide a group subject and optional group icon. Messages are end-to-end encrypted across all members.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                avatarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (avatarHash != null) {
                    TextButton(onClick = { avatarHash = null }) { Text("Remove group image") }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Summary of Members
                Text(
                    text = "Members: ${selectedContacts.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedContacts, key = { it.contactId }) { contact ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.displayName.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Member",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
