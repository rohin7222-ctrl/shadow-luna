package com.personal.devicememory.collector.videos

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
    val dateAdded: Long
)

object VideoMapper {

    fun map(item: VideoItem): CollectedRecord {
        val effectiveTimestamp = if (item.dateTaken > 0) item.dateTaken else item.dateAdded
        val durationSec = item.durationMs / 1000

        val textContent = "Video: ${item.bucketName ?: "Camera"} - ${item.displayName} (${durationSec}s)"

        val metadata = mapOf<String, Any?>(
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

        return CollectedRecord(
            sourceType = SourceType.VIDEO,
            timestamp = effectiveTimestamp,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
