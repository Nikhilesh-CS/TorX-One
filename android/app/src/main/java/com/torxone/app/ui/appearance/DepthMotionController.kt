package com.torxone.app.ui.appearance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class DepthMotionState(val offset: State<Offset>, val reduced: State<Boolean>)

fun shouldReduceAppearanceMotion(mode: String, batterySaver: Boolean, animationScale: Float): Boolean =
    mode == "REDUCED" || batterySaver || !animationScale.isFinite() || animationScale <= 0f

@Composable
fun rememberDepthMotion(config: ChatAppearance): DepthMotionState {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offset = remember { mutableStateOf(Offset.Zero) }
    val reduced = remember { mutableStateOf(true) }
    DisposableEffect(context, lifecycle, config.preset, config.motionMode, config.parallaxStrength) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var registered = false
        var lastUpdate = 0L
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                if (!registered || event.timestamp - lastUpdate < 32_000_000L) return
                lastUpdate = event.timestamp
                val target = Offset((event.values[0] / 9.81f).coerceIn(-1f, 1f), (event.values[1] / 9.81f).coerceIn(-1f, 1f))
                offset.value = offset.value * 0.85f + target * 0.15f
            }
        }
        fun update() {
            val animationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            reduced.value = shouldReduceAppearanceMotion(config.motionMode, power.isPowerSaveMode, animationScale)
            val allowed = config.preset == "DEPTH" && !reduced.value && config.parallaxStrength > 0f &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (allowed && !registered) {
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                registered = sensor != null && sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            } else if (!allowed && registered) { sensors.unregisterListener(listener); registered = false }
            if (!allowed) offset.value = Offset.Zero
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) = update() }
        val batteryReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) = update() }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver)
        androidx.core.content.ContextCompat.registerReceiver(context, batteryReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        update()
        onDispose {
            sensors.unregisterListener(listener)
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(settingsObserver)
            context.unregisterReceiver(batteryReceiver)
            offset.value = Offset.Zero
        }
    }
    return DepthMotionState(offset, reduced)
}
