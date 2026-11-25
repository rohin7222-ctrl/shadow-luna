package com.personal.devicememory.collector.appusage

import android.app.usage.UsageStats
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Maps [UsageStats] to [CollectedRecord].
 */
object AppUsageMapper {

    fun map(stats: UsageStats): CollectedRecord {
        val metadata = mapOf<String, Any?>(
            "package" to stats.packageName,
            "total_foreground_ms" to stats.totalTimeInForeground,
            "last_used" to stats.lastTimeUsed,
            "first_timestamp" to stats.firstTimeStamp,
            "last_timestamp" to stats.lastTimeStamp,
        )

        return CollectedRecord(
            sourceType = SourceType.APP_USAGE,
            timestamp = stats.lastTimeUsed,
            textContent = stats.packageName,
            metadataJson = metadata.toJsonString(),
        )
    }
}
