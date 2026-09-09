package com.torxone.app.call

import android.content.Context
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Thread-safe provider for cached, pre-warmed WebRTC PeerConnectionFactory and audio modules.
 * Avoids the 500ms–1500ms latency penalty of re-initializing native WebRTC threads
 * and audio drivers on every call.
 */
object WebRtcFactoryProvider {
    private const val TAG = "WebRtcFactoryProvider"

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var isLibraryInitialized = false

    @Synchronized
    fun getOrCreateFactory(context: Context): PeerConnectionFactory {
        val existing = factory
        if (existing != null) {
            return existing
        }

        val appContext = context.applicationContext
        Log.d(TAG, "Initializing WebRTC native subsystem & PeerConnectionFactory")

        if (!isLibraryInitialized) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            isLibraryInitialized = true
        }

        if (eglBase == null) {
            eglBase = EglBase.create()
        }

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val newFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        factory = newFactory
        Log.d(TAG, "PeerConnectionFactory initialized and cached")
        return newFactory
    }

    @Synchronized
    fun getEglBase(): EglBase? {
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        return eglBase
    }

    @Synchronized
    fun dispose() {
        Log.d(TAG, "Disposing WebRtcFactoryProvider resources")
        try {
            factory?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing factory: ${e.message}")
        }
        factory = null

        try {
            audioDeviceModule?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ADM: ${e.message}")
        }
        audioDeviceModule = null

        try {
            eglBase?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing EglBase: ${e.message}")
        }
        eglBase = null
    }
}
