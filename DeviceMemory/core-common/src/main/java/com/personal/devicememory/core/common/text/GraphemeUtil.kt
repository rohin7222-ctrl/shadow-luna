package com.personal.devicememory.core.common.text

import java.text.BreakIterator
import java.util.Locale

/**
 * Tamil-safe grapheme cluster utilities.
 * Uses [BreakIterator] with Tamil locale so that combined characters
 * (e.g. க + ொ → கொ) are treated as a single grapheme cluster.
 */
object GraphemeUtil {

    private val TAMIL_LOCALE = Locale("ta", "IN")

    /**
     * Splits [text] into grapheme clusters using the Tamil locale.
     * Works correctly for mixed Tamil + English + emoji text.
     */
    fun toGraphemeClusters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val clusters = mutableListOf<String>()
        val bi = BreakIterator.getCharacterInstance(TAMIL_LOCALE)
        bi.setText(text)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = bi.next()
        }
        return clusters
    }

    /**
     * Returns the grapheme cluster count (not code point or char count).
     */
    fun graphemeLength(text: String): Int = toGraphemeClusters(text).size

    /**
     * Truncates [text] to at most [maxGraphemes] grapheme clusters,
     * appending [ellipsis] if truncated.
     */
    fun truncate(text: String, maxGraphemes: Int, ellipsis: String = "…"): String {
        val clusters = toGraphemeClusters(text)
        return if (clusters.size <= maxGraphemes) {
            text
        } else {
            clusters.take(maxGraphemes).joinToString("") + ellipsis
        }
    }
}
