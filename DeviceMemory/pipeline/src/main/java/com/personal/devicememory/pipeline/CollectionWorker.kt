package com.personal.devicememory.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity
import com.personal.devicememory.pipeline.brain.OnDeviceLunaBrain
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

        var allSuccess = true

        for (config in enabledConfigs) {
            val sourceType = try {
                SourceType.valueOf(config.sourceType)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unknown source type: ${config.sourceType}")
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

                    // Map to entities with sensitive content redaction
                    val entities = records.map { record ->
                        CollectedRecordEntity(
                            id = UUID.randomUUID().toString(),
                            sourceType = record.sourceType.name,
                            timestamp = record.timestamp,
                            textContent = SensitiveContentFilter.redact(record.textContent),
                            metadataJson = record.metadataJson,
                            processedFlag = 0,
                            createdAt = now
                        )
                    }

                    // Insert into encrypted database
                    db.collectedRecordDao().insertAll(entities)

                    // Learn tokens locally offline on device
                    OnDeviceLunaBrain.learnFromRecords(db, entities)

                    // Update bookmark
                    val latestTimestamp = records.maxOf { it.timestamp }
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

        // Offline-first: data stays encrypted on-device until explicit 2-way handshake sync
        return if (allSuccess) Result.success() else Result.retry()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
