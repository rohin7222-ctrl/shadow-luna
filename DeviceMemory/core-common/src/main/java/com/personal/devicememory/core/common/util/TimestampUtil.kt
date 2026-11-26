package com.personal.devicememory.core.common.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimestampUtil {

    private val BASE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun nowMillis(): Long = System.currentTimeMillis()

    fun formatForDisplay(epochMillis: Long): String =
        BASE_DISPLAY_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

    fun startOfDayMillis(daysAgo: Int = 0): Long {
        val now = Instant.now().atZone(ZoneId.systemDefault())
        return now.minusDays(daysAgo.toLong())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
