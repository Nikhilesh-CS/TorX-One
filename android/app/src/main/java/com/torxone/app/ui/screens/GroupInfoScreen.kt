package com.torxone.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.content.Intent
import com.torxone.app.data.AppDatabase
import com.torxone.app.ui.components.AstraAvatar
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
    var showScanner by remember { mutableStateOf(false) }
    val manager = com.torxone.app.service.TorXOneService.getInstance()?.groupManager

    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val cur = group
            if (cur != null) {
                scope.launch {
                    manager?.updateGroupMetadata(groupId, cur.name, uri.toString(), cur.description)
                    Toast.makeText(context, "Group avatar updated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Group info") }, navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Back") }
        })
    }) { padding ->
        val current = group ?: return@Scaffold
        val isCreator = current.myRole == "owner" || current.myRole == "admin"
        val isOwner = current.myRole == "owner"
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.clickable(enabled = isCreator) { avatarPicker.launch("image/*") }) {
                        AstraAvatar(model = current.avatarUri, name = current.name, size = 64.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${members.count { it.role != "invited" }} members", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (isOwner) item {
                ListItem(
                    headlineContent = { Text("Approve new members") },
                    supportingContent = { Text("Require owner approval before a join becomes active") },
                    trailingContent = { Switch(checked = current.approvalRequired, onCheckedChange = { enabled -> scope.launch { manager?.setJoinApprovalRequired(groupId, enabled) } }) }
                )
            }
            item { OutlinedButton(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth()) { Text("Scan group invite QR") } }
            if (isOwner) item {
                Button(onClick = { scope.launch { val token = manager?.createInviteToken(groupId, System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L); inviteToken = token; showQr = token != null } }, modifier = Modifier.fillMaxWidth()) { Text("Create invite QR") }
            }
            if (isCreator) item {
                OutlinedTextField(name, { name = it }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    scope.launch {
                        if (manager?.updateGroupMetadata(groupId, name, current.avatarUri, description) == true) Toast.makeText(context, "Group updated", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(context, "Only the creator can edit this group", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
            item {
                val muted = current.muteUntil == -1L || current.muteUntil > System.currentTimeMillis()
                ListItem(
                    headlineContent = { Text("Mute notifications") },
                    supportingContent = { Text(if (muted) "No notifications from this group on this device" else "Notify for new group messages") },
                    trailingContent = {
                        Switch(
                            checked = muted,
                            onCheckedChange = { enabled -> scope.launch { manager?.setGroupMuted(groupId, enabled) } }
                        )
                    }
                )
            }
            if (isCreator) item {
                ListItem(headlineContent = { Text("Add members") }, supportingContent = { Text("Invited members receive the current key after accepting") },
                    leadingContent = { Icon(Icons.Rounded.PersonAdd, null) }, modifier = Modifier.clickable { showAddMembers = true })
            }
            item { Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(members, key = { it.memberKey }) { member ->
                val contact = contacts.firstOrNull { it.signingPublicKey == member.memberKey }
                ListItem(headlineContent = { Text(contact?.name ?: member.memberKey.take(16)) }, supportingContent = { Text(member.role) },
                    trailingContent = if (isCreator && member.memberKey != current.creatorKey) ({
                        Row {
                            if (isOwner && member.membershipState == "PENDING_APPROVAL") TextButton(onClick = { scope.launch { manager?.approveJoin(groupId, member.memberKey) } }) { Text("Approve") }
                            if (isOwner) TextButton(onClick = { scope.launch { manager?.changeMemberRole(groupId, member.memberKey, if (member.role == "admin") "member" else "admin") } }) { Text(if (member.role == "admin") "Demote" else "Promote") }
                            if (isOwner && member.role != "invited") TextButton(onClick = { scope.launch {
                                if (manager?.transferOwnership(groupId, member.memberKey) == true) Toast.makeText(context, "Ownership transferred", Toast.LENGTH_SHORT).show()
                            } }) { Text("Owner") }
                            TextButton(onClick = { scope.launch { manager?.removeMember(groupId, member.memberKey) } }) { Text("Remove") }
                        }
                    }) else null)
            }
            if (isOwner) {
                item {
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete group")
                    }
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                manager?.leaveGroup(groupId)
                                navController.popBackStack()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave group")
                    }
                }
            }
        }
    }
    if (showAddMembers) AlertDialog(onDismissRequest = { showAddMembers = false }, title = { Text("Add members") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(contacts.filter { contact -> members.none { it.memberKey == contact.signingPublicKey } }, key = { it.signingPublicKey }) { contact ->
                val checked = selected.contains(contact.signingPublicKey)
                Row(Modifier.fillMaxWidth().clickable { selected = if (checked) selected - contact.signingPublicKey else selected + contact.signingPublicKey }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null); Text(contact.name, Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { scope.launch { manager?.addMembers(groupId, selected.toList()); showAddMembers = false } }, enabled = selected.isNotEmpty()) { Text("Invite") } }, dismissButton = { TextButton(onClick = { showAddMembers = false }) { Text("Cancel") } })
    if (showQr && inviteToken != null) {
        val matrix = remember(inviteToken) { QRCodeWriter().encode(inviteToken, BarcodeFormat.QR_CODE, 700, 700) }
        val bitmap = remember(matrix) { Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888).apply { for (x in 0 until 700) for (y in 0 until 700) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) } }
        AlertDialog(onDismissRequest = { showQr = false }, title = { Text("TorX One group invite") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Invite QR", Modifier.size(260.dp)); Text("Scan this code before it expires.") } }, confirmButton = { TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, inviteToken) }, "Share invite")) }) { Text("Share") } }, dismissButton = { TextButton(onClick = { showQr = false }) { Text("Close") } })
    }
    if (showScanner) QrContactScannerDialog(onDismiss = { showScanner = false }, onContactScanned = { token -> showScanner = false; scope.launch { manager?.requestJoinWithInvite(token) } })
}
