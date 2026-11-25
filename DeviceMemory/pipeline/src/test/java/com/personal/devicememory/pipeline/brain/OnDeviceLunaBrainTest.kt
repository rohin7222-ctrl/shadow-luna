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
}
