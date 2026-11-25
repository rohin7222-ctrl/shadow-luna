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

    data class TemporalWindow(
        val startMillis: Long,
        val endMillis: Long,
        val label: String,
        val preferredHourStart: Int? = null,
        val preferredHourEnd: Int? = null
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

    private val TIME_OF_DAY_WINDOW = mapOf(
        "morning" to (5 to 11),
        "காலை" to (5 to 11),
        "afternoon" to (12 to 16),
        "மதியம்" to (12 to 16),
        "evening" to (17 to 20),
        "மாலை" to (17 to 20),
        "night" to (21 to 4),
        "இரவு" to (21 to 4)
    )

    // Regex for clock times: "5 PM", "10:30 am", "18:00"
    private val CLOCK_TIME_REGEX = Regex(
        """(?<!\d)(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?=\s|$|[.,!?;])|(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?=\s|$|[.,!?;])""",
        RegexOption.IGNORE_CASE
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
        var targetDate = Instant.ofEpochMilli(referenceEpochMillis).atZone(zone).toLocalDate()

        // 1. Check relative day markers (English + Tamil)
        val combinedDays = ENGLISH_DAYS + TAMIL_DAYS
        for ((word, dayOffset) in combinedDays) {
            val pattern = Pattern.compile("(?i)(?<=^|\\s)${Pattern.quote(word)}(?=\\s|\$|[.,!?;])")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val resolvedDate = targetDate.plusDays(dayOffset.toLong())
                targetDate = resolvedDate
                val targetMillis = resolvedDate.atStartOfDay(zone).toInstant().toEpochMilli()
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
            val pattern = Pattern.compile("(?i)(?<=^|\\s)${Pattern.quote(word)}(?=\\s|\$|[.,!?;])")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val targetDateTime = targetDate.atTime(hour, 0).atZone(zone)
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

        // 3. Explicit clock times ("5 pm", "10:30 am", "18:00")
        val clockMatch = CLOCK_TIME_REGEX.find(text)
        if (clockMatch != null) {
            val hourRaw12 = clockMatch.groupValues[1].toIntOrNull()
            val min12 = clockMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = clockMatch.groupValues[3].lowercase()
            val hour24Explicit = clockMatch.groupValues[4].toIntOrNull()
            val min24 = clockMatch.groupValues[5].toIntOrNull() ?: 0

            val (hour24, minute) = when {
                hourRaw12 != null && hourRaw12 in 1..12 && amPm.isNotEmpty() -> {
                    val converted = when {
                        amPm == "pm" && hourRaw12 < 12 -> hourRaw12 + 12
                        amPm == "am" && hourRaw12 == 12 -> 0
                        else -> hourRaw12
                    }
                    converted to min12.coerceIn(0, 59)
                }
                hour24Explicit != null -> hour24Explicit.coerceIn(0, 23) to min24.coerceIn(0, 59)
                else -> -1 to -1
            }

            if (hour24 in 0..23) {
                val clockDt = targetDate.atTime(hour24, minute).atZone(zone)
                results.add(
                    TemporalAnchor(
                        matchedPhrase = clockMatch.value.trim(),
                        estimatedEpochMillis = clockDt.toInstant().toEpochMilli(),
                        confidence = 0.92f,
                        isRelative = true
                    )
                )
            }
        }

        return results
    }

    /**
     * Resolves a natural-language user query into an epoch search window + optional time-of-day filter
     * for temporal reasoning in OnDeviceLunaBrain.
     */
    fun extractQueryWindow(
        query: String,
        referenceEpochMillis: Long = System.currentTimeMillis()
    ): TemporalWindow? {
        if (query.isBlank()) return null
        val qLower = query.lowercase()
        val zone = ZoneId.systemDefault()
        val refDate = Instant.ofEpochMilli(referenceEpochMillis).atZone(zone).toLocalDate()

        var dayOffset: Int? = null
        var matchedDayLabel: String? = null
        if (qLower.contains("last week") || qLower.contains("பழைய") || qLower.contains("இந்த வாரம்")) {
            val start = refDate.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = referenceEpochMillis
            return TemporalWindow(start, end, "Last 7 days")
        }

        val dayChecks = ENGLISH_DAYS + TAMIL_DAYS
        for ((word, off) in dayChecks) {
            val pat = Pattern.compile("(?i)(?<=^|\\s)${Pattern.quote(word)}(?=\\s|\$|[.,!?;])")
            if (pat.matcher(qLower).find()) {
                dayOffset = off
                matchedDayLabel = word
                break
            }
        }

        var hourRange: Pair<Int, Int>? = null
        var matchedTodLabel: String? = null
        for ((word, range) in TIME_OF_DAY_WINDOW) {
            val pat = Pattern.compile("(?i)(?<=^|\\s)${Pattern.quote(word)}(?=\\s|\$|[.,!?;])")
            if (pat.matcher(qLower).find()) {
                hourRange = range
                matchedTodLabel = word
                break
            }
        }

        if (dayOffset == null && hourRange == null) return null

        val startMs: Long
        val endMs: Long
        if (dayOffset != null) {
            val d = refDate.plusDays(dayOffset.toLong())
            startMs = d.atStartOfDay(zone).toInstant().toEpochMilli()
            endMs = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        } else {
            // Time-of-day only -> scan recent 14 days within matching hours
            startMs = refDate.minusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
            endMs = referenceEpochMillis
        }

        val label = listOfNotNull(matchedDayLabel, matchedTodLabel).joinToString(" • ")
        return TemporalWindow(
            startMillis = startMs,
            endMillis = endMs,
            label = label,
            preferredHourStart = hourRange?.first,
            preferredHourEnd = hourRange?.second
        )
    }
}
