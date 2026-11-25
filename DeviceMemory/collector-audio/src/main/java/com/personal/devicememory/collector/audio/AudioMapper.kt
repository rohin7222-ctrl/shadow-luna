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
    val dateAdded: Long
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
        }

        val metadata = mapOf<String, Any?>(
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

        return CollectedRecord(
            sourceType = SourceType.AUDIO,
            timestamp = item.dateAdded,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
