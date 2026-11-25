package com.personal.devicememory.collector.videos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord

/**
 * Collects video metadata (duration, resolution, size, timestamps) from [MediaStore.Video].
 * Metadata-only collection as specified.
 */
class VideoCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Videos"

    companion object {
        const val TAG = "VideoCollector"

        fun hasPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing video permission, skipping collection")
            return emptyList()
        }

        val records = mutableListOf<CollectedRecord>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED
        )

        val (selection, selectionArgs) = if (sinceTimestamp > 0) {
            val sinceSeconds = sinceTimestamp / 1000
            val query = "(${MediaStore.Video.Media.DATE_ADDED} > ?) OR (${MediaStore.Video.Media.DATE_TAKEN} > ?)"
            query to arrayOf(sinceSeconds.toString(), sinceTimestamp.toString())
        } else {
            null to null
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val durationCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val widthCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val dateTakenCol = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val dateAddedCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)

                while (c.moveToNext()) {
                    val mediaId = c.getLong(idCol)
                    val displayName = c.getString(nameCol) ?: "Video_$mediaId"
                    val bucketName = if (bucketCol >= 0) c.getString(bucketCol) else null
                    val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    val sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) c.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) c.getInt(heightCol) else 0
                    val dateTaken = if (dateTakenCol >= 0) c.getLong(dateTakenCol) else 0L
                    val dateAddedSec = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else 0L

                    val item = VideoItem(
                        mediaId = mediaId,
                        displayName = displayName,
                        bucketName = bucketName,
                        mimeType = mimeType,
                        durationMs = durationMs,
                        sizeBytes = sizeBytes,
                        width = width,
                        height = height,
                        dateTaken = dateTaken,
                        dateAdded = dateAddedSec * 1000
                    )

                    records.add(VideoMapper.map(item))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for videos", e)
        }

        Log.i(TAG, "Collected ${records.size} video metadata records")
        return records
    }
}
