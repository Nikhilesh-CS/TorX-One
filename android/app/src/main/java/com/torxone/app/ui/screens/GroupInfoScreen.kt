package com.torxone.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.torxone.app.ui.components.rememberUiActionState
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
import com.torxone.app.chat.ChatService
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.GroupMemberEntity
import com.torxone.app.data.entity.GroupMemberRole
import com.torxone.app.data.entity.GroupMemberState
import com.torxone.app.groups.GroupService
import com.torxone.app.groups.GroupAvatarStorage
import com.torxone.app.notifications.NotificationPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * GroupInfoScreen — Management screen for Group details, participants, roles, and exit semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupId: String,
    database: TorXDatabase,
    groupService: GroupService,
    chatService: ChatService,
    localIdentityId: String?,
    onBackClick: () -> Unit,
    onGroupLeft: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = rememberUiActionState()
    val context = LocalContext.current

    val group by database.groupDao().observeById(groupId).collectAsState(initial = null)
    val conversation by database.conversationDao().observeById(groupId).collectAsState(initial = null)
    val allMembers by database.groupMemberDao().observeMembers(groupId).collectAsState(initial = emptyList())
    val allContacts by database.contactDao().observeAll().collectAsState(initial = emptyList())

    val contactsMap = remember(allContacts) { allContacts.associateBy { it.remoteIdentityId } }
    val activeMembers = remember(allMembers) {
        allMembers.filter { it.state == GroupMemberState.ACTIVE.name }
    }

    val selfMember = remember(activeMembers, localIdentityId) {
        activeMembers.find { it.memberIdentityId == localIdentityId }
    }
    val selfRole = remember(selfMember) {
        selfMember?.let { GroupMemberRole.fromString(it.role) } ?: GroupMemberRole.MEMBER
    }
    val canManage = selfRole == GroupMemberRole.OWNER || selfRole == GroupMemberRole.ADMIN
    val isOwner = selfRole == GroupMemberRole.OWNER

    var showEditTitleDialog by rememberSaveable { mutableStateOf(false) }
    var editTitleText by rememberSaveable { mutableStateOf("") }
    var showAddMemberSheet by rememberSaveable { mutableStateOf(false) }
    var selectedMemberForAction by remember { mutableStateOf<GroupMemberEntity?>(null) }
    var showLeaveConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showMuteDialog by rememberSaveable { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && canManage) actions.run("Could not update this group") {
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
                val hash = GroupAvatarStorage.save(context, bytes)
                check(groupService.updateGroupAvatar(groupId, hash)) { "Only an owner or admin can change the group image" }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; avatarError = it.message ?: "Unable to change group image" }
                .onSuccess { avatarError = null }
        }
    }
    val isMuted = NotificationPolicy.isConversationMuted(conversation?.mutedUntil)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Group info") },
                actions = {
                    if (canManage) {
                        IconButton(onClick = {
                            editTitleText = group?.title ?: ""
                            showEditTitleDialog = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit group name")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // Header: Avatar + Title + Metadata
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .clickable(enabled = canManage && !actions.busy) { avatarPicker.launch("image/*") }
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        com.torxone.app.ui.components.ProfileAvatar(group?.title ?: "Group", group?.avatarHash, Modifier.fillMaxSize())
                    }

                    if (canManage) {
                        Row {
                            TextButton(onClick = { avatarPicker.launch("image/*") }) { Text("Change image") }
                            if (group?.avatarHash != null) TextButton(onClick = {
                                actions.run("Could not update this group") {
                                    if (!groupService.updateGroupAvatar(groupId, null)) avatarError = "Unable to remove group image"
                                }
                            }) { Text("Remove") }
                        }
                    }
                    avatarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = group?.title ?: "Group",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Group · ${activeMembers.size} participants",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        group?.let { g ->
                            val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(g.createdAt))
                            Text(
                                text = "Created $formattedDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Encryption Info Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Messages and media are end-to-end encrypted with independent Double Ratchet pairwise sessions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick Actions (Mute, Add Member)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Mute notifications") },
                            supportingContent = { Text(if (isMuted) "Muted" else "Not muted") },
                            leadingContent = {
                                Icon(
                                    if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable(enabled = !actions.busy) { showMuteDialog = true }
                        )

                        if (canManage) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ListItem(
                                headlineContent = { Text("Add participants") },
                                supportingContent = { Text("Invite direct contacts into group") },
                                leadingContent = {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                modifier = Modifier.clickable(enabled = !actions.busy) { showAddMemberSheet = true }
                            )
                        }
                    }
                }
            }

            // Participants Header
            item {
                Text(
                    text = "${activeMembers.size} participants",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Participant List
            items(activeMembers, key = { it.memberIdentityId }) { member ->
                val isSelf = member.memberIdentityId == localIdentityId
                val contact = contactsMap[member.memberIdentityId]
                val displayName = when {
                    isSelf -> "${contact?.displayName ?: "You"} (You)"
                    else -> contact?.displayName ?: member.memberIdentityId.take(10)
                }
                val role = GroupMemberRole.fromString(member.role)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSelf && (isOwner || (canManage && role == GroupMemberRole.MEMBER))) {
                            selectedMemberForAction = member
                        },
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
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isSelf) "Active on this device" else "Group member",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Role Badge
                        when (role) {
                            GroupMemberRole.OWNER -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "Owner",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            GroupMemberRole.ADMIN -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "Admin",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            GroupMemberRole.MEMBER -> {}
                        }
                    }
                }
            }

            // Danger Actions (Leave Group, Clear Chat)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Leave group", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text("You will no longer receive new messages") },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !actions.busy) { showLeaveConfirmDialog = true }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        ListItem(
                            headlineContent = { Text("Clear chat history", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text("Delete local messages from this device") },
                            leadingContent = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !actions.busy) { showDeleteConfirmDialog = true }
                        )
                    }
                }
            }
        }
    }

    // ─── Edit Title Dialog ─────────────────────────────────────────────────────
    if (showEditTitleDialog) {
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            title = { Text("Edit group name") },
            text = { Column {
                actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    enabled = !actions.busy,
                    value = editTitleText,
                    onValueChange = { if (it.length <= 64) editTitleText = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

 actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
 } },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newTitle = editTitleText.trim()
                        if (newTitle.isNotBlank()) {
                            actions.run("Could not update this group") {
                                check(groupService.updateGroupTitle(groupId, newTitle)) { "Unable to update the group name. Try again after pending changes finish." }
                                showEditTitleDialog = false
                            }
                        }
                    },
                    enabled = editTitleText.isNotBlank() && !actions.busy
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ─── Member Action Sheet (Promote/Demote/Remove) ───────────────────────────
    if (selectedMemberForAction != null) {
        val target = selectedMemberForAction!!
        val targetContact = contactsMap[target.memberIdentityId]
        val targetName = targetContact?.displayName ?: target.memberIdentityId.take(10)
        val targetRole = GroupMemberRole.fromString(target.role)

        ModalBottomSheet(
            onDismissRequest = { selectedMemberForAction = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    text = targetName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Role change options (Only Owner can promote/demote)
                if (isOwner) {
                    if (targetRole == GroupMemberRole.MEMBER) {
                        ListItem(
                            headlineContent = { Text("Make group admin") },
                            leadingContent = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !actions.busy) {
                                val id = target.memberIdentityId
                                actions.run("Could not update this group") {
                                    check(groupService.changeRole(groupId, id, GroupMemberRole.ADMIN)) { "Unable to change this member’s role" }
                                    selectedMemberForAction = null
                                }
                            }
                        )
                    } else if (targetRole == GroupMemberRole.ADMIN) {
                        ListItem(
                            headlineContent = { Text("Dismiss as admin") },
                            leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.clickable(enabled = !actions.busy) {
                                val id = target.memberIdentityId
                                actions.run("Could not update this group") {
                                    check(groupService.changeRole(groupId, id, GroupMemberRole.MEMBER)) { "Unable to change this member’s role" }
                                    selectedMemberForAction = null
                                }
                            }
                        )
                    }
                }

                // Remove from group
                val canRemove = isOwner || (selfRole == GroupMemberRole.ADMIN && targetRole == GroupMemberRole.MEMBER)
                if (canRemove) {
                    ListItem(
                        headlineContent = { Text("Remove from group", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(enabled = !actions.busy) {
                            val id = target.memberIdentityId
                            actions.run("Could not update this group") {
                                check(groupService.removeMember(groupId, id)) { "Unable to remove this member" }
                                selectedMemberForAction = null
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ─── Add Member Sheet ──────────────────────────────────────────────────────
    if (showAddMemberSheet) {
        val nonMemberContacts = remember(allContacts, activeMembers) {
            val memberIds = activeMembers.map { it.memberIdentityId }.toSet()
            allContacts.filter { it.remoteIdentityId !in memberIds }
        }

        ModalBottomSheet(
            onDismissRequest = { showAddMemberSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    text = "Add participants",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (nonMemberContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "All direct contacts are already in this group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(nonMemberContacts, key = { it.contactId }) { contact ->
                            ListItem(
                                headlineContent = { Text(contact.displayName) },
                                supportingContent = { Text("Direct contact") },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.displayName.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                },
                                modifier = Modifier.clickable(enabled = !actions.busy) {
                                    actions.run("Could not update this group") {
                                        check(groupService.addMember(groupId, contact)) { "Unable to add this contact" }
                                        showAddMemberSheet = false
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ─── Leave Group Confirmation Dialog ───────────────────────────────────────
    if (showLeaveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmDialog = false },
            title = { Text("Leave \"${group?.title ?: "Group"}\"?") },
            text = { Column {
                Text(
                    if (isOwner)
                        "You are the owner of this group. If you leave, you will lose owner control over the group and will no longer receive new messages."
                    else
                        "You will no longer receive new messages or participate in group discussions."
                )

 actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
 } },
            confirmButton = {
                TextButton(
                    enabled = !actions.busy,
                    onClick = {
                        actions.run("Could not update this group") {
                            val success = groupService.leaveGroup(groupId)
                            check(success) { "Unable to leave this group. Try again after pending changes finish." }
                            showLeaveConfirmDialog = false
                            onGroupLeft()
                        }
                    }
                ) {
                    Text("Leave group", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ─── Delete Local Chat Confirmation Dialog ─────────────────────────────────
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Clear chat history?") },
            text = { Column {
                Text("Messages will be permanently deleted from this device only. You will remain a member of the group.")

 actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
 } },
            confirmButton = {
                TextButton(
                    enabled = !actions.busy,
                    onClick = {
                        actions.run("Could not update this group") {
                            chatService.deleteChatLocally(groupId)
                            showDeleteConfirmDialog = false
                        }
                    }
                ) {
                    Text("Clear chat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ─── Mute Dialog ───────────────────────────────────────────────────────────
    if (showMuteDialog) {
        val now = System.currentTimeMillis()
        val options = listOf(
            "8 hours" to (now + 8 * 3600 * 1000L),
            "1 week" to (now + 7 * 24 * 3600 * 1000L),
            "Always" to Long.MAX_VALUE
        )

        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text("Mute notifications") },
            text = { Column {
                Column {
                    if (isMuted) {
                        TextButton(
                            onClick = {
                                actions.run("Could not update this group") {
                                    chatService.setChatMuted(groupId, null)
                                    showMuteDialog = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unmute")
                        }
                    }
                    options.forEach { (label, until) ->
                        TextButton(
                            onClick = {
                                actions.run("Could not update this group") {
                                    chatService.setChatMuted(groupId, until)
                                    showMuteDialog = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }

 actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
 } },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
