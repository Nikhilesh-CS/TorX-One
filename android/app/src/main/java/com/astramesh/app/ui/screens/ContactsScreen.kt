package com.astramesh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.ContactEntity
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.TorManager
import com.astramesh.app.ui.components.AstraAvatar
import com.astramesh.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    navController: NavController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager
) {
    val contacts by db.contactDao().getAllContacts().collectAsState(initial = emptyList())
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsState()
    val isTorReady by torManager.isTorReady.collectAsState()

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("Contacts", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(64.dp), tint = DimGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No contacts yet.", fontSize = 18.sp, color = SoftWhite, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Discover nearby users or share your onion address.",
                        fontSize = 14.sp,
                        color = MutedGray,
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(contacts) { contact ->
                    val isNearby = connectedEndpoints.contains(contact.endpointId)
                    val isTor = contact.onionAddress.isNotBlank() && isTorReady
                    
                    ContactItemRow(
                        contact = contact,
                        isNearby = isNearby,
                        isTor = isTor,
                        onClick = {
                            navController.navigate("chat/${contact.signingPublicKey}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactItemRow(
    contact: ContactEntity,
    isNearby: Boolean,
    isTor: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AstraAvatar(name = contact.name, size = 56.dp, isOnline = isNearby || isTor)
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = SoftWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (contact.onionAddress.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "🧅 ${contact.onionAddress.take(16)}...",
                    fontSize = 12.sp,
                    color = MutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (contact.endpointId.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📍 ${contact.endpointId}",
                    fontSize = 12.sp,
                    color = MutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isNearby) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentCyan))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nearby (Wi-Fi Direct / BT)", fontSize = 11.sp, color = AccentCyan)
                } else if (isTor) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(NeonGreen))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Online via Tor", fontSize = 11.sp, color = NeonGreen)
                } else {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DimGray))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Offline", fontSize = 11.sp, color = MutedGray)
                }
            }
        }
    }
}
