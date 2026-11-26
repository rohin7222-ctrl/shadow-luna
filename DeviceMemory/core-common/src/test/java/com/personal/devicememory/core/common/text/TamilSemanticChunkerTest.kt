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

    @Test
    fun testOversizedSentencePreservesChunkOrderAndGuardsInvalidMax() {
        val text = "முதல் வாக்கியம். இது மிகவும் நீளமான இரண்டாவது வாக்கியம் தமிழ் எழுத்துக்களுடன்."
        val chunks = TamilSemanticChunker.chunk(text, maxGraphemes = 15, overlapGraphemes = 3)
        assertTrue(chunks.size >= 3)
        assertTrue("First chunk must start withதல் sentence, got: ${chunks[0].text}", chunks[0].text.startsWith("முதல்"))
        for (i in chunks.indices) {
            assertEquals(i, chunks[i].index)
        }
        // Invalid maxGraphemes <= 0 never crashes
        val clamped = TamilSemanticChunker.chunk("Hello world", maxGraphemes = 0, overlapGraphemes = 5)
        assertTrue(clamped.isNotEmpty())
        assertEquals("", GraphemeUtil.truncate("வணக்கம்", 0))
    }
}
