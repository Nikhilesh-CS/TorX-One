package com.torxone.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Log

/**
 * Manages audio routing during calls: speaker/earpiece switching, mute toggle,
 * AudioManager mode, and audio focus.
 */
class AudioRouteManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    var isMuted: Boolean = false
        private set
    var isSpeaker: Boolean = false
        private set

    fun startCallAudio() {
        Log.d(TAG, "Starting call audio mode")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        isSpeaker = false
        requestAudioFocus()
    }

    fun stopCallAudio() {
        Log.d(TAG, "Stopping call audio mode")
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
        isMuted = false
        isSpeaker = false
        abandonAudioFocus()
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        audioManager.isMicrophoneMute = isMuted
        Log.d(TAG, "Mute toggled: $isMuted")
        return isMuted
    }

    fun toggleSpeaker(): Boolean {
        isSpeaker = if (isSpeaker) {
            setEarpieceInternal()
            false
        } else setSpeakerInternal()
        Log.d(TAG, "Speaker toggled: $isSpeaker")
        return isSpeaker
    }

    fun setEarpiece() {
        setEarpieceInternal()
        isSpeaker = false
    }

    fun setSpeaker() {
        isSpeaker = setSpeakerInternal()
    }

    private fun setSpeakerInternal(): Boolean {
        val selected = selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        if (!selected) audioManager.isSpeakerphoneOn = true
        return selected || audioManager.isSpeakerphoneOn
    }

    private fun setEarpieceInternal(): Boolean {
        val selected = selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        if (!selected) audioManager.isSpeakerphoneOn = false
        return !(selected || audioManager.isSpeakerphoneOn)
    }

    /** API 31+ route selection used by Android's communication stack and WebRTC. */
    private fun selectCommunicationDevice(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type } ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { }
        }
    }
}
