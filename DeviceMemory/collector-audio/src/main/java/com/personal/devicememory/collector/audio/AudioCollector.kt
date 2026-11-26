package com.personal.devicememory.collector.audio

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
 * Collects audio files and voice recordings metadata (duration, title, artist, size)
 * from [MediaStore.Audio]. Metadata-only collection as specified.
 */
class AudioCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Audio & Voice Recordings"

    companion object {
        const val TAG = "AudioCollector"

        fun hasPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_AUDIO
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
            Log.w(TAG, "Missing audio permission, skipping collection")
            return emptyList()
        }

        val items = mutableListOf<AudioItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val pendingFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else null
        val timeFilter = if (sinceTimestamp > 0) {
            val sinceSeconds = sinceTimestamp / 1000
            "${MediaStore.Audio.Media.DATE_ADDED} >= ?" to arrayOf(sinceSeconds.toString())
        } else {
            null to null
        }
        val selection = listOfNotNull(pendingFilter, timeFilter.first).joinToString(" AND ").ifEmpty { null }
        val selectionArgs = timeFilter.second

        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dateAddedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

                while (c.moveToNext()) {
                    val mediaId = if (idCol >= 0) c.getLong(idCol) else 0L
                    val displayName = (if (nameCol >= 0) c.getString(nameCol) else null) ?: "Audio_$mediaId"
                    val title = if (titleCol >= 0) c.getString(titleCol) else null
                    val artist = if (artistCol >= 0) c.getString(artistCol) else null
                    val album = if (albumCol >= 0) c.getString(albumCol) else null
                    val durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L
                    val sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                    val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val dateAddedSec = if (dateAddedCol >= 0) c.getLong(dateAddedCol) else 0L

                    items.add(
                        AudioItem(
                            mediaId = mediaId,
                            displayName = displayName,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            mimeType = mimeType,
                            dateAdded = dateAddedSec * 1000
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for audio", e)
        }

        // Deep spectral & acoustic decode budget: newest 20 items per collection pass
        val deepStartIndex = (items.size - 20).coerceAtLeast(0)
        val records = ArrayList<CollectedRecord>(items.size)
        for (i in items.indices) {
            val item = items[i]
            val analysis = if (i >= deepStartIndex && item.mediaId > 0L) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, item.mediaId)
                try {
                    AndroidAudioDecoder.analyzeUri(context, uri)
                } catch (e: Exception) {
                    Log.d(TAG, "Spectral audio decode skipped for ${item.mediaId}: ${e.message}")
                    null
                }
            } else null

            records.add(AudioMapper.map(item.copy(analysis = analysis)))
        }

        Log.i(TAG, "Collected ${records.size} audio records (spectral analyzed=${items.size - deepStartIndex})")
        return records
    }
}
