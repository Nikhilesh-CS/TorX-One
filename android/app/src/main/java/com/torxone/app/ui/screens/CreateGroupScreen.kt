package com.torxone.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.torxone.app.data.AppDatabase
import com.torxone.app.ui.components.AstraAvatar
import com.torxone.app.ui.components.AstraPrimaryButton
import com.torxone.app.ui.theme.AstraTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    navController: NavController,
    db: AppDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var selectedContacts by remember { mutableStateOf(setOf<String>()) }
    var isCreating by remember { mutableStateOf(false) }

    val contacts by db.contactDao().getAllContacts().collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Group", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Box(modifier = Modifier.padding(16.dp)) {
                AstraPrimaryButton(
                    text = if (isCreating) "Creating..." else "Create Group",
                    onClick = {
                        if (groupName.isBlank()) {
                            Toast.makeText(context, "Please enter a group name", Toast.LENGTH_SHORT).show()
                            return@AstraPrimaryButton
                        }
                        if (selectedContacts.isEmpty()) {
                            Toast.makeText(context, "Please select at least one contact", Toast.LENGTH_SHORT).show()
                            return@AstraPrimaryButton
                        }
                        
                        isCreating = true
                        scope.launch {
                            try {
                                val groupManager = com.torxone.app.service.TorXOneService.getInstance()?.groupManager
                                if (groupManager != null) {
                                    val groupId = groupManager.createGroupAndInvite(
                                        name = groupName.trim(),
                                        avatarUri = null,
                                        memberKeys = selectedContacts.toList()
                                    )
                                    navController.popBackStack()
                                    navController.navigate("chat/group/$groupId")
                                } else {
                                    Toast.makeText(context, "Service not ready", Toast.LENGTH_SHORT).show()
                                    isCreating = false
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(context, "Failed to create group", Toast.LENGTH_SHORT).show()
                                isCreating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )
            
            Text(
                "Select Members",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.signingPublicKey }) { contact ->
                    val isSelected = selectedContacts.contains(contact.signingPublicKey)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedContacts = if (isSelected) {
                                    selectedContacts - contact.signingPublicKey
                                } else {
                                    selectedContacts + contact.signingPublicKey
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AstraAvatar(name = contact.name, model = null, size = 48.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
