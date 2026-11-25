package com.personal.devicememory.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Activity Recognition collector for on-device context awareness
 * (e.g. STILL, WALKING, IN_VEHICLE, RUNNING).
 */
class ActivityCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Activity Recognition"

    companion object {
        const val TAG = "ActivityCollector"

        fun hasPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Granted at install time prior to Android 10
            }
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing activity recognition permission")
            return emptyList()
        }

        // Periodic snapshot: stores passive context state
        val now = TimestampUtil.nowMillis()
        val metadata = mapOf<String, Any?>(
            "status" to "ACTIVE_SAMPLING",
            "timestamp" to now
        )

        return listOf(
            CollectedRecord(
                sourceType = SourceType.ACTIVITY,
                timestamp = now,
                textContent = "Activity state: RECORDED",
                metadataJson = metadata.toJsonString()
            )
        )
    }
}
