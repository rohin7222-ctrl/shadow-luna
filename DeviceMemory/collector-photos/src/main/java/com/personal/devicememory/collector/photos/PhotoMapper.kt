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
    val isScreenshot: Boolean,
    val visual: PhotoVisualResult? = null,
    val ocrText: String? = null
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
     * Maps a [PhotoItem] to a [CollectedRecord] for database persistence,
     * fusing 2026 visual latent cues + redacted offline OCR text when available.
     */
    fun map(item: PhotoItem): CollectedRecord {
        val effectiveTimestamp = if (item.dateTaken > 0) item.dateTaken else item.dateAdded

        val tagParts = mutableListOf<String>()
        item.visual?.let { v ->
            tagParts.add(v.lightingScene)
            if (v.colorTone != "Neutral") tagParts.add(v.colorTone)
            if (v.isDocumentOrReceipt) tagParts.add("Document/Text")
            tagParts.add(v.visualCode)
        }
        val tagSuffix = if (tagParts.isNotEmpty()) " [${tagParts.joinToString(", ")}]" else ""
        val ocrSuffix = if (!item.ocrText.isNullOrBlank()) " [OCR: ${item.ocrText}]" else ""

        val base = if (item.isScreenshot) {
            "Screenshot: ${item.displayName}"
        } else {
            "Photo: ${item.bucketName ?: "Camera"} - ${item.displayName}"
        }
        val textContent = "$base$tagSuffix$ocrSuffix"

        val visualMap = if (item.visual != null || !item.ocrText.isNullOrBlank()) {
            buildMap<String, Any?> {
                item.visual?.let { v ->
                    put("lighting_scene", v.lightingScene)
                    put("color_tone", v.colorTone)
                    put("avg_luminance", v.avgLuminance)
                    put("contrast_std", v.contrastStd)
                    put("edge_density", v.edgeDensity)
                    put("is_document", v.isDocumentOrReceipt)
                    put("visual_code", v.visualCode)
                    put("visual_latent_16", v.visualLatent16)
                }
                if (!item.ocrText.isNullOrBlank()) {
                    put("ocr_text", item.ocrText)
                }
            }
        } else null

        val metadata = mutableMapOf<String, Any?>(
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
        if (visualMap != null) {
            metadata["visual_analysis"] = visualMap
        }

        return CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = effectiveTimestamp,
            textContent = textContent,
            metadataJson = metadata.toJsonString()
        )
    }
}
