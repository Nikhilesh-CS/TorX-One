package com.torxone.app.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * SmartScrollEngine
 * Handles advanced pagination, restoring scroll position across rotation,
 * and intelligent auto-scroll behavior.
 */
class SmartScrollEngine(
    val listState: LazyListState,
    private val scope: CoroutineScope
) {
    private val _showScrollToBottom = MutableStateFlow(false)
    val showScrollToBottom: StateFlow<Boolean> = _showScrollToBottom

    fun checkScrollPosition() {
        // If we are scrolled up by more than 2 items, show the FAB
        _showScrollToBottom.value = listState.firstVisibleItemIndex > 2
    }

    fun scrollToBottom() {
        scope.launch {
            listState.animateScrollToItem(0)
            _showScrollToBottom.value = false
        }
    }

    fun onNewMessageArrived(isFromMe: Boolean) {
        if (isFromMe || listState.firstVisibleItemIndex <= 1) {
            scrollToBottom()
        } else {
            _showScrollToBottom.value = true
        }
    }
}
