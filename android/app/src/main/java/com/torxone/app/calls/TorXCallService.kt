package com.torxone.app.calls

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.torxone.app.TorXOneApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * TorXCallService — Foreground service for active calls.
 *
 * Separate from TorXCoreService (mesh/networking/outbox).
 * Starts only when a call begins, stops after the call finishes.
 *
 * Owns the foreground notification that keeps the microphone/camera alive.
 * Observes CallManager.activeCall to auto-stop when the call ends.
 */
class TorXCallService : Service() {

    companion object {
        private const val TAG = "TorXCallService"
        const val ACTION_START = "com.torxone.app.calls.START_CALL_SERVICE"
        const val ACTION_STOP = "com.torxone.app.calls.STOP_CALL_SERVICE"

        fun start(context: android.content.Context) {
            val intent = Intent(context, TorXCallService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, TorXCallService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val app = application as TorXOneApplication
        val callNotifManager = app.callNotificationManager
        val callManager = app.callManager

        // Start as foreground immediately with a placeholder notification
        val activeSession = callManager.activeCall.value
        val notification = callNotifManager.buildActiveCallNotification(
            callId = activeSession?.callId ?: "",
            peerName = "Connecting...",
            callType = activeSession?.type ?: CallType.VOICE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CallNotificationManager.ACTIVE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(CallNotificationManager.ACTIVE_NOTIFICATION_ID, notification)
        }

        // Observe active call — auto-stop when call ends
        scope.launch {
            callManager.activeCall.collectLatest { session ->
                if (session == null || session.state == CallState.ENDED ||
                    session.state == CallState.FAILED || session.state == CallState.DECLINED ||
                    session.state == CallState.MISSED || session.state == CallState.BUSY
                ) {
                    delay(2000) // Brief delay so UI can show "Call ended"
                    stopSelf()
                }
            }
        }

        Log.i(TAG, "TorXCallService started as foreground")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()

        val app = application as TorXOneApplication
        app.callNotificationManager.cancelAll()
        app.audioRouteManager.stopCallAudio()

        Log.i(TAG, "TorXCallService destroyed")
    }
}
