package com.personal.devicememory.pipeline

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity
import com.personal.devicememory.pipeline.brain.EpisodeStitchingEngine
import com.personal.devicememory.pipeline.brain.OnDeviceLunaBrain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.UUID

/**
 * WorkManager worker that runs all enabled collectors.
 * Designed to run nightly when the device is charging and idle.
 */
class CollectionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "CollectionWorker"
        const val WORK_NAME = "devicememory_nightly_collection"

        internal fun stableRecordUuid(record: CollectedRecord): String {
            val meta = try {
                Json.parseToJsonElement(record.metadataJson).jsonObject
            } catch (_: Throwable) {
                null
            }
            val keyPart = when (record.sourceType) {
                SourceType.PHOTO, SourceType.VIDEO, SourceType.AUDIO -> {
                    val mediaId = meta?.get("media_id")?.jsonPrimitive?.contentOrNull
                    val dateAdded = meta?.get("date_added")?.jsonPrimitive?.longOrNull ?: record.timestamp
                    "${mediaId ?: record.textContent.orEmpty()}|$dateAdded"
                }
                SourceType.NOTIFICATION -> {
                    val contentHash = meta?.get("content_hash")?.jsonPrimitive?.contentOrNull ?: sha256(record.textContent.orEmpty())
                    "${record.timestamp}|$contentHash"
                }
                SourceType.CALENDAR_EVENT -> {
                    val eventId = meta?.get("event_id")?.jsonPrimitive?.contentOrNull ?: record.textContent.orEmpty()
                    "$eventId|${record.timestamp}"
                }
                SourceType.CONTACT -> {
                    val contactId = meta?.get("contact_id")?.jsonPrimitive?.contentOrNull ?: ""
                    val displayName = meta?.get("display_name")?.jsonPrimitive?.contentOrNull ?: record.textContent.orEmpty()
                    "$contactId|$displayName"
                }
                SourceType.APP_USAGE -> {
                    val pkg = meta?.get("package")?.jsonPrimitive?.contentOrNull ?: record.textContent.orEmpty()
                    "$pkg|${record.timestamp}"
                }
                SourceType.LOCATION, SourceType.ACTIVITY -> {
                    "${record.timestamp}|${record.textContent.orEmpty()}"
                }
            }
            return UUID.nameUUIDFromBytes("${record.sourceType.name}|$keyPart".toByteArray(Charsets.UTF_8)).toString()
        }

        internal fun computeSafeBookmarkTimestamp(
            sourceType: SourceType,
            records: List<CollectedRecord>,
            sinceTimestamp: Long,
            nowMs: Long
        ): Long {
            if (sourceType == SourceType.CALENDAR_EVENT) {
                val recentFloor = (nowMs - 86_400_000L).coerceAtLeast(0L)
                return maxOf(sinceTimestamp, minOf(records.maxOf { it.timestamp }, recentFloor))
            }
            val candidate = if (sourceType == SourceType.PHOTO || sourceType == SourceType.VIDEO || sourceType == SourceType.AUDIO) {
                records.maxOf { rec ->
                    val added = try {
                        Json.parseToJsonElement(rec.metadataJson).jsonObject["date_added"]?.jsonPrimitive?.longOrNull ?: 0L
                    } catch (_: Throwable) {
                        0L
                    }
                    // Guard against future EXIF/DATE_TAKEN pushing media bookmarks ahead of real clock
                    maxOf(rec.timestamp.coerceAtMost(nowMs), added.coerceAtMost(nowMs))
                }
            } else {
                records.maxOf { it.timestamp.coerceAtMost(nowMs) }
            }
            return maxOf(sinceTimestamp, candidate.coerceAtMost(nowMs))
        }

        internal fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun isHeavyMediaSource(sourceType: SourceType): Boolean =
            sourceType == SourceType.PHOTO || sourceType == SourceType.VIDEO || sourceType == SourceType.AUDIO

        /**
         * Throttles heavy ML media collectors (OCR, Video JEPA, MFCC audio) during automatic
         * background runs when Battery Saver is active or unplugged battery falls below 20%.
         */
        internal fun shouldSkipForBattery(
            sourceType: SourceType,
            isManual: Boolean,
            isPowerSaveMode: Boolean,
            batteryPct: Int,
            isCharging: Boolean
        ): Boolean {
            if (isManual || !isHeavyMediaSource(sourceType)) return false
            return isPowerSaveMode || (!isCharging && batteryPct in 1..19)
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Collection worker started")

        val db = DeviceMemoryDatabase.getInstance(applicationContext)
        val registry = CollectorRegistryProvider.getRegistry(applicationContext)

        val enabledConfigs = db.collectorConfigDao().getEnabled()
        if (enabledConfigs.isEmpty()) {
            Log.i(TAG, "No collectors enabled, skipping")
            return Result.success()
        }

        val isManual = inputData.getBoolean(WorkScheduler.KEY_MANUAL_TRIGGER, false)
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isPowerSave = pm?.isPowerSaveMode == true
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = bm?.isCharging == true

        var allSuccess = true
        var totalInserted = 0

        for (config in enabledConfigs) {
            val sourceType = try {
                SourceType.valueOf(config.sourceType)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unknown source type: ${config.sourceType}")
                continue
            }

            if (shouldSkipForBattery(sourceType, isManual, isPowerSave, batteryPct, isCharging)) {
                Log.i(TAG, "Deferred heavy media collector $sourceType due to battery saver / low power ($batteryPct%)")
                continue
            }

            val collector = registry.get(sourceType)
            if (collector == null) {
                Log.w(TAG, "No collector registered for $sourceType")
                continue
            }

            try {
                // Get the bookmark for incremental processing
                val bookmark = db.syncBookmarkDao().getBookmark(sourceType.name)
                val sinceTimestamp = bookmark?.lastTimestamp ?: 0L

                // Collect new records
                val records = collector.collect(sinceTimestamp)
                Log.i(TAG, "Collected ${records.size} records from $sourceType")

                if (records.isNotEmpty()) {
                    val now = TimestampUtil.nowMillis()

                    // Map to entities with deterministic deduplication IDs + sensitive content redaction
                    val entities = records.map { record ->
                        CollectedRecordEntity(
                            id = stableRecordUuid(record),
                            sourceType = record.sourceType.name,
                            timestamp = record.timestamp,
                            textContent = SensitiveContentFilter.redact(record.textContent),
                            metadataJson = record.metadataJson,
                            processedFlag = 0,
                            createdAt = now
                        )
                    }

                    // Insert into encrypted database and learn tokens ONLY for newly inserted rows
                    val insertRowIds = db.collectedRecordDao().insertAll(entities)
                    val newlyInserted = entities.filterIndexed { idx, _ ->
                        insertRowIds.getOrNull(idx)?.let { it >= 0L } == true
                    }
                    if (newlyInserted.isNotEmpty()) {
                        totalInserted += newlyInserted.size
                        OnDeviceLunaBrain.learnFromRecords(db, newlyInserted)
                    }

                    // Advance bookmark safely without letting future calendar/EXIF timestamps lock out future syncs
                    val latestTimestamp = computeSafeBookmarkTimestamp(sourceType, records, sinceTimestamp, now)
                    val lastHash = sha256(records.last().metadataJson)
                    db.syncBookmarkDao().upsert(
                        SyncBookmarkEntity(
                            sourceType = sourceType.name,
                            lastTimestamp = latestTimestamp,
                            lastItemHash = lastHash,
                            updatedAt = now
                        )
                    )
                }

                // Update config status
                db.collectorConfigDao().updateRunStatus(
                    sourceType = sourceType.name,
                    runAt = TimestampUtil.nowMillis(),
                    status = "SUCCESS"
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error collecting $sourceType", e)
                allSuccess = false
                db.collectorConfigDao().updateRunStatus(
                    sourceType = sourceType.name,
                    runAt = TimestampUtil.nowMillis(),
                    status = "FAILED",
                    error = e.message
                )
            }
        }

        if (totalInserted > 0) {
            try {
                EpisodeStitchingEngine.stitchRecentFromDb(applicationContext, limit = 160)
            } catch (e: Throwable) {
                Log.w(TAG, "Episode stitch after collection skipped", e)
            }
        }

        // Notify any active Home Screen Memory Widgets to refresh counts cleanly
        try {
            val mgr = AppWidgetManager.getInstance(applicationContext)
            val comp = ComponentName(
                applicationContext.packageName,
                "com.personal.devicememory.app.widget.ShadowMemoryWidgetProvider"
            )
            val ids = mgr.getAppWidgetIds(comp)
            if (ids.isNotEmpty()) {
                val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = comp
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                applicationContext.sendBroadcast(updateIntent)
            }
        } catch (_: Throwable) {}

        // Offline-first: data stays encrypted on-device until explicit 2-way handshake sync
        return if (allSuccess) Result.success() else Result.retry()
    }

    // Identity and bookmark helpers live on Companion for unit testability.
}
