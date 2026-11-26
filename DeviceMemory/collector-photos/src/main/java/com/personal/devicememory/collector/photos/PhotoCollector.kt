package com.personal.devicememory.collector.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord

/**
 * Collects metadata for photos and screenshots from [MediaStore.Images].
 * Supports incremental querying using date bounds and Android 14+ partial selection.
 */
class PhotoCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Photos & Screenshots"

    companion object {
        const val TAG = "PhotoCollector"

        fun hasPermission(context: Context): Boolean {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                }
                else -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing photo permission, skipping collection")
            return emptyList()
        }

        val items = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        // MediaStore DATE_ADDED is in seconds (>= prevents same-second burst loss; deduplicated downstream)
        val pendingFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else null
        val timeFilter = if (sinceTimestamp > 0) {
            val sinceSeconds = sinceTimestamp / 1000
            "((${MediaStore.Images.Media.DATE_ADDED} >= ?) OR (${MediaStore.Images.Media.DATE_TAKEN} > ?))" to
                arrayOf(sinceSeconds.toString(), sinceTimestamp.toString())
        } else {
            null to null
        }
        val selection = listOfNotNull(pendingFilter, timeFilter.first).joinToString(" AND ").ifEmpty { null }
        val selectionArgs = timeFilter.second

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateTakenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                val widthCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                while (c.moveToNext()) {
                    val mediaId = if (idCol >= 0) c.getLong(idCol) else 0L
                    val displayName = (if (nameCol >= 0) c.getString(nameCol) else null) ?: "Image_$mediaId"
                    val bucketName = if (bucketCol >= 0) c.getString(bucketCol) else null
                    val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val dateTaken = if (dateTakenCol >= 0) c.getLong(dateTakenCol) else 0L
                    val dateAddedSec = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else 0L
                    val sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) c.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) c.getInt(heightCol) else 0

                    val isScreenshot = PhotoMapper.isScreenshot(displayName, bucketName)

                    items.add(
                        PhotoItem(
                            mediaId = mediaId,
                            displayName = displayName,
                            bucketName = bucketName,
                            mimeType = mimeType,
                            dateTaken = dateTaken,
                            dateAdded = dateAddedSec * 1000,
                            sizeBytes = sizeBytes,
                            width = width,
                            height = height,
                            isScreenshot = isScreenshot
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for images", e)
        }

        // Deep visual + offline OCR budget: newest 15 photos/screenshots per pass
        val deepStartIndex = (items.size - 15).coerceAtLeast(0)
        val records = ArrayList<CollectedRecord>(items.size)
        for (i in items.indices) {
            val item = items[i]
            val enriched = if (i >= deepStartIndex && item.mediaId > 0L) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.mediaId)
                try {
                    AndroidPhotoAnalyzer.analyzeUri(context, uri, item.isScreenshot)
                } catch (e: Exception) {
                    Log.d(TAG, "Photo analysis skipped for ${item.mediaId}: ${e.message}")
                    null
                }
            } else null

            records.add(PhotoMapper.map(item.copy(visual = enriched?.visual, ocrText = enriched?.ocrText)))
        }

        Log.i(TAG, "Collected ${records.size} photo/screenshot records (OCR+visual=${items.size - deepStartIndex})")
        return records
    }
}
