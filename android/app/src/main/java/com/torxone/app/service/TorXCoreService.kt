package com.torxone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.torxone.app.TorXOneApplication

/**
 * Android Foreground Service ensuring mesh networking and outbox delivery
 * survive activity transitions and device sleep (Section 48).
 */
class TorXCoreService : Service() {
    private val wakeHandler = Handler(Looper.getMainLooper())
    private var networkWakeLock: PowerManager.WakeLock? = null
    private val renewNetworkWakeLock = object : Runnable {
        override fun run() {
            // Tor is a persistent socket service. A foreground notification alone does
            // not prevent CPU suspend. The lease expires if renewal/service execution stops.
            networkWakeLock?.acquire(3 * 60_000L)
            if (networkWakeLock != null) wakeHandler.postDelayed(this, 60_000L)
        }
    }

    companion object {
        private const val CHANNEL_SERVICE = "torx_core_service_channel"
        const val CORE_SERVICE_NOTIFICATION_ID = 8001

        fun start(context: Context) {
            val intent = Intent(context, TorXCoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TorXCoreService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(CORE_SERVICE_NOTIFICATION_ID, buildForegroundNotification())
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        networkWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TorXOne:Networking")
            .apply { setReferenceCounted(false) }
        renewNetworkWakeLock.run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? TorXOneApplication
        if (app != null) {
            app.agent.start()
            app.torBootstrapManager.start()
            if (com.torxone.app.ui.permissions.PermissionHelper.arePermissionsGranted(
                    this,
                    com.torxone.app.ui.permissions.PermissionHelper.getNearbyPermissions()
                )
            ) {
                app.nearbyTransport.start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseNetworkWakeLock()
        super.onDestroy()
        // Networking is application-scoped. Android may recreate this sticky service;
        // tearing transports down here creates an avoidable delivery outage meanwhile.
    }

    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Respect Android's foreground-service time budget and release CPU resources.
        releaseNetworkWakeLock()
        stopSelf()
    }

    private fun releaseNetworkWakeLock() {
        wakeHandler.removeCallbacks(renewNetworkWakeLock)
        networkWakeLock?.let { if (it.isHeld) it.release() }
        networkWakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SERVICE,
                "TorX Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps secure mesh communication active"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(com.torxone.app.R.drawable.ic_notification_torx)
            .setContentTitle("TorX One Active")
            .setContentText("Secure messaging connection active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
