package com.personal.devicememory.collector.contactscalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Read-only collector for Calendar events from Android [CalendarContract].
 */
class CalendarCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Calendar Events"

    companion object {
        const val TAG = "CalendarCollector"

        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing READ_CALENDAR permission")
            return emptyList()
        }

        val records = mutableListOf<CollectedRecord>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )

        val (selection, selectionArgs) = if (sinceTimestamp > 0) {
            "${CalendarContract.Events.DTSTART} > ?" to arrayOf(sinceTimestamp.toString())
        } else {
            null to null
        }

        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleCol = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val descCol = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val locCol = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val startCol = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val endCol = c.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayCol = c.getColumnIndex(CalendarContract.Events.ALL_DAY)

                val now = TimestampUtil.nowMillis()

                while (c.moveToNext()) {
                    val eventId = c.getLong(idCol)
                    val rawTitle = c.getString(titleCol) ?: "Untitled Event"
                    val rawDesc = if (descCol >= 0) c.getString(descCol) else null
                    val location = if (locCol >= 0) c.getString(locCol) else null
                    val dtStart = if (startCol >= 0) c.getLong(startCol) else now
                    val dtEnd = if (endCol >= 0) c.getLong(endCol) else dtStart
                    val allDay = if (allDayCol >= 0) c.getInt(allDayCol) == 1 else false

                    val title = SensitiveContentFilter.redact(rawTitle) ?: rawTitle
                    val description = SensitiveContentFilter.redact(rawDesc)

                    val textContent = buildString {
                        append("Event: ").append(title)
                        if (!description.isNullOrBlank()) {
                            append(" - ").append(description)
                        }
                        if (!location.isNullOrBlank()) {
                            append(" @ ").append(location)
                        }
                    }

                    val metadata = mapOf<String, Any?>(
                        "event_id" to eventId,
                        "title" to title,
                        "location" to location,
                        "start_time" to dtStart,
                        "end_time" to dtEnd,
                        "all_day" to allDay
                    )

                    records.add(
                        CollectedRecord(
                            sourceType = SourceType.CALENDAR_EVENT,
                            timestamp = dtStart,
                            textContent = textContent,
                            metadataJson = metadata.toJsonString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query CalendarContract", e)
        }

        Log.i(TAG, "Collected ${records.size} calendar event records")
        return records
    }
}
