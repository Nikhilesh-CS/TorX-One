package com.astramesh.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.astramesh.app.ui.theme.AstraTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var cropTargetUri by remember { mutableStateOf<Uri?>(null) }
    
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? -> 
            if (uri != null) {
                cropTargetUri = uri
            }
        }
    )

    if (cropTargetUri != null) {
        com.astramesh.app.ui.components.AvatarCropperScreen(
            imageUri = cropTargetUri!!,
            onCropComplete = { bitmap ->
                // Note: Save bitmap to a temp file if not null and update with that URI.
                // For now, if bitmap is null, we fallback to the original URI (or we could cancel)
                viewModel.updateAvatar(cropTargetUri)
                cropTargetUri = null
            },
            onCancel = { cropTargetUri = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile", style = AstraTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveProfile() },
                        enabled = !uiState.isSaving
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Check, contentDescription = "Save")
                        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AstraTheme.spacing.standard),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // Error Message
            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = AstraTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = AstraTheme.spacing.medium)
                )
            }

            // Avatar Section
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clickable {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
            ) {
                // Determine what to show: selected URI, local path, or fallback placeholder
                val model = uiState.avatarUri ?: uiState.avatarLocalPath

                AsyncImage(
                    model = model,
                    contentDescription = "Profile Photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = "Edit Photo",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))

            // Text Fields
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Display Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
            
            OutlinedTextField(
                value = uiState.bio,
                onValueChange = { if (it.length <= 160) viewModel.updateBio(it) },
                label = { Text("Bio (max 160 chars)") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))

            OutlinedTextField(
                value = uiState.statusMessage,
                onValueChange = { viewModel.updateStatusMessage(it) },
                label = { Text("Status Message") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))
            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))

            // Extra Profile Options
            ListItem(
                headlineContent = { Text("Identity QR Code") },
                supportingContent = { Text("Scan to add contact") },
                leadingContent = { Icon(Icons.Rounded.QrCode, contentDescription = null) },
                modifier = Modifier.clickable { }
            )
        }
    }
}
