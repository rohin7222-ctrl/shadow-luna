package com.personal.devicememory.pipeline.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceLunaBrainTest {

    @Test
    fun tokenizePreservesFullTamilAndEnglishWords() {
        val tokens = OnDeviceLunaBrain.tokenize("தமிழ் வணக்கம் Hello_World 123!")
        assertTrue(tokens.contains("தமிழ்"))
        assertTrue(tokens.contains("வணக்கம்"))
        assertTrue(tokens.contains("hello_world"))
        assertTrue(tokens.contains("123"))
        assertEquals(4, tokens.size)
    }

    @Test
    fun semanticConceptExtractorStripsTimestampsAndSplitsPackages() {
        val mediaConcepts = LunaMemorySimulator.extractSemanticConcepts(
            "PHOTO",
            "Screenshot: Screenshot_20250811_150835_YouTube.jpg"
        )
        assertTrue(mediaConcepts.contains("youtube"))
        assertTrue(!mediaConcepts.any { it.contains("20250811") })

        val appConcepts = LunaMemorySimulator.extractSemanticConcepts(
            "APP_USAGE",
            "com.sec.android.app.camera"
        )
        assertTrue(appConcepts.contains("camera"))
    }

    @Test
    fun typoToleranceAndIntentClassificationWorkCleanly() {
        assertEquals(1, OnDeviceLunaBrain.levenshteinDistance("camra", "camera"))
        assertEquals(1, OnDeviceLunaBrain.levenshteinDistance("watsapp", "whatsapp"))
        assertEquals(QueryIntent.SUMMARY_PROFILE, OnDeviceLunaBrain.classifyIntent("what did you learn summary"))
        assertEquals(QueryIntent.TEMPORAL, OnDeviceLunaBrain.classifyIntent("when do i use camera"))
    }
}
