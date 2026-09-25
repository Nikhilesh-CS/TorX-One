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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TorXOneApplication
        val callManager = app.callManager
        val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID) ?: return

        when (intent.action) {
            CallNotificationManager.ACTION_ANSWER -> {
                Log.d(TAG, "Answer action for call=$callId")
                scope.launch {
                    callManager.acceptCall(callId)
                    app.callNotificationManager.cancelIncomingNotification()
                }
            }
            CallNotificationManager.ACTION_DECLINE -> {
                Log.d(TAG, "Decline action for call=$callId")
                scope.launch {
                    callManager.declineCall(callId)
                    app.callNotificationManager.cancelAll()
                }
            }
            CallNotificationManager.ACTION_HANGUP -> {
                Log.d(TAG, "Hangup action for call=$callId")
                scope.launch {
                    callManager.hangUp()
                    app.callNotificationManager.cancelAll()
                }
            }
        }
    }
}
