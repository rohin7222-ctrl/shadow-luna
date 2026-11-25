package com.personal.devicememory.collector.notifications

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Real-time notification listener service.
 * Captures user notifications, redacts sensitive content (OTPs, passwords, banking info),
 * and persists them into the encrypted database.
 */
class MemoryNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "MemoryNotifListener"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var instance: MemoryNotificationListener? = null
            private set

        // In-memory cache of recent notification hashes to prevent duplicate writes
        private val recentHashes = LinkedHashMap<String, Long>()
        private const val MAX_RECENT_HASHES = 100

        @Synchronized
        fun isDuplicate(hash: String, now: Long): Boolean {
            val lastSeen = recentHashes[hash]
            if (lastSeen != null && (now - lastSeen) < 60_000) {
                return true // duplicate within 60 seconds
            }
            recentHashes[hash] = now
            if (recentHashes.size > MAX_RECENT_HASHES) {
                val oldest = recentHashes.keys.first()
                recentHashes.remove(oldest)
            }
            return false
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Ignore notifications posted by our own app to avoid recursive feedback loops
        if (sbn.packageName == packageName) return

        serviceScope.launch {
            try {
                val db = DeviceMemoryDatabase.getInstance(applicationContext)

                // Check if Notifications collector is enabled in user config
                val config = db.collectorConfigDao().getConfig(SourceType.NOTIFICATION.name)
                if (config?.enabled != 1) {
                    return@launch
                }

                val parsed = NotificationMapper.parse(sbn) ?: return@launch
                val now = TimestampUtil.nowMillis()

                // Deduplicate repetitive progress notifications
                if (isDuplicate(parsed.contentHash, now)) {
                    return@launch
                }

                val record = NotificationMapper.mapToRecord(parsed)

                // Apply sensitive content filter (redact OTPs, passwords, bank cards, etc.)
                val sanitizedText = SensitiveContentFilter.redact(record.textContent)

                val entityId = UUID.nameUUIDFromBytes(
                    "NOTIFICATION|${record.timestamp}|${parsed.contentHash}".toByteArray(Charsets.UTF_8)
                ).toString()

                val entity = CollectedRecordEntity(
                    id = entityId,
                    sourceType = SourceType.NOTIFICATION.name,
                    timestamp = record.timestamp,
                    textContent = sanitizedText,
                    metadataJson = record.metadataJson,
                    processedFlag = 0,
                    createdAt = now
                )

                val insertedRowIds = db.collectedRecordDao().insertAll(listOf(entity))
                val insertedRowId = insertedRowIds.firstOrNull() ?: -1L
                if (insertedRowId < 0L) {
                    return@launch
                }

                // Index redacted notification tokens locally offline for OnDeviceLunaBrain
                val notifTokens = Regex("""[\p{L}\p{M}\p{N}_]{2,40}""")
                    .findAll((sanitizedText ?: "").lowercase())
                    .map { it.value }
                    .filter { tok -> tok.any { it.isLetterOrDigit() } }
                    .distinct()
                    .take(25)
                for (tok in notifTokens) {
                    db.learnedTokenDao().recordToken(tok, SourceType.NOTIFICATION.name, record.timestamp)
                }

                // Update bookmark
                db.syncBookmarkDao().upsert(
                    SyncBookmarkEntity(
                        sourceType = SourceType.NOTIFICATION.name,
                        lastTimestamp = record.timestamp,
                        lastItemHash = parsed.contentHash,
                        updatedAt = now
                    )
                )

                // Update last run status
                db.collectorConfigDao().updateRunStatus(
                    sourceType = SourceType.NOTIFICATION.name,
                    runAt = now,
                    status = "SUCCESS"
                )

                Log.d(TAG, "Stored notification from: ${parsed.packageName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed for dismissed notifications
    }
}

/**
 * Collector implementation for the pipeline / WorkManager.
 */
class NotificationCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Notifications"

    companion object {
        fun hasPermission(context: Context): Boolean {
            return try {
                val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
                enabledListeners.contains(context.packageName)
            } catch (e: Throwable) {
                false
            }
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) return emptyList()

        val listener = MemoryNotificationListener.instance ?: return emptyList()
        val activeSbns = try {
            listener.activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        val records = mutableListOf<CollectedRecord>()
        for (sbn in activeSbns) {
            if (sbn.packageName == context.packageName) continue
            if (sbn.postTime <= sinceTimestamp) continue

            val parsed = NotificationMapper.parse(sbn) ?: continue
            records.add(NotificationMapper.mapToRecord(parsed))
        }

        return records
    }
}
