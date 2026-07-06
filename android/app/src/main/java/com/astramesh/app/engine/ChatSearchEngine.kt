package com.astramesh.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ChatSearchEngine
 * Manages search queries, highlighting, jumping between results,
 * and filtering by media type.
 */
class ChatSearchEngine(private val conversationEngine: ConversationEngine) {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<String>>(emptyList())
    val searchResults: StateFlow<List<String>> = _searchResults
    
    private val _currentResultIndex = MutableStateFlow(-1)
    val currentResultIndex: StateFlow<Int> = _currentResultIndex

    fun updateQuery(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            _currentResultIndex.value = -1
            return
        }

        // Parse filters
        val hasImage = query.contains("has:image", ignoreCase = true)
        val hasVideo = query.contains("has:video", ignoreCase = true)
        val hasDoc = query.contains("has:document", ignoreCase = true)
        val hasMedia = query.contains("has:media", ignoreCase = true) || hasImage || hasVideo
        
        val fromMe = query.contains("from:me", ignoreCase = true)
        val fromThem = query.contains("from:them", ignoreCase = true)
        
        val textQuery = query
            .replace("has:image", "", ignoreCase = true)
            .replace("has:video", "", ignoreCase = true)
            .replace("has:document", "", ignoreCase = true)
            .replace("has:media", "", ignoreCase = true)
            .replace("from:me", "", ignoreCase = true)
            .replace("from:them", "", ignoreCase = true)
            .trim()

        val results = conversationEngine.messages.value.filter { msg ->
            var matches = true
            
            if (hasImage && msg.messageType != "IMAGE") matches = false
            if (hasVideo && msg.messageType != "VIDEO") matches = false
            if (hasDoc && msg.messageType != "DOCUMENT") matches = false
            if (hasMedia && !msg.hasAttachments) matches = false
            
            if (fromMe && msg.senderId != "me") matches = false
            if (fromThem && msg.senderId == "me") matches = false
            
            if (textQuery.isNotEmpty() && !msg.text.contains(textQuery, ignoreCase = true)) {
                matches = false
            }
            
            matches
        }.map { it.id }
        
        _searchResults.value = results
        _currentResultIndex.value = if (results.isNotEmpty()) 0 else -1
    }

    fun nextResult() {
        val count = _searchResults.value.size
        if (count == 0) return
        _currentResultIndex.value = (_currentResultIndex.value + 1) % count
    }

    fun previousResult() {
        val count = _searchResults.value.size
        if (count == 0) return
        val current = _currentResultIndex.value
        _currentResultIndex.value = if (current - 1 < 0) count - 1 else current - 1
    }
    
    fun clearSearch() {
        updateQuery("")
    }
}
