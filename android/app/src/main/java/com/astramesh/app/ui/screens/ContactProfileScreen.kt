package com.astramesh.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.astramesh.app.ui.theme.AstraTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    navController: NavController,
    contactKey: String // Will be used to load profile from ViewModel
) {
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
                        model = null,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(50.dp) // Blur effect
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    
                    AsyncImage(
                        model = null,
                        contentDescription = "Contact Avatar",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.large))

                // Name & Status
                Text(
                    text = "Contact Name",
                    style = AstraTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                Text(
                    text = "Status message here...",
                    style = AstraTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

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
                        modifier = Modifier.fillMaxWidth().clickable { },
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
                    // Horizontal scroll of media could go here
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
                        text = "This is a placeholder bio for the contact.",
                        style = AstraTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.small))

                // Identity Section
                ListItem(
                    headlineContent = { Text("Identity Fingerprint") },
                    supportingContent = { Text("AB12...9F84") },
                    leadingContent = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) }
                )
                ListItem(
                    headlineContent = { Text("Onion Address") },
                    supportingContent = { Text("astramesh...onion") },
                    leadingContent = { Icon(Icons.Rounded.Router, contentDescription = null) }
                )

                Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.small))

                // Destructive Actions
                ListItem(
                    headlineContent = { Text("Mute Notifications") },
                    leadingContent = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                    modifier = Modifier.clickable { }
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
    }
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

// Alpha modifier extension
fun Modifier.alpha(alpha: Float): Modifier = this.then(androidx.compose.ui.draw.alpha(alpha))
