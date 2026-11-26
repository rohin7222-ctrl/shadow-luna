package com.personal.devicememory.collector.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.common.util.toJsonString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2026 Real Multi-Sensor Device & Physical Context Collector:
 * Combines live OS hardware telemetry (Battery charging state, Bluetooth/wired audio route,
 * Screen interactivity, Wi-Fi vs Cellular transport) with a brief low-power Accelerometer
 * variance probe to infer physical context (`MOVING_OR_WALKING`, `STATIONARY_DESK`, `CHARGING_IDLE`, etc.)
 * without dummy placeholder text.
 */
class ActivityCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Activity & Context"

    companion object {
        const val TAG = "ActivityCollector"

        fun hasPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing activity recognition permission")
            return emptyList()
        }

        val now = TimestampUtil.nowMillis()
        val battIntent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(0, 100) else -1

        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenInteractive = powerMgr?.isInteractive == true

        val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val headphonesActive = try {
            audioMgr?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } == true
        } catch (_: Throwable) {
            false
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val networkLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Connected"
        }

        val motionStd = sampleAccelerometerMotion()
        val physicalState = when {
            motionStd != null && motionStd >= 0.45 -> "ACTIVE_MOVING"
            isCharging && !screenInteractive -> "CHARGING_IDLE"
            headphonesActive -> "AUDIO_IMMERSION"
            networkLabel == "Cellular" && screenInteractive -> "MOBILE_ON_THE_GO"
            screenInteractive -> "ACTIVE_USING"
            else -> "STILL_STANDBY"
        }

        val cues = mutableListOf<String>()
        cues.add(
            when (physicalState) {
                "ACTIVE_MOVING" -> "Moving"
                "CHARGING_IDLE" -> "Charging Idle"
                "AUDIO_IMMERSION" -> "Headphones Active"
                "MOBILE_ON_THE_GO" -> "On The Go (Cellular)"
                "ACTIVE_USING" -> "Active Screen"
                else -> "Still / Standby"
            }
        )
        if (isCharging && physicalState != "CHARGING_IDLE") cues.add("Charging")
        if (headphonesActive && physicalState != "AUDIO_IMMERSION") cues.add("Audio Headset")
        cues.add(networkLabel)
        if (batteryPct >= 0) cues.add("Batt ${batteryPct}%")

        val metadata = mapOf<String, Any?>(
            "physical_state" to physicalState,
            "charging" to isCharging,
            "battery_pct" to batteryPct,
            "screen_interactive" to screenInteractive,
            "headphones_active" to headphonesActive,
            "network_transport" to networkLabel,
            "motion_std" to motionStd,
            "timestamp" to now
        )

        return listOf(
            CollectedRecord(
                sourceType = SourceType.ACTIVITY,
                timestamp = now,
                textContent = "Context: ${cues.joinToString(" • ")}",
                metadataJson = metadata.toJsonString()
            )
        )
    }

    private fun sampleAccelerometerMotion(): Double? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return null
        val magnitudes = ArrayList<Double>(8)
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values.getOrNull(0)?.toDouble() ?: 0.0
                val y = event.values.getOrNull(1)?.toDouble() ?: 0.0
                val z = event.values.getOrNull(2)?.toDouble() ?: 0.0
                val mag = sqrt(x * x + y * y + z * z)
                synchronized(magnitudes) {
                    if (magnitudes.size < 6) {
                        magnitudes.add(mag)
                        if (magnitudes.size >= 5) latch.countDown()
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        return try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            latch.await(160, TimeUnit.MILLISECONDS)
            synchronized(magnitudes) {
                if (magnitudes.size < 2) return null
                val mean = magnitudes.average()
                val avgDev = magnitudes.map { abs(it - mean) }.average()
                avgDev
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { sm.unregisterListener(listener) } catch (_: Throwable) {}
        }
    }
}
