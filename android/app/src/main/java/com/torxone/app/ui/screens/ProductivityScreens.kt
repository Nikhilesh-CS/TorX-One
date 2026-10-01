package com.torxone.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.chat.*
import com.torxone.app.data.entity.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchScreen(service: ChatProductivityService, conversationId: String?, onOpen: (MessageEntity) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    val resultsFlow = remember(query, conversationId, filter) { service.search(query, conversationId, filter) }
    val results by resultsFlow.collectAsState(initial = emptyList())
    val filtered = results
    Scaffold(topBar = { TopAppBar(title = { Text(if (conversationId == null) "Search messages" else "Search this chat") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SearchFilter.entries.toList()) { item -> FilterChip(filter == item, { filter = item }, label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            }
            Text("${filtered.size} results", Modifier.padding(vertical = 8.dp))
            LazyColumn {
                items(filtered, key = { it.logicalMessageId }) { message -> ListItem(
                    headlineContent = { Text(message.body ?: "Attachment") },
                    supportingContent = { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(message.createdAt))) },
                    modifier = Modifier.clickable { onOpen(message) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedMessagesScreen(messages: List<MessageEntity>, onOpen: (MessageEntity) -> Unit, onUnstar: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Starred messages") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) item { Text("No starred messages yet", Modifier.padding(16.dp)) }
            items(messages, key = { it.logicalMessageId }) { message -> ListItem(
                headlineContent = { Text(message.body ?: "Attachment") },
                trailingContent = { TextButton(onClick = { onUnstar(message.logicalMessageId) }) { Text("Unstar") } },
                modifier = Modifier.clickable { onOpen(message) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardMessagesScreen(conversations: List<ConversationEntity>, isSending: Boolean, error: String?,
    onChoose: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Forward to") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text("Creates new encrypted messages without identifying the original sender.", Modifier.padding(16.dp)) }
            error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
            if (isSending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(conversations, key = { it.conversationId }) { row -> ListItem(headlineContent = { Text(row.title ?: "Contact") },
                modifier = Modifier.clickable(enabled = !isSending) { onChoose(row.conversationId) }) }
        }
    }
}
