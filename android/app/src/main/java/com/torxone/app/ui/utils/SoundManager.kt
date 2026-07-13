package com.torxone.app.ui.utils

enum class SoundEvent {
    Send,
    Receive,
    Error,
    Tap
}

interface SoundManager {
    fun playSound(event: SoundEvent)
}

class DummySoundManager : SoundManager {
    override fun playSound(event: SoundEvent) {
        // Dummy implementation for tiny interaction sounds.
        // In a real implementation, this would use SoundPool or MediaPlayer.
        println("Playing sound: ${event.name}")
    }
}
