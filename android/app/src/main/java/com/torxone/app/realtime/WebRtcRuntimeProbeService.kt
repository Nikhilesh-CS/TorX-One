package com.torxone.app.realtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.torxone.app.R
import org.webrtc.PeerConnectionFactory

class WebRtcRuntimeProbeService : Service() {
    companion object {
        private const val TAG = "WebRtcProbe"
        private const val CHANNEL_ID = "astra_webrtc_probe"
        private const val NOTIFICATION_ID = 81

        fun intent(context: Context): Intent {
            return Intent(context, WebRtcRuntimeProbeService::class.java)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TorX One")
                .setContentText("Checking secure call runtime")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WebRtcProbeStore.markRunning(this)
        Thread {
            runCatching {
                val options = PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                WebRtcProbeStore.markCompatible(this)
                Log.i(TAG, "WebRTC runtime probe passed for ${WebRtcRuntimeStatus.artifact}")
            }.onFailure { e ->
                WebRtcProbeStore.markFailed(this, e.message ?: e.javaClass.simpleName)
                Log.e(TAG, "WebRTC runtime probe failed", e)
            }
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WebRTC Runtime Probe",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }
}
