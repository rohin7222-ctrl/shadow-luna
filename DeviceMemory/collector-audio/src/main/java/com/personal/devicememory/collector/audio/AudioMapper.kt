package com.personal.devicememory.collector.audio

import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.toJsonString

data class AudioItem(
    val mediaId: Long,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateAdded: Long,
    val analysis: AudioAnalysisResult? = null
)

object AudioMapper {

    fun map(item: AudioItem): CollectedRecord {
        val durationSec = item.durationMs / 1000
        val artistLabel = if (!item.artist.isNullOrBlank() && item.artist != "<unknown>") item.artist else null
        val titleLabel = if (!item.title.isNullOrBlank()) item.title else item.displayName

        val textContent = buildString {
            append("Audio: ")
            append(titleLabel)
            if (artistLabel != null) {
                append(" by ").append(artistLabel)
            }
            if (durationSec > 0) {
                append(" (${durationSec}s)")
            }
            val a = item.analysis
            if (a != null) {
                val vadPct = (a.speechRatio * 100.0).toInt().coerceIn(0, 100)
                append(" [${a.acousticScene}, VAD ${vadPct}%, ${a.spectralCentroidHz}Hz, ${a.acousticCode}]")
            }
        }

        val analysisMap = item.analysis?.let { a ->
            mapOf<String, Any?>(
                "acoustic_scene" to a.acousticScene,
                "speech_ratio" to a.speechRatio,
                "spectral_centroid_hz" to a.spectralCentroidHz,
                "spectral_flatness" to a.spectralFlatness,
                "spectral_flux" to a.spectralFlux,
                "rms_db" to a.rmsDb,
                "acoustic_code" to a.acousticCode,
                "mfcc_signature" to a.mfccSignature,
                "mel_patch_latent" to a.melPatchLatent
            )
        }

        val metadata = mutableMapOf<String, Any?>(
            "media_id" to item.mediaId,
            "display_name" to item.displayName,
            "title" to item.title,
            "artist" to item.artist,
            "album" to item.album,
            "duration_ms" to item.durationMs,
            "size_bytes" to item.sizeBytes,
            "mime_type" to item.mimeType,
            "date_added" to item.dateAdded
        )
        if (analysisMap != null) {
            metadata["audio_analysis"] = analysisMap
        }

        return CollectedRecord(
            sourceType = SourceType.AUDIO,
            timestamp = item.dateAdded,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
