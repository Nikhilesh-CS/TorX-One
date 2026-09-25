package com.torxone.app.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.torxone.app.notifications.NotificationPolicy
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()

    val group by database.groupDao().observeById(groupId).collectAsState(initial = null)
    val conversation by database.conversationDao().observeById(groupId).collectAsState(initial = null)
    val allMembers by database.groupMemberDao().observeMembers(groupId).collectAsState(initial = emptyList())
    val allContacts by database.contactDao().observeAll().collectAsState(initial = emptyList())

    val contactsMap = remember(allContacts) { allContacts.associateBy { it.contactId } }
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

    var showEditTitleDialog by remember { mutableStateOf(false) }
    var editTitleText by remember { mutableStateOf("") }
    var showAddMemberSheet by remember { mutableStateOf(false) }
    var selectedMemberForAction by remember { mutableStateOf<GroupMemberEntity?>(null) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showMuteDialog by remember { mutableStateOf(false) }

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
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "Group",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(54.dp)
                        )
                    }

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
                            modifier = Modifier.clickable { showMuteDialog = true }
                        )

                        if (canManage) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ListItem(
                                headlineContent = { Text("Add participants") },
                                supportingContent = { Text("Invite direct contacts into group") },
                                leadingContent = {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                modifier = Modifier.clickable { showAddMemberSheet = true }
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
                            modifier = Modifier.clickable { showLeaveConfirmDialog = true }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                        ListItem(
                            headlineContent = { Text("Clear chat history", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text("Delete local messages from this device") },
                            leadingContent = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { showDeleteConfirmDialog = true }
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
            text = {
                OutlinedTextField(
                    value = editTitleText,
                    onValueChange = { if (it.length <= 64) editTitleText = it },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newTitle = editTitleText.trim()
                        if (newTitle.isNotBlank()) {
                            showEditTitleDialog = false
                            coroutineScope.launch {
                                groupService.updateGroupTitle(groupId, newTitle)
                            }
                        }
                    },
                    enabled = editTitleText.isNotBlank()
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
                            modifier = Modifier.clickable {
                                val id = target.memberIdentityId
                                selectedMemberForAction = null
                                coroutineScope.launch {
                                    groupService.changeRole(groupId, id, GroupMemberRole.ADMIN)
                                }
                            }
                        )
                    } else if (targetRole == GroupMemberRole.ADMIN) {
                        ListItem(
                            headlineContent = { Text("Dismiss as admin") },
                            leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                            modifier = Modifier.clickable {
                                val id = target.memberIdentityId
                                selectedMemberForAction = null
                                coroutineScope.launch {
                                    groupService.changeRole(groupId, id, GroupMemberRole.MEMBER)
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
                        modifier = Modifier.clickable {
                            val id = target.memberIdentityId
                            selectedMemberForAction = null
                            coroutineScope.launch {
                                groupService.removeMember(groupId, id)
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
            allContacts.filter { it.contactId !in memberIds }
        }

        ModalBottomSheet(
            onDismissRequest = { showAddMemberSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
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
                                modifier = Modifier.clickable {
                                    showAddMemberSheet = false
                                    coroutineScope.launch {
                                        groupService.addMember(groupId, contact)
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
            text = {
                Text(
                    if (isOwner)
                        "You are the owner of this group. If you leave, you will lose owner control over the group and will no longer receive new messages."
                    else
                        "You will no longer receive new messages or participate in group discussions."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmDialog = false
                        coroutineScope.launch {
                            val success = groupService.leaveGroup(groupId)
                            if (success) {
                                onGroupLeft()
                            }
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
            text = {
                Text("Messages will be permanently deleted from this device only. You will remain a member of the group.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        coroutineScope.launch {
                            chatService.deleteChatLocally(groupId)
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
            text = {
                Column {
                    if (isMuted) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
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
                                coroutineScope.launch {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
