package com.torxone.app.ui.models

data class Conversation(
    val id: String, // Contact public key or Group ID
    val type: String, // "direct" or "group"
    val title: String,
    val avatarUri: String? = null,
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val lastMessageText: String = "Tap to chat..."
)
