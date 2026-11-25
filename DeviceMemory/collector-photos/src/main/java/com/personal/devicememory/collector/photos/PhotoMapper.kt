package com.personal.devicememory.collector.photos

import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Domain representation of an image indexed from MediaStore.
 */
data class PhotoItem(
    val mediaId: Long,
    val displayName: String,
    val bucketName: String?,
    val mimeType: String?,
    val dateTaken: Long,       // epoch millis
    val dateAdded: Long,       // epoch millis
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val isScreenshot: Boolean
)

object PhotoMapper {

    /**
     * Determines whether an image is a screenshot based on file naming or album/bucket name.
     */
    fun isScreenshot(displayName: String, bucketName: String?): Boolean {
        val lowerName = displayName.lowercase()
        val lowerBucket = bucketName?.lowercase() ?: ""
        return lowerName.contains("screenshot") ||
                lowerName.contains("screencap") ||
                lowerBucket.contains("screenshot") ||
                lowerBucket.contains("screencap")
    }

    /**
     * Maps a [PhotoItem] to a [CollectedRecord] for database persistence.
     */
    fun map(item: PhotoItem): CollectedRecord {
        val effectiveTimestamp = if (item.dateTaken > 0) item.dateTaken else item.dateAdded

        val textContent = if (item.isScreenshot) {
            "Screenshot: ${item.displayName}"
        } else {
            "Photo: ${item.bucketName ?: "Camera"} - ${item.displayName}"
        }

        val metadata = mapOf<String, Any?>(
            "media_id" to item.mediaId,
            "display_name" to item.displayName,
            "bucket_name" to item.bucketName,
            "mime_type" to item.mimeType,
            "size_bytes" to item.sizeBytes,
            "width" to item.width,
            "height" to item.height,
            "is_screenshot" to item.isScreenshot,
            "date_taken" to item.dateTaken,
            "date_added" to item.dateAdded
        )

        return CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = effectiveTimestamp,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
