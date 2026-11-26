package com.personal.devicememory.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TemporalAnchorExtractorTest {

    @Test
    fun testExtractTamilRelativeDays() {
        val refMillis = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val text = "நாளை காலை மீட்டிங் உள்ளது"

        val anchors = TemporalAnchorExtractor.extractAnchors(text, refMillis)

        assertTrue(anchors.isNotEmpty())
        val matched = anchors.map { it.matchedPhrase }
        assertTrue(matched.contains("நாளை"))
        assertTrue(matched.contains("காலை"))
    }

    @Test
    fun testExtractEnglishRelativeDays() {
        val refMillis = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val text = "Flight to Chennai tomorrow evening"

        val anchors = TemporalAnchorExtractor.extractAnchors(text, refMillis)

        assertEquals(2, anchors.size)
        val phrases = anchors.map { it.matchedPhrase.lowercase() }
        assertTrue(phrases.contains("tomorrow"))
        assertTrue(phrases.contains("evening"))
    }

    @Test
    fun testClockTimeAndQueryWindowResolution() {
        val refMillis = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val anchors = TemporalAnchorExtractor.extractAnchors("Doctor appointment tomorrow 5:30 pm", refMillis)
        assertTrue(anchors.any { it.matchedPhrase.equals("tomorrow", ignoreCase = true) })
        assertTrue(anchors.any { it.matchedPhrase.equals("5:30 pm", ignoreCase = true) })

        val win = TemporalAnchorExtractor.extractQueryWindow("what did i do yesterday morning", refMillis)
        assertTrue(win != null)
        assertEquals(5, win!!.preferredHourStart)
        assertEquals(11, win.preferredHourEnd)
        assertTrue(win.endMillis < refMillis + 86_400_000L)
    }

    @Test
    fun testMultiWordRelativeDaysDoNotDoubleAccumulate() {
        val refMillis = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val zone = ZoneId.systemDefault()
        val refDate = Instant.ofEpochMilli(refMillis).atZone(zone).toLocalDate()

        val anchors = TemporalAnchorExtractor.extractAnchors("Trip day after tomorrow at 10:30 am", refMillis)
        val dayAnchors = anchors.filter { it.matchedPhrase.contains("tomorrow", ignoreCase = true) }
        assertEquals(1, dayAnchors.size)
        assertEquals("day after tomorrow", dayAnchors[0].matchedPhrase.lowercase())
        val expectedMillis = refDate.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedMillis, dayAnchors[0].estimatedEpochMillis)

        // Query window preserves +2 days and time-of-day even with "last week evening"
        val datWin = TemporalAnchorExtractor.extractQueryWindow("schedule day after tomorrow", refMillis)!!
        assertEquals(expectedMillis, datWin.startMillis)
        val lwWin = TemporalAnchorExtractor.extractQueryWindow("messages last week evening", refMillis)!!
        assertEquals(17, lwWin.preferredHourStart)
        assertEquals(20, lwWin.preferredHourEnd)
    }
}
