package com.torxone.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * AudioRouteManager — Handles Android audio routing for calls.
 *
 * Manages:
 * - MODE_IN_COMMUNICATION for voice calls
 * - Audio focus acquisition/release
 * - Earpiece / Speaker / Bluetooth routing
 * - Mute state via AudioTrack (not system volume)
 * - Restores previous audio mode when call ends
 *
 * Invariant: Audio mode must be restored on EVERY call end path.
 * Otherwise: "call ends but phone audio remains in communication mode 💀"
 */
class AudioRouteManager(context: Context) {

    companion object {
        private const val TAG = "AudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerState: Boolean = false
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    enum class AudioRoute {
        EARPIECE,
        SPEAKER,
        BLUETOOTH,
        WIRED_HEADSET
    }

    /**
     * Configure audio for an active call.
     * Must be called when a call connects.
     */
    fun startCallAudio(isSpeaker: Boolean = false) {
        // Save previous state
        previousAudioMode = audioManager.mode
        previousSpeakerState = audioManager.isSpeakerphoneOn

        // Request audio focus
        requestAudioFocus()

        // Set communication mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Default to earpiece for voice calls
        setRoute(if (isSpeaker) AudioRoute.SPEAKER else AudioRoute.EARPIECE)

        Log.i(TAG, "Call audio started. Route=${if (isSpeaker) "SPEAKER" else "EARPIECE"}")
    }

    /**
     * Restore audio state after call ends.
     * MUST be called on every exit path.
     */
    fun stopCallAudio() {
        audioManager.mode = previousAudioMode
        audioManager.isSpeakerphoneOn = previousSpeakerState
        abandonAudioFocus()
        Log.i(TAG, "Call audio stopped. Mode restored to $previousAudioMode")
    }

    /**
     * Set the audio output route.
     */
    fun setRoute(route: AudioRoute) {
        when (route) {
            AudioRoute.EARPIECE -> {
                audioManager.isSpeakerphoneOn = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
            }
            AudioRoute.SPEAKER -> {
                audioManager.isSpeakerphoneOn = true
            }
            AudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            AudioRoute.WIRED_HEADSET -> {
                audioManager.isSpeakerphoneOn = false
            }
        }
        Log.d(TAG, "Audio route set to $route")
    }

    fun getCurrentRoute(): AudioRoute {
        return when {
            audioManager.isBluetoothScoOn -> AudioRoute.BLUETOOTH
            audioManager.isSpeakerphoneOn -> AudioRoute.SPEAKER
            audioManager.isWiredHeadsetOn -> AudioRoute.WIRED_HEADSET
            else -> AudioRoute.EARPIECE
        }
    }

    fun isSpeakerOn(): Boolean = audioManager.isSpeakerphoneOn

    fun isBluetoothAvailable(): Boolean {
        return audioManager.isBluetoothScoAvailableOffCall ||
            audioManager.isBluetoothScoOn
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.w(TAG, "Audio focus lost: $focusChange")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request result: $result")
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        focusRequest = null
        hasAudioFocus = false

        // Clean up Bluetooth if it was on
        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }
}
