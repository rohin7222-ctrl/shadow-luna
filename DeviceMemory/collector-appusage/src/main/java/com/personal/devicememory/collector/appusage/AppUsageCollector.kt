package com.personal.devicememory.collector.appusage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.util.TimestampUtil

/**
 * Collects daily app usage stats via [UsageStatsManager].
 *
 * Requires PACKAGE_USAGE_STATS permission (granted in Settings → Usage Access).
 */
class AppUsageCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "App Usage"

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission()) return emptyList()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyList()

        val endTime = TimestampUtil.nowMillis()
        val startTime = if (sinceTimestamp > 0) sinceTimestamp else TimestampUtil.startOfDayMillis(1)

        val statsMap = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: return emptyList()

        return statsMap
            .filter { it.totalTimeInForeground > 0 }
            .filter { it.lastTimeUsed > sinceTimestamp }
            .map { AppUsageMapper.map(it) }
    }

    /**
     * Check if PACKAGE_USAGE_STATS permission is granted.
     * Backwards-compatible across all supported Android versions.
     */
    fun hasPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }
}
