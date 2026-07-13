package com.torxone.app.realtime

import android.content.Context

object WebRtcProbeStore {
    private const val PREFS = "webrtc_runtime_probe"
    private const val KEY_ARTIFACT = "artifact"
    private const val KEY_STATUS = "status"
    private const val KEY_ERROR = "error"
    private const val KEY_TESTED_AT = "tested_at"

    const val STATUS_UNKNOWN = "unknown"
    const val STATUS_COMPATIBLE = "compatible"
    const val STATUS_FAILED = "failed"
    const val STATUS_RUNNING = "running"

    fun markRunning(context: Context) {
        write(context, STATUS_RUNNING, null)
    }

    fun markCompatible(context: Context) {
        write(context, STATUS_COMPATIBLE, null)
    }

    fun markFailed(context: Context, error: String) {
        write(context, STATUS_FAILED, error)
    }

    fun status(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ARTIFACT, "") != WebRtcRuntimeStatus.artifact) {
            return STATUS_UNKNOWN
        }
        return prefs.getString(KEY_STATUS, STATUS_UNKNOWN) ?: STATUS_UNKNOWN
    }

    fun error(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERROR, null)
    }

    fun isCompatible(context: Context): Boolean {
        return status(context) == STATUS_COMPATIBLE
    }

    private fun write(context: Context, status: String, error: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARTIFACT, WebRtcRuntimeStatus.artifact)
            .putString(KEY_STATUS, status)
            .putString(KEY_ERROR, error)
            .putLong(KEY_TESTED_AT, System.currentTimeMillis())
            .apply()
    }
}
