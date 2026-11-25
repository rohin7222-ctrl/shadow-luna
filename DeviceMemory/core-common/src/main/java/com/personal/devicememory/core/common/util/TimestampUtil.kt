package com.personal.devicememory.core.common.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimestampUtil {

    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun nowMillis(): Long = System.currentTimeMillis()

    fun formatForDisplay(epochMillis: Long): String =
        DISPLAY_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    fun startOfDayMillis(daysAgo: Int = 0): Long {
        val now = Instant.now().atZone(ZoneId.systemDefault())
        return now.minusDays(daysAgo.toLong())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
