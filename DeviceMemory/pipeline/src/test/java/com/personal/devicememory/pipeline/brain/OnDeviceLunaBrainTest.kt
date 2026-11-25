package com.personal.devicememory.pipeline.brain

import com.personal.devicememory.core.database.entity.NeuralVectorEntity
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

    @Test
    fun titansSurpriseDropsForExpectedPairsAndTttAdaptsContext() {
        val cam = FrontierNeuralTrainer.initConceptVector("camera")
        val heic = FrontierNeuralTrainer.initConceptVector("heic")
        val surpriseRandom = FrontierNeuralTrainer.computeTitansSurprise(cam, heic)
        // High cosine alignment reduces surprise
        val surpriseAligned = FrontierNeuralTrainer.computeTitansSurprise(cam, cam)
        assertTrue(surpriseAligned < surpriseRandom)

        // TTT adapts query slightly toward session context vector
        val youtube = FrontierNeuralTrainer.initConceptVector("youtube")
        val gallery = FrontierNeuralTrainer.l2Normalize(FloatArray(FrontierNeuralTrainer.EMBEDDING_DIM) { i ->
            cam[i] * 0.7f + youtube[i] * 0.5f
        })
        val pool = listOf(
            NeuralVectorEntity("camera", FrontierNeuralTrainer.encodeVector(cam), ""),
            NeuralVectorEntity("youtube", FrontierNeuralTrainer.encodeVector(youtube), ""),
            NeuralVectorEntity("gallery", FrontierNeuralTrainer.encodeVector(gallery), "")
        )
        val results = FrontierNeuralTrainer.adaptAndQueryVectors(
            queryConcepts = listOf("camera"),
            recentSessionConcepts = listOf("youtube"),
            allVectors = pool,
            topK = 3
        )
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.first == "gallery" || it.first == "youtube" })
    }

    @Test
    fun futurePoincareJepaForwardForwardAndLiquidMathWork() {
        val p1 = FutureNeuralArchitectures.initPoincareCoords("whatsapp")
        val p2 = FutureNeuralArchitectures.initPoincareCoords("camera")
        assertTrue(FutureNeuralArchitectures.normSquared(p1) < 1.0)
        assertEquals(0.0, FutureNeuralArchitectures.poincareDistance(p1, p1), 1e-4)
        assertTrue(FutureNeuralArchitectures.poincareDistance(p1, p2) > 0.0)

        val ffWeights = FloatArray(FutureNeuralArchitectures.FUTURE_DIM) { 0.7f }
        val goodness = FutureNeuralArchitectures.forwardForwardGoodness(p1, p2, ffWeights)
        assertTrue(goodness in 0.0..1.0)

        val liqAfter1h = FutureNeuralArchitectures.liquidTimeUpdate(0.8, 0.2, 3_600_000L, goodness)
        val liqAfter48h = FutureNeuralArchitectures.liquidTimeUpdate(0.8, 0.2, 48 * 3_600_000L, goodness)
        assertTrue(liqAfter1h > liqAfter48h)

        val idMatrix = FloatArray(FutureNeuralArchitectures.FUTURE_DIM * FutureNeuralArchitectures.FUTURE_DIM) { idx ->
            if (idx / FutureNeuralArchitectures.FUTURE_DIM == idx % FutureNeuralArchitectures.FUTURE_DIM) 1.0f else 0.0f
        }
        val jepaOut = FutureNeuralArchitectures.jepaPredict(idMatrix, FutureNeuralArchitectures.l2Normalize(p1))
        assertTrue(kotlin.math.abs(FutureNeuralArchitectures.normSquared(jepaOut) - 1.0) < 1e-3)
    }

    @Test
    fun escapeLikeNeutralizesSqlWildcards() {
        assertEquals("100\\%\\_done\\\\ok", OnDeviceLunaBrain.escapeLike("100%_done\\ok"))
        assertTrue(OnDeviceLunaBrain.tokenize("%%% ___ ???").isEmpty())
    }
}
