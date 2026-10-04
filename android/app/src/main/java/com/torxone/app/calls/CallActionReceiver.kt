package com.torxone.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.torxone.app.TorXOneApplication
import kotlinx.coroutines.*

/**
 * CallActionReceiver — Handles notification action intents for calls.
 *
 * Actions:
 *   ACTION_ANSWER  → CallManager.acceptCall()
 *   ACTION_DECLINE → CallManager.declineCall()
 *   ACTION_HANGUP  → CallManager.hangUp()
 *
 * No business logic here. Everything routes to CallManager.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TorXOneApplication
        val callManager = app.callManager
        val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID) ?: return

        when (intent.action) {
            CallNotificationManager.ACTION_ANSWER -> {
                Log.d(TAG, "Answer action")
                val activeSession = callManager.activeCall.value
                val isVideo = activeSession?.type == CallType.VIDEO
                val audioGranted = com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)
                val cameraGranted = com.torxone.app.ui.permissions.PermissionHelper.isCameraGranted(context)
                val permissionsGranted = if (isVideo) {
                    audioGranted && cameraGranted
                } else {
                    audioGranted
                }

                if (!permissionsGranted) {
                    Log.w(TAG, "Call permissions missing; opening permission flow")
                    val callIntent = Intent(context, com.torxone.app.MainActivity::class.java).apply {
                        action = "ACTION_ANSWER_CALL"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("callId", callId)
                        putExtra("isVideo", isVideo)
                    }
                    context.startActivity(callIntent)
                    return
                }

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        callManager.acceptCall(callId)
                        app.callNotificationManager.cancelIncomingNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to accept call")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            CallNotificationManager.ACTION_DECLINE -> {
                Log.d(TAG, "Decline action")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        callManager.declineCall(callId)
                        app.callNotificationManager.cancelAll()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decline call")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            CallNotificationManager.ACTION_HANGUP -> {
                Log.d(TAG, "Hangup action")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        callManager.hangUp(callId)
                        app.callNotificationManager.cancelAll()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to hang up call")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
