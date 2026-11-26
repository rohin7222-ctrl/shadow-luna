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
    val analysis: AudioAnalysisResult? = null,
    val speakerCluster: String? = null
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
                val spk = if (!item.speakerCluster.isNullOrBlank()) ", ${item.speakerCluster}" else ""
                append(" [${a.acousticScene}, VAD ${vadPct}%, ${a.spectralCentroidHz}Hz, ${a.acousticCode}$spk]")
            }
        }

        val analysisMap = item.analysis?.let { a ->
            buildMap<String, Any?> {
                put("acoustic_scene", a.acousticScene)
                put("speech_ratio", a.speechRatio)
                put("spectral_centroid_hz", a.spectralCentroidHz)
                put("spectral_flatness", a.spectralFlatness)
                put("spectral_flux", a.spectralFlux)
                put("rms_db", a.rmsDb)
                put("acoustic_code", a.acousticCode)
                put("mfcc_signature", a.mfccSignature)
                put("mel_patch_latent", a.melPatchLatent)
                if (!item.speakerCluster.isNullOrBlank()) {
                    put("speaker_cluster", item.speakerCluster)
                }
            }
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
