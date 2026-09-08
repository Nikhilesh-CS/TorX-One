package com.torxone.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.torxone.app.data.AppDatabase
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.AvatarCropperScreen
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimarySoft
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(groupId: String, navController: NavController, db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val group by db.groupDao().getGroupFlow(groupId).collectAsStateWithLifecycle(initialValue = null)
    val members by db.groupDao().getGroupMembers(groupId).collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by db.contactDao().getAllContacts().collectAsStateWithLifecycle(initialValue = emptyList())

    var name by remember(group?.name) { mutableStateOf(group?.name.orEmpty()) }
    var description by remember(group?.description) { mutableStateOf(group?.description.orEmpty()) }
    var showAddMembers by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var inviteToken by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showPhotoOptions by remember { mutableStateOf(false) }
    var cropTargetUri by remember { mutableStateOf<Uri?>(null) }

    val manager = com.torxone.app.service.TorXOneService.getInstance()?.groupManager

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            cropTargetUri = uri
        }
    }

    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val cur = group
            if (cur != null) {
                scope.launch {
                    val file = java.io.File(context.cacheDir, "group_${groupId}_avatar.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    val uri = Uri.fromFile(file).toString()
                    manager?.updateGroupMetadata(groupId, cur.name, uri, cur.description)
                    Toast.makeText(context, "Group photo updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Avatar Cropper View
    if (cropTargetUri != null) {
        AvatarCropperScreen(
            imageUri = cropTargetUri!!,
            onCropComplete = { bitmap ->
                val cur = group
                if (cur != null) {
                    scope.launch {
                        val finalUri = if (bitmap != null) {
                            val file = java.io.File(context.cacheDir, "group_${groupId}_avatar.jpg")
                            file.outputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            Uri.fromFile(file).toString()
                        } else {
                            cropTargetUri.toString()
                        }
                        manager?.updateGroupMetadata(groupId, cur.name, finalUri, cur.description)
                        Toast.makeText(context, "Group photo updated", Toast.LENGTH_SHORT).show()
                    }
                }
                cropTargetUri = null
            },
            onCancel = { cropTargetUri = null }
        )
        return
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Group info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PrimaryText) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PrimaryText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        }
    ) { padding ->
        val current = group ?: return@Scaffold
        val isCreator = current.myRole == "owner" || current.myRole == "admin"
        val isOwner = current.myRole == "owner"
        val activeMemberCount = members.count { it.role != "invited" }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. IDENTITY SECTION (Centered at the very top)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        AstraAvatar(
                            model = current.avatarUri,
                            name = current.name,
                            size = 96.dp
                        )
                        if (isCreator) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceCard)
                                    .border(1.5.dp, BorderColor, CircleShape)
                                    .clickable { showPhotoOptions = true }
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Edit group photo",
                                    tint = TorXPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "$activeMemberCount ${if (activeMemberCount == 1) "member" else "members"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText
                    )
                }
            }

            // 2. GROUP SETTINGS (If owner)
            if (isOwner) {
                item {
                    Column {
                        GroupSectionTitle("GROUP SETTINGS")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceCard)
                                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Approve new members", fontWeight = FontWeight.SemiBold, color = PrimaryText, fontSize = 15.sp)
                                    Text("Require owner approval before a join becomes active", color = SecondaryText, fontSize = 13.sp)
                                }
                                Switch(
                                    checked = current.approvalRequired,
                                    onCheckedChange = { enabled -> scope.launch { manager?.setJoinApprovalRequired(groupId, enabled) } },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TorXPrimary)
                                )
                            }
                        }
                    }
                }
            }

            // 3. NOTIFICATIONS
            item {
                Column {
                    GroupSectionTitle("NOTIFICATIONS")
                    val muted = current.muteUntil == -1L || current.muteUntil > System.currentTimeMillis()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mute notifications", fontWeight = FontWeight.SemiBold, color = PrimaryText, fontSize = 15.sp)
                                Text(if (muted) "No notifications from this group on this device" else "Notify for new group messages", color = SecondaryText, fontSize = 13.sp)
                            }
                            Switch(
                                checked = muted,
                                onCheckedChange = { enabled -> scope.launch { manager?.setGroupMuted(groupId, enabled) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TorXPrimary)
                            )
                        }
                    }
                }
            }

            // 4. GROUP INVITATION (Unified scanner shortcut + Create QR)
            item {
                Column {
                    GroupSectionTitle("GROUP INVITATION")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                    ) {
                        // Scan Group QR (Routes to universal scanner)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("scan_qr") }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(TorXPrimarySoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scan group QR", fontWeight = FontWeight.SemiBold, color = PrimaryText, fontSize = 15.sp)
                                Text("Join a group by scanning its invitation code", color = SecondaryText, fontSize = 13.sp)
                            }
                        }

                        if (isOwner) {
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderColor))

                            // Create invite QR
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val token = manager?.createInviteToken(groupId, System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L)
                                            inviteToken = token
                                            showQr = token != null
                                        }
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(TorXPrimarySoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.QrCode, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Create invite QR", fontWeight = FontWeight.SemiBold, color = PrimaryText, fontSize = 15.sp)
                                    Text("Share a code for others to scan and join", color = SecondaryText, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 5. GROUP DETAILS (If creator/admin)
            if (isCreator) {
                item {
                    Column {
                        GroupSectionTitle("GROUP DETAILS")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceCard)
                                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Group name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PrimaryText,
                                    unfocusedTextColor = PrimaryText,
                                    focusedBorderColor = TorXPrimary,
                                    unfocusedBorderColor = BorderColor,
                                    focusedContainerColor = AppBackground,
                                    unfocusedContainerColor = AppBackground
                                )
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Description") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PrimaryText,
                                    unfocusedTextColor = PrimaryText,
                                    focusedBorderColor = TorXPrimary,
                                    unfocusedBorderColor = BorderColor,
                                    focusedContainerColor = AppBackground,
                                    unfocusedContainerColor = AppBackground
                                )
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (manager?.updateGroupMetadata(groupId, name, current.avatarUri, description) == true) {
                                            Toast.makeText(context, "Group updated", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Only the creator can edit this group", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Save", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 6. MEMBERS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupSectionTitle("MEMBERS")
                    if (isCreator) {
                        TextButton(onClick = { showAddMembers = true }) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add members", color = TorXPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            items(members, key = { it.memberKey }) { member ->
                val contact = contacts.firstOrNull { it.signingPublicKey == member.memberKey }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceCard)
                        .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AstraAvatar(name = contact?.name ?: member.memberKey, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact?.name ?: member.memberKey.take(12) + "...",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = member.role.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (member.role == "owner") TorXPrimary else SecondaryText
                            )
                        }

                        if (isCreator && member.memberKey != current.creatorKey) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isOwner && member.membershipState == "PENDING_APPROVAL") {
                                    TextButton(onClick = { scope.launch { manager?.approveJoin(groupId, member.memberKey) } }) {
                                        Text("Approve", color = TorXPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                if (isOwner) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            manager?.changeMemberRole(groupId, member.memberKey, if (member.role == "admin") "member" else "admin")
                                        }
                                    }) {
                                        Text(if (member.role == "admin") "Demote" else "Promote", color = SecondaryText, fontSize = 12.sp)
                                    }
                                }
                                TextButton(onClick = { scope.launch { manager?.removeMember(groupId, member.memberKey) } }) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 7. DESTRUCTIVE ACTIONS
            item {
                Spacer(modifier = Modifier.height(8.dp))
                if (isOwner) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (manager?.deleteGroup(groupId) == true) {
                                    Toast.makeText(context, "Group deleted", Toast.LENGTH_SHORT).show()
                                    navController.popBackStack()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete group", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                manager?.leaveGroup(groupId)
                                navController.popBackStack()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave group", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Photo Options Modal Bottom Sheet
    if (showPhotoOptions) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showPhotoOptions = false },
            sheetState = sheetState,
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    "Change group photo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryText
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Choose from gallery
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showPhotoOptions = false
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = TorXPrimary)
                    Spacer(Modifier.width(14.dp))
                    Text("Choose from gallery", color = PrimaryText, fontSize = 15.sp)
                }

                // Take photo
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showPhotoOptions = false
                            cameraCaptureLauncher.launch(null)
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = TorXPrimary)
                    Spacer(Modifier.width(14.dp))
                    Text("Take photo", color = PrimaryText, fontSize = 15.sp)
                }

                // Remove photo (if avatar is set)
                val cur = group
                if (cur?.avatarUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showPhotoOptions = false
                                scope.launch {
                                    manager?.updateGroupMetadata(groupId, cur.name, null, cur.description)
                                    Toast.makeText(context, "Photo removed", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(14.dp))
                        Text("Remove photo", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Add Members Dialog (TorX Light Styling)
    if (showAddMembers) {
        AlertDialog(
            onDismissRequest = { showAddMembers = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            title = { Text("Add members", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                val candidateContacts = contacts.filter { contact -> members.none { it.memberKey == contact.signingPublicKey } }
                if (candidateContacts.isEmpty()) {
                    Text("All existing contacts are already members.", color = SecondaryText)
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(candidateContacts, key = { it.signingPublicKey }) { contact ->
                            val checked = selected.contains(contact.signingPublicKey)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selected = if (checked) selected - contact.signingPublicKey else selected + contact.signingPublicKey
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked, null)
                                Spacer(Modifier.width(8.dp))
                                AstraAvatar(name = contact.name, size = 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(contact.name, color = PrimaryText, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            manager?.addMembers(groupId, selected.toList())
                            showAddMembers = false
                        }
                    },
                    enabled = selected.isNotEmpty()
                ) {
                    Text("Invite", color = if (selected.isNotEmpty()) TorXPrimary else TextMuted, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMembers = false }) {
                    Text("Cancel", color = SecondaryText)
                }
            }
        )
    }

    // Invite QR Dialog (TorX Light Styling)
    if (showQr && inviteToken != null) {
        val matrix = remember(inviteToken) { QRCodeWriter().encode(inviteToken, BarcodeFormat.QR_CODE, 700, 700) }
        val bitmap = remember(matrix) {
            Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 700) {
                    for (y in 0 until 700) {
                        setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showQr = false },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            title = { Text("TorX One Group Invite", color = PrimaryText, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Invite QR",
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Scan this code before it expires to join.", color = SecondaryText, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, inviteToken)
                            },
                            "Share invite"
                        )
                    )
                }) {
                    Text("Share", color = TorXPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQr = false }) {
                    Text("Close", color = SecondaryText)
                }
            }
        )
    }
}

@Composable
private fun GroupSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}
