package com.personal.devicememory.pipeline.brain

import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeStitchingEngineTest {

    @Test
    fun groupsNearbyRecordsAndSplitsAcrossGapOrGpsJump() {
        val base = 1_760_000_000_000L
        val records = listOf(
            CollectedRecordEntity("1", "PHOTO", base, "Photo: Beach sunset", "{\"latitude\":13.05,\"longitude\":80.28}", 0, base),
            CollectedRecordEntity("2", "VIDEO", base + 5 * 60_000L, "Video: Beach waves [Outdoor]", "{}", 0, base),
            CollectedRecordEntity("3", "NOTIFICATION", base + 12 * 60_000L, "WhatsApp Arun: coming now", "{}", 0, base),
            // 45 mins later -> distinct episode!
            CollectedRecordEntity("4", "PHOTO", base + 60 * 60_000L, "Screenshot: Swiggy dinner order", "{}", 0, base),
            CollectedRecordEntity("5", "APP_USAGE", base + 65 * 60_000L, "Used Swiggy for 5m", "{}", 0, base)
        )

        val episodes = EpisodeStitchingEngine.stitchEpisodes(
            records = records,
            conceptExtractor = LunaMemorySimulator::extractSemanticConcepts,
            nowMs = base
        )
        assertEquals(2, episodes.size)
        assertEquals(3, episodes[0].recordCount)
        assertEquals(2, episodes[1].recordCount)
        assertTrue(episodes[0].keyConcepts.contains("beach") || episodes[0].narrativeSummary.contains("Photos"))
        assertTrue(episodes[1].keyConcepts.contains("swiggy"))
    }
}
