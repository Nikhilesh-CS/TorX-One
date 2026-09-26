package com.torxone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.torxone.app.TorXOneApplication

/**
 * Android Foreground Service ensuring mesh networking and outbox delivery
 * survive activity transitions and device sleep (Section 48).
 */
class TorXCoreService : Service() {

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? TorXOneApplication
        if (app != null) {
            app.agent.start()
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
        super.onDestroy()
        val app = applicationContext as? TorXOneApplication
        app?.nearbyTransport?.stop()
        app?.agent?.stop()
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TorX One Active")
            .setContentText("Direct peer mesh running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
