package com.torxone.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.conversations.ConversationListViewModel
import com.torxone.app.conversations.ConversationUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedConversationsScreen(
    viewModel: ConversationListViewModel,
    onConversationClick: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val archivedList = uiState.archivedConversations

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Archived chats (${archivedList.size})") }
            )
        },
        modifier = modifier
    ) { padding ->
        if (archivedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No archived chats",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(archivedList, key = { it.conversationId }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = {
                            if (item.preview != null) {
                                Text(item.preview, maxLines = 1)
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.toggleArchive(item) }) {
                                Icon(Icons.Default.Unarchive, contentDescription = "Unarchive")
                            }
                        },
                        modifier = Modifier.clickable { onConversationClick(item.conversationId) }
                    )
                }
            }
        }
    }
}
