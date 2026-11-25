package com.personal.devicememory.core.common.text

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

/**
 * 2026 Zero-Cost Deterministic Temporal Anchor Extractor.
 *
 * Scans bilingual (Tamil + English) text to extract relative/absolute time references
 * (e.g. "today", "இன்று", "tomorrow 5 PM", "நேற்று மாலை") and anchors them to
 * absolute epoch timestamps without invoking an expensive neural model.
 */
object TemporalAnchorExtractor {

    data class TemporalAnchor(
        val matchedPhrase: String,
        val estimatedEpochMillis: Long,
        val confidence: Float,
        val isRelative: Boolean
    )

    private val ENGLISH_DAYS = mapOf(
        "today" to 0,
        "tonight" to 0,
        "tomorrow" to 1,
        "yesterday" to -1,
        "day after tomorrow" to 2,
        "day before yesterday" to -2
    )

    private val TAMIL_DAYS = mapOf(
        "இன்று" to 0,
        "இன்னிக்கு" to 0,
        "நாளை" to 1,
        "நாளைக்கு" to 1,
        "நேற்று" to -1,
        "நேத்து" to -1,
        "நாளை மறுநாள்" to 2,
        "முந்தாநாள்" to -2
    )

    private val TIME_OF_DAY_HOUR = mapOf(
        // English
        "morning" to 9,
        "afternoon" to 14,
        "evening" to 18,
        "night" to 21,
        // Tamil
        "காலை" to 9,
        "மதியம்" to 14,
        "மாலை" to 18,
        "இரவு" to 21
    )

    // Regex for clock times: "5 PM", "10:30 am", "18:00"
    private val CLOCK_TIME_REGEX = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?\b"""
    )

    /**
     * Extracts all temporal anchors found in [text], relative to [referenceEpochMillis].
     */
    fun extractAnchors(
        text: String,
        referenceEpochMillis: Long = System.currentTimeMillis()
    ): List<TemporalAnchor> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<TemporalAnchor>()
        val zone = ZoneId.systemDefault()
        val refDate = Instant.ofEpochMilli(referenceEpochMillis).atZone(zone).toLocalDate()

        // 1. Check relative day markers (English + Tamil)
        val combinedDays = ENGLISH_DAYS + TAMIL_DAYS
        for ((word, dayOffset) in combinedDays) {
            val pattern = Pattern.compile("(?i)(?<=^|\\s)$word(?=\\s|\$|[.,!?;])")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val targetDate = refDate.plusDays(dayOffset.toLong())
                val targetMillis = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
                results.add(
                    TemporalAnchor(
                        matchedPhrase = matcher.group(),
                        estimatedEpochMillis = targetMillis,
                        confidence = 0.95f,
                        isRelative = true
                    )
                )
            }
        }

        // 2. Check time of day qualifiers ("காலை", "evening", etc.)
        for ((word, hour) in TIME_OF_DAY_HOUR) {
            val pattern = Pattern.compile("(?i)(?<=^|\\s)$word(?=\\s|\$|[.,!?;])")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val targetDateTime = refDate.atTime(hour, 0).atZone(zone)
                results.add(
                    TemporalAnchor(
                        matchedPhrase = matcher.group(),
                        estimatedEpochMillis = targetDateTime.toInstant().toEpochMilli(),
                        confidence = 0.85f,
                        isRelative = true
                    )
                )
            }
        }

        return results
    }
}
