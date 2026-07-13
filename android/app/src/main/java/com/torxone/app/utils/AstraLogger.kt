package com.torxone.app.utils

import android.util.Log

object AstraLogger {
    private const val DEFAULT_TAG = "TorX One"

    enum class Level {
        DEBUG, INFO, WARNING, ERROR, SECURITY, NETWORKING, UI
    }

    fun d(message: String, tag: String = DEFAULT_TAG) = log(Level.DEBUG, tag, message)
    fun i(message: String, tag: String = DEFAULT_TAG) = log(Level.INFO, tag, message)
    fun w(message: String, tag: String = DEFAULT_TAG) = log(Level.WARNING, tag, message)
    fun e(message: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)
    fun sec(message: String, tag: String = DEFAULT_TAG) = log(Level.SECURITY, tag, message)
    fun net(message: String, tag: String = DEFAULT_TAG) = log(Level.NETWORKING, tag, message)
    fun ui(message: String, tag: String = DEFAULT_TAG) = log(Level.UI, tag, message)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (!true && (level == Level.DEBUG || level == Level.UI)) {
            return
        }

        val prefix = "[${level.name}]"
        val fullMessage = "$prefix $message"

        when (level) {
            Level.DEBUG, Level.UI -> Log.d(tag, fullMessage)
            Level.INFO, Level.NETWORKING -> Log.i(tag, fullMessage)
            Level.WARNING -> Log.w(tag, fullMessage)
            Level.ERROR, Level.SECURITY -> {
                if (throwable != null) {
                    Log.e(tag, fullMessage, throwable)
                } else {
                    Log.e(tag, fullMessage)
                }
            }
        }
    }
}
