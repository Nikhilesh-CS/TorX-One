package com.torxone.app.notifications

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppVisibilityState {
    FOREGROUND,
    BACKGROUND
}

/**
 * Tracks whether the TorX One application process is in the foreground or background.
 * Implements Application.ActivityLifecycleCallbacks to automatically maintain visibility state.
 * Used by TorXNotificationManager to prevent redundant notifications when chat is actively open.
 */
class AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val _state = MutableStateFlow(AppVisibilityState.BACKGROUND)
    val state: StateFlow<AppVisibilityState> = _state.asStateFlow()

    private var startedActivityCount = 0

    fun setVisibility(visibility: AppVisibilityState) {
        _state.value = visibility
    }

    fun isForeground(): Boolean = _state.value == AppVisibilityState.FOREGROUND

    fun isBackground(): Boolean = _state.value == AppVisibilityState.BACKGROUND

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount++
        if (startedActivityCount > 0) {
            setVisibility(AppVisibilityState.FOREGROUND)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0) {
            setVisibility(AppVisibilityState.BACKGROUND)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
