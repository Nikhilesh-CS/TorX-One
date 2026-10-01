package com.torxone.app.chat

import com.torxone.app.data.entity.ConversationAppearanceEntity

object AppearanceOptions {
    val themes = listOf("SYSTEM", "OCEAN", "FOREST")
    val wallpapers = listOf("NONE", "WARM", "COOL")
    val bubbles = listOf("ROUNDED", "SQUARE")
    fun validate(value: ConversationAppearanceEntity) {
        require(value.theme in themes && (value.wallpaper ?: "NONE") in wallpapers && value.bubbleStyle in bubbles) {
            "Unknown chat appearance"
        }
    }
}
