package com.torxone.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.style.TextOverflow
import com.torxone.app.ui.components.TorXEmptyState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.chat.*
import com.torxone.app.data.entity.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(service: ChatProductivityService, conversationId: String?, onOpen: (MessageEntity) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val resultsFlow = remember(query, conversationId, filter) { service.search(query, conversationId, filter) }
    val results by resultsFlow.collectAsState(initial = emptyList())
    val filtered = results
    val formatter = remember { java.text.DateFormat.getDateTimeInstance() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (conversationId == null) "Search messages" else "Search this chat") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } })
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SearchFilter.entries.toList()) { item -> FilterChip(filter == item, { filter = item }, label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            }
            Text("${filtered.size} results", Modifier.padding(vertical = 8.dp))
            LazyColumn {
                if (filtered.isEmpty()) item { TorXEmptyState(if (query.isBlank()) "Search your messages" else "No matching messages",
                    Icons.Default.Search, message = if (query.isBlank()) "Find text, links and attachments." else "Try a different word or filter.") }
                items(filtered, key = { it.logicalMessageId }) { message -> ListItem(
                    headlineContent = { Text(message.body ?: "Attachment", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(formatter.format(java.util.Date(message.createdAt))) },
                    modifier = Modifier.clickable { onOpen(message) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedMessagesScreen(messages: List<MessageEntity>, onOpen: (MessageEntity) -> Unit, onUnstar: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Starred messages") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) item { TorXEmptyState("No starred messages", Icons.Default.Star, message = "Star a message to find it here.") }
            items(messages, key = { it.logicalMessageId }) { message -> ListItem(
                headlineContent = { Text(message.body ?: "Attachment", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { onUnstar(message.logicalMessageId) }) { Text("Unstar") } },
                modifier = Modifier.clickable { onOpen(message) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMessagesScreen(conversations: List<ConversationEntity>, isSending: Boolean, error: String?,
    onChoose: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Forward to") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text("Creates new encrypted messages without identifying the original sender.", Modifier.padding(16.dp)) }
            error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            if (isSending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (conversations.isEmpty()) item { TorXEmptyState("No chats to forward to", Icons.Default.Chat) }
            items(conversations, key = { it.conversationId }) { row -> ListItem(headlineContent = { Text(row.title ?: "Contact") },
                modifier = Modifier.clickable(enabled = !isSending) { onChoose(row.conversationId) }) }
        }
    }
}
