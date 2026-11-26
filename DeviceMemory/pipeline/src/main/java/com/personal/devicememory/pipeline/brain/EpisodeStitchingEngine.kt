package com.personal.devicememory.pipeline.brain

import android.content.Context
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.EpisodeEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2026 Cross-Modal Spatio-Temporal Episode Stitching Engine ("Life Moments").
 * Groups nearby memories within ±20 mins (splitting on >500m GPS displacement when location exists)
 * into coherent multi-sensor Episode summaries purely from user data.
 */
object EpisodeStitchingEngine {

    private const val EPISODE_GAP_MS = 20 * 60 * 1000L // 20 minutes
    private const val GEO_SPLIT_METERS = 500.0

    private fun extractLatLon(metadataJson: String?): Pair<Double, Double>? {
        if (metadataJson.isNullOrBlank()) return null
        return try {
            val obj = Json.parseToJsonElement(metadataJson).jsonObject
            val lat = obj["latitude"]?.jsonPrimitive?.doubleOrNull
            val lon = obj["longitude"]?.jsonPrimitive?.doubleOrNull
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) lat to lon else null
        } catch (_: Throwable) {
            null
        }
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    fun stitchEpisodes(
        records: List<CollectedRecordEntity>,
        conceptExtractor: (String, String?) -> List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<EpisodeEntity> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedBy { it.timestamp }
        val clusters = ArrayList<MutableList<CollectedRecordEntity>>()

        var currentCluster = mutableListOf(sorted.first())
        var lastLatLon = extractLatLon(sorted.first().metadataJson)

        for (i in 1 until sorted.size) {
            val rec = sorted[i]
            val prev = currentCluster.last()
            val dt = rec.timestamp - prev.timestamp
            val curLatLon = extractLatLon(rec.metadataJson)
            val movedTooFar = if (lastLatLon != null && curLatLon != null) {
                haversineMeters(lastLatLon.first, lastLatLon.second, curLatLon.first, curLatLon.second) > GEO_SPLIT_METERS
            } else false

            if (dt > EPISODE_GAP_MS || movedTooFar) {
                if (currentCluster.size >= 2) clusters.add(currentCluster)
                currentCluster = mutableListOf(rec)
                lastLatLon = curLatLon
            } else {
                currentCluster.add(rec)
                if (curLatLon != null) lastLatLon = curLatLon
            }
        }
        if (currentCluster.size >= 2) clusters.add(currentCluster)

        return clusters.mapNotNull { cluster ->
            val start = cluster.first().timestamp
            val end = cluster.last().timestamp
            val bucketId = start / EPISODE_GAP_MS
            val episodeId = "ep_${bucketId}_${cluster.first().sourceType.take(3)}"

            val srcCounts = linkedMapOf<String, Int>()
            val conceptFreq = linkedMapOf<String, Int>()
            var place = ""

            for (r in cluster) {
                srcCounts[r.sourceType] = (srcCounts[r.sourceType] ?: 0) + 1
                if (place.isBlank() && r.sourceType == "LOCATION" && !r.textContent.isNullOrBlank()) {
                    place = r.textContent!!.take(48)
                }
                for (c in conceptExtractor(r.sourceType, r.textContent)) {
                    conceptFreq[c] = (conceptFreq[c] ?: 0) + 1
                }
            }

            val topConcepts = conceptFreq.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }
            if (topConcepts.isEmpty() && srcCounts.isEmpty()) return@mapNotNull null

            val bucket = LunaMemorySimulator.timeBucketLabel(start)
            val srcDesc = srcCounts.entries.joinToString(" + ") { (src, cnt) ->
                val readable = when (src) {
                    "PHOTO" -> if (cnt == 1) "Photo" else "Photos"
                    "VIDEO" -> if (cnt == 1) "Video" else "Videos"
                    "AUDIO" -> "Audio"
                    "NOTIFICATION" -> "Notifs"
                    "APP_USAGE" -> "Apps"
                    "LOCATION" -> "Place"
                    else -> src.lowercase()
                }
                "$cnt $readable"
            }
            val startStr = TimestampUtil.formatForDisplay(start).substring(11, 16)
            val endStr = TimestampUtil.formatForDisplay(end).substring(11, 16)
            val conceptPart = if (topConcepts.isNotEmpty()) topConcepts.joinToString(", ") else "activity"
            val titleLabel = "$bucket Moment ($startStr–$endStr)"
            val summary = "$titleLabel: $srcDesc • themes: $conceptPart"

            EpisodeEntity(
                episodeId = episodeId,
                titleLabel = titleLabel,
                startMillis = start,
                endMillis = end,
                primaryPlace = place,
                dominantSources = srcCounts.keys.joinToString(","),
                keyConcepts = topConcepts.joinToString(", "),
                recordCount = cluster.size,
                narrativeSummary = summary,
                updatedAt = nowMs
            )
        }
    }

    /**
     * Loads recent records from encrypted Room vault, stitches cross-modal episodes, and upserts them.
     */
    suspend fun stitchRecentFromDb(context: Context, limit: Int = 160): Int {
        val db = DeviceMemoryDatabase.getInstance(context)
        val recent = db.collectedRecordDao().getRecentAll(limit)
        if (recent.size < 2) return db.brainSimulationDao().countEpisodes()
        val episodes = stitchEpisodes(
            records = recent,
            conceptExtractor = { src, txt -> LunaMemorySimulator.extractSemanticConcepts(src, txt) },
            nowMs = TimestampUtil.nowMillis()
        )
        if (episodes.isNotEmpty()) {
            db.brainSimulationDao().upsertEpisodes(episodes)
        }
        return db.brainSimulationDao().countEpisodes()
    }
}
