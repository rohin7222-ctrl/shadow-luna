package com.personal.devicememory.collector.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AcousticSpeakerClustererTest {

    @Test
    fun clustersRecurringVoicePrintsSeparatelyAndUpdatesCentroid() {
        val centroids = mutableListOf<AcousticSpeakerClusterer.Centroid>()
        val voiceA1 = AcousticSpeakerClusterer.normalizeVoicePrint(
            listOf(-12.0, 2.5, -1.2, 0.8, 0.3, -0.4, 0.2, 0.1, 0.0, 0.1, -0.1, 0.05, 0.02)
        )!!
        val voiceB = AcousticSpeakerClusterer.normalizeVoicePrint(
            listOf(-10.0, -2.1, 2.4, -1.5, 0.9, 1.1, -0.6, 0.4, -0.2, 0.3, 0.2, -0.4, 0.1)
        )!!
        val voiceA2 = AcousticSpeakerClusterer.normalizeVoicePrint(
            listOf(-8.0, 2.4, -1.1, 0.85, 0.28, -0.35, 0.22, 0.09, 0.02, 0.08, -0.11, 0.04, 0.01)
        )!!

        val c1 = AcousticSpeakerClusterer.assignOrCreateCluster(voiceA1, centroids)
        val c2 = AcousticSpeakerClusterer.assignOrCreateCluster(voiceB, centroids)
        val c3 = AcousticSpeakerClusterer.assignOrCreateCluster(voiceA2, centroids)

        assertNotNull(c1)
        assertEquals("spk_01", c1)
        assertEquals("spk_02", c2)
        assertNotEquals(c1, c2)
        assertEquals("spk_01", c3)
        assertEquals(2, centroids.first { it.id == "spk_01" }.hits)
    }
}
