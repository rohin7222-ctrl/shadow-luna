package com.personal.devicememory.collector.videos

import com.personal.devicememory.collector.audio.AudioAnalysisResult
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.toJsonString

data class VideoItem(
    val mediaId: Long,
    val displayName: String,
    val bucketName: String?,
    val mimeType: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    val dateAdded: Long,
    val visual: VideoAnalysisResult? = null,
    val audio: AudioAnalysisResult? = null
)

object VideoMapper {

    fun map(item: VideoItem): CollectedRecord {
        val effectiveTimestamp = if (item.dateTaken > 0) item.dateTaken else item.dateAdded
        val durationSec = item.durationMs / 1000

        val avSummaryParts = mutableListOf<String>()
        item.visual?.let { v ->
            avSummaryParts.add(v.motionScene)
            avSummaryParts.add(v.lightingScene)
            if (v.colorTone != "Neutral") avSummaryParts.add(v.colorTone)
            if (!v.codec.isNullOrBlank()) avSummaryParts.add(v.codec)
            avSummaryParts.add(v.visualCode)
        }
        item.audio?.let { a ->
            val vadPct = (a.speechRatio * 100.0).toInt().coerceIn(0, 100)
            avSummaryParts.add("${a.acousticScene} VAD ${vadPct}%")
            avSummaryParts.add(a.acousticCode)
        }

        val avSuffix = if (avSummaryParts.isNotEmpty()) {
            " [${avSummaryParts.joinToString(", ")}]"
        } else ""

        val textContent = "Video: ${item.bucketName ?: "Camera"} - ${item.displayName} (${durationSec}s)$avSuffix"

        val avMap = mutableMapOf<String, Any?>()
        item.visual?.let { v ->
            avMap["motion_scene"] = v.motionScene
            avMap["motion_score"] = v.motionScore
            avMap["v_jepa_novelty"] = v.vJepaNovelty
            avMap["lighting_scene"] = v.lightingScene
            avMap["avg_luminance"] = v.avgLuminance
            avMap["scene_cut_count"] = v.sceneCutCount
            avMap["color_tone"] = v.colorTone
            avMap["codec"] = v.codec
            avMap["estimated_fps"] = v.estimatedFps
            avMap["has_audio_track"] = v.hasAudioTrack
            avMap["visual_code"] = v.visualCode
            avMap["visual_latent_16"] = v.visualLatent16
        }
        item.audio?.let { a ->
            avMap["audio_scene"] = a.acousticScene
            avMap["speech_ratio"] = a.speechRatio
            avMap["spectral_centroid_hz"] = a.spectralCentroidHz
            avMap["acoustic_code"] = a.acousticCode
            avMap["mel_patch_latent"] = a.melPatchLatent
        }

        val metadata = mutableMapOf<String, Any?>(
            "media_id" to item.mediaId,
            "display_name" to item.displayName,
            "bucket_name" to item.bucketName,
            "mime_type" to item.mimeType,
            "duration_ms" to item.durationMs,
            "size_bytes" to item.sizeBytes,
            "width" to item.width,
            "height" to item.height,
            "date_taken" to item.dateTaken,
            "date_added" to item.dateAdded
        )
        if (avMap.isNotEmpty()) {
            metadata["av_analysis"] = avMap
        }

        return CollectedRecord(
            sourceType = SourceType.VIDEO,
            timestamp = effectiveTimestamp,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
