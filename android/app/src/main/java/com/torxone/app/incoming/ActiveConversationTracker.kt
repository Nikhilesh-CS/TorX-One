package com.torxone.app.incoming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the currently active conversation in the foreground.
 * Used to suppress notifications and manage unread counters.
 */
class ActiveConversationTracker {
    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    fun setActiveConversation(id: String?) {
        _activeConversationId.value = id
    }

    fun clearActiveConversation() {
        _activeConversationId.value = null
    }

    fun getActiveConversationId(): String? = _activeConversationId.value
}
