package com.personal.devicememory.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeUtilTest {

    @Test
    fun testTamilGraphemeSplitting() {
        // "தமிழ்" consists of:
        // த (ta)
        // மி (mi = ma + vowel sign i) -> 1 cluster
        // ழ் (zha = zha + virama) -> 1 cluster
        // Total clusters = 3
        val tamilWord = "தமிழ்"
        val clusters = GraphemeUtil.toGraphemeClusters(tamilWord)
        
        assertEquals(3, clusters.size)
        assertEquals("த", clusters[0])
        assertEquals("மி", clusters[1])
        assertEquals("ழ்", clusters[2])
        assertEquals(3, GraphemeUtil.graphemeLength(tamilWord))
    }

    @Test
    fun testMixedTamilAndEnglishText() {
        // "வணக்கம் Hello 🌟"
        // வ (1) + ண (1) + க் (1) + க (1) + ம் (1) = 5
        // ' ' (1)
        // H(1) e(1) l(1) l(1) o(1) = 5
        // ' ' (1)
        // 🌟 (1)
        // Total = 5 + 1 + 5 + 1 + 1 = 13 grapheme clusters
        val text = "வணக்கம் Hello 🌟"
        val clusters = GraphemeUtil.toGraphemeClusters(text)
        
        assertEquals(13, clusters.size)
        assertEquals("வ", clusters[0])
        assertEquals("க்", clusters[2])
        assertEquals("🌟", clusters.last())
    }

    @Test
    fun testTruncateGraphemes() {
        val tamilWord = "தமிழ்நாடு" // த (1) + மி (1) + ழ் (1) + நா (1) + டு (1) = 5 clusters
        val truncated = GraphemeUtil.truncate(tamilWord, 3, ellipsis = "...")
        
        assertEquals("தமிசா...".length, truncated.length) // 3 clusters + 3 dots
        assertTrue(truncated.startsWith("தமி"))
        assertEquals("தமிழ்...", truncated)
    }

    @Test
    fun testEmptyString() {
        assertTrue(GraphemeUtil.toGraphemeClusters("").isEmpty())
        assertEquals(0, GraphemeUtil.graphemeLength(""))
        assertEquals("", GraphemeUtil.truncate("", 5))
    }
}
