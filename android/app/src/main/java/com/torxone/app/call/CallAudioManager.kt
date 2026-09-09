package com.torxone.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages call audio lifecycle: audio focus, communication mode, speaker/earpiece routing,
 * headset plug/unplug recovery, and deterministic mute/speaker state persistence.
 */
class CallAudioManager(
    private val context: Context,
    private val onPermanentFocusLoss: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "CallAudioManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val isAudioActive = AtomicBoolean(false)

    var isMuted: Boolean = false
        private set
    var isSpeaker: Boolean = false
        private set

    private var deviceCallback: AudioDeviceCallback? = null
    private var noisyReceiver: BroadcastReceiver? = null

    init {
        setupDeviceChangeListeners()
    }

    fun startCallAudio() {
        if (isAudioActive.getAndSet(true)) {
            Log.d(TAG, "Call audio already active, re-applying routing")
            applyRouting()
            return
        }
        Log.d(TAG, "Starting call audio mode")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()
        applyRouting()
        applyMuteState()
    }

    fun stopCallAudio() {
        if (!isAudioActive.getAndSet(false)) return
        Log.d(TAG, "Stopping call audio mode")
        abandonAudioFocus()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting audio device: ${e.message}")
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun toggleMute(): Boolean {
        setMuted(!isMuted)
        return isMuted
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyMuteState()
        Log.d(TAG, "Call mute state set: isMuted=$isMuted")
    }

    fun toggleSpeaker(): Boolean {
        setSpeaker(!isSpeaker)
        return isSpeaker
    }

    fun setSpeaker(speaker: Boolean) {
        isSpeaker = speaker
        applyRouting()
        Log.d(TAG, "Call speaker state set: isSpeaker=$isSpeaker")
    }

    /** Re-applies mute and speaker configurations after reconnect or device handover. */
    fun restoreAudioState() {
        if (!isAudioActive.get()) return
        Log.d(TAG, "Restoring audio state: isMuted=$isMuted, isSpeaker=$isSpeaker")
        applyRouting()
        applyMuteState()
    }

    private fun applyRouting() {
        if (isSpeaker) {
            setSpeakerInternal()
        } else {
            setEarpieceInternal()
        }
    }

    private fun applyMuteState() {
        try {
            audioManager.isMicrophoneMute = isMuted
        } catch (e: Exception) {
            Log.w(TAG, "Error applying mic mute: ${e.message}")
        }
    }

    private fun setSpeakerInternal(): Boolean {
        val selected = selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        if (!selected) {
            audioManager.isSpeakerphoneOn = true
        }
        return selected || audioManager.isSpeakerphoneOn
    }

    private fun setEarpieceInternal(): Boolean {
        val selected = selectCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        if (!selected) {
            audioManager.isSpeakerphoneOn = false
        }
        return !(selected || audioManager.isSpeakerphoneOn)
    }

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
                    handleAudioFocusChange(focusChange)
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus regained, restoring routing & mute")
                restoreAudioState()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Pause/duck playback as appropriate, but do NOT change user's isMuted value!
                Log.d(TAG, "Transient audio focus loss, preserving isMuted=$isMuted")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Permanent audio focus loss")
                onPermanentFocusLoss?.invoke()
            }
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

    private fun setupDeviceChangeListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val hadHeadset = removedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: false
                    if (hadHeadset && isAudioActive.get()) {
                        Log.i(TAG, "Headset disconnected, falling back to earpiece/speaker")
                        applyRouting()
                    }
                }
            }
            try {
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed registering AudioDeviceCallback: ${e.message}")
            }
        }

        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isAudioActive.get()) {
                    Log.i(TAG, "Audio becoming noisy, restoring routing")
                    applyRouting()
                }
            }
        }
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            context.registerReceiver(noisyReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering noisyReceiver: ${e.message}")
        }
    }

    fun release() {
        stopCallAudio()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && deviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering deviceCallback: ${e.message}")
            }
            deviceCallback = null
        }
        noisyReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering noisyReceiver: ${e.message}")
            }
            noisyReceiver = null
        }
    }
}
