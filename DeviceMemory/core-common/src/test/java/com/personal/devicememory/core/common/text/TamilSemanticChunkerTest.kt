package com.personal.devicememory.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamilSemanticChunkerTest {

    @Test
    fun testSentenceSplittingWithTamilAndEnglish() {
        val text = "வணக்கம். நான் இப்போது சென்னை செல்கிறேன்! Are you free tomorrow?"
        val sentences = TamilSemanticChunker.splitSentences(text)

        assertTrue(sentences.size >= 3)
        assertEquals("வணக்கம்.", sentences[0])
        assertEquals("நான் இப்போது சென்னை செல்கிறேன்!", sentences[1])
        assertEquals("Are you free tomorrow?", sentences[2])
    }

    @Test
    fun testSemanticChunkingPreservesTamilClusters() {
        val longTamilText = "தமிழ்நாட்டின் தலைநகரம் சென்னை ஆகும். இது மிக அழகான கடற்கரை நகரம். " +
                "இங்கு பல தொழிற்சாலைகளும் மென்பொருள் நிறுவனங்களும் உள்ளன."

        val chunks = TamilSemanticChunker.chunk(longTamilText, maxGraphemes = 25, overlapGraphemes = 5)

        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(chunk.graphemeCount <= 25)
            assertTrue(chunk.isTamil)
        }
    }

    @Test
    fun testContainsTamilDetection() {
        assertTrue(TamilSemanticChunker.containsTamil("வணக்கம்"))
        assertTrue(TamilSemanticChunker.containsTamil("Hello உலகம்"))
        org.junit.Assert.assertFalse(TamilSemanticChunker.containsTamil("Hello World 123!"))
    }
}
