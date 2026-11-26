package com.personal.devicememory.core.common.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilingualSemanticBridgeTest {

    @Test
    fun expandsTamilToEnglishAndEnglishToTamilCleanly() {
        val fromTamil = BilingualSemanticBridge.expandBilingualSynonyms(listOf("சாப்பாடு", "பணம்"))
        assertTrue(fromTamil.contains("food"))
        assertTrue(fromTamil.contains("swiggy"))
        assertTrue(fromTamil.contains("payment"))
        assertTrue(fromTamil.contains("gpay"))
        assertFalse(fromTamil.contains("சாப்பாடு"))

        val fromEnglish = BilingualSemanticBridge.expandBilingualSynonyms(listOf("hospital"))
        assertTrue(fromEnglish.contains("மருத்துவமனை"))
        assertTrue(fromEnglish.contains("doctor"))
    }
}
