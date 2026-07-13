package com.torxone.app.service

object ActiveConversationTracker {
    @Volatile
    private var activeContactKey: String? = null

    fun setActive(contactKey: String) {
        activeContactKey = contactKey
    }

    fun clear(contactKey: String) {
        if (activeContactKey == contactKey) {
            activeContactKey = null
        }
    }

    fun isActive(contactKey: String): Boolean {
        return activeContactKey == contactKey
    }
}
