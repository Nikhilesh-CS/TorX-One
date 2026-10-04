package com.torxone.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
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

        Log.i(TAG, "Call audio started")
    }

    /**
     * Restore audio state after call ends.
     * MUST be called on every exit path.
     */
    fun stopCallAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = previousAudioMode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            audioManager.isSpeakerphoneOn = previousSpeakerState
        }
        abandonAudioFocus()
        Log.i(TAG, "Call audio stopped. Mode restored")
    }

    /**
     * Set the audio output route.
     */
    fun setRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val requestedTypes = when (route) {
                AudioRoute.EARPIECE -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.SPEAKER -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.BLUETOOTH -> setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER
                )
                AudioRoute.WIRED_HEADSET -> setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET
                )
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in requestedTypes }
            if (device != null && audioManager.setCommunicationDevice(device)) {
                Log.d(TAG, "Audio communication device set")
            } else {
                Log.w(TAG, "Requested audio route is unavailable")
            }
            return
        }

        when (route) {
            AudioRoute.EARPIECE -> {
                audioManager.isSpeakerphoneOn = false
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
        Log.d(TAG, "Audio route set")
    }

    fun getCurrentRoute(): AudioRoute {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (audioManager.communicationDevice?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioRoute.BLUETOOTH
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED_HEADSET
                else -> AudioRoute.EARPIECE
            }
        }
        return when {
            audioManager.isBluetoothScoOn -> AudioRoute.BLUETOOTH
            audioManager.isSpeakerphoneOn -> AudioRoute.SPEAKER
            audioManager.isWiredHeadsetOn -> AudioRoute.WIRED_HEADSET
            else -> AudioRoute.EARPIECE
        }
    }

    fun isSpeakerOn(): Boolean = getCurrentRoute() == AudioRoute.SPEAKER

    fun isBluetoothAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        }
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
                        Log.w(TAG, "Audio focus lost")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request result")
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
