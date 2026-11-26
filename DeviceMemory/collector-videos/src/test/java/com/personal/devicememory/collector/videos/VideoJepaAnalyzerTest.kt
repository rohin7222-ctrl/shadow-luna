package com.personal.devicememory.collector.videos

import com.personal.devicememory.collector.audio.AudioAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoJepaAnalyzerTest {

    @Test
    fun `detects static dark low-light scene accurately`() {
        val flatPatch = FloatArray(16) { 0.12f }
        val frames = listOf(
            FrameSample(100L, flatPatch, 0.12, 0.11, 0.13),
            FrameSample(600L, flatPatch, 0.12, 0.11, 0.13),
            FrameSample(1100L, flatPatch, 0.12, 0.11, 0.13)
        )
        val res = VideoJepaAnalyzer.analyzeFrames(frames, codec = "HEVC", estimatedFps = 30.0, hasAudioTrack = true)
        assertNotNull(res)
        assertEquals("Static Scene", res!!.motionScene)
        assertEquals("Dark / Low-Light", res.lightingScene)
        assertEquals(0, res.sceneCutCount)
        assertEquals("HEVC", res.codec)
        assertTrue(res.visualCode.matches(Regex("vis_\\d{3}")))
        assertEquals(16, res.visualLatent16.size)
    }

    @Test
    fun `detects scene cuts and higher V-JEPA novelty on sudden discontinuous events`() {
        // Linear smooth pan across 3 frames -> low V-JEPA surprise
        val panFrames = listOf(
            FrameSample(0L, FloatArray(16) { 0.20f }, 0.2, 0.2, 0.2),
            FrameSample(500L, FloatArray(16) { 0.24f }, 0.24, 0.24, 0.24),
            FrameSample(1000L, FloatArray(16) { 0.28f }, 0.28, 0.28, 0.28)
        )
        val panRes = VideoJepaAnalyzer.analyzeFrames(panFrames, codec = "AVC")!!

        // Sudden unpredictable jump / scene cut at frame 3 -> high V-JEPA novelty & cut detected
        val cutFrames = listOf(
            FrameSample(0L, FloatArray(16) { 0.20f }, 0.2, 0.2, 0.2),
            FrameSample(500L, FloatArray(16) { 0.22f }, 0.22, 0.22, 0.22),
            FrameSample(1000L, FloatArray(16) { 0.90f }, 0.9, 0.85, 0.8),
            FrameSample(1500L, FloatArray(16) { 0.15f }, 0.15, 0.15, 0.15)
        )
        val cutRes = VideoJepaAnalyzer.analyzeFrames(cutFrames, codec = "AV1")!!

        assertEquals("Fast Motion", cutRes.motionScene)
        assertTrue("Expected >= 2 scene cuts, got ${cutRes.sceneCutCount}", cutRes.sceneCutCount >= 2)
        assertTrue(
            "Expected cut V-JEPA novelty (${cutRes.vJepaNovelty}) > smooth pan novelty (${panRes.vJepaNovelty})",
            cutRes.vJepaNovelty > panRes.vJepaNovelty + 0.15
        )
    }

    @Test
    fun `VideoMapper fuses visual and audio analysis into unified record`() {
        val visual = VideoAnalysisResult(
            motionScene = "Moderate Motion",
            motionScore = 0.09,
            vJepaNovelty = 0.11,
            lightingScene = "Bright / Outdoor",
            avgLuminance = 0.71,
            sceneCutCount = 1,
            colorTone = "Green / Nature",
            codec = "HEVC",
            estimatedFps = 60.0,
            hasAudioTrack = true,
            visualLatent16 = List(16) { 0.5 },
            visualCode = "vis_098"
        )
        val audio = AudioAnalysisResult(
            acousticScene = "Speech-Dominant",
            speechRatio = 0.68,
            spectralCentroidHz = 980,
            spectralFlatness = 0.15,
            spectralFlux = 0.24,
            rmsDb = -16,
            mfccSignature = List(13) { 0.2 },
            melPatchLatent = List(16) { 0.3 },
            acousticCode = "ac_019"
        )
        val rec = VideoMapper.map(
            VideoItem(
                mediaId = 99L,
                displayName = "park_walk.mp4",
                bucketName = "Camera",
                mimeType = "video/mp4",
                durationMs = 12_000L,
                sizeBytes = 4_200_000L,
                width = 1920,
                height = 1080,
                dateTaken = 1_760_000_000_000L,
                dateAdded = 1_760_000_000_000L,
                visual = visual,
                audio = audio
            )
        )
        val text = rec.textContent ?: ""
        assertTrue(text.contains("Moderate Motion"))
        assertTrue(text.contains("Bright / Outdoor"))
        assertTrue(text.contains("Speech-Dominant VAD 68%"))
        assertTrue(text.contains("vis_098"))
        assertTrue(text.contains("ac_019"))
        assertTrue(rec.metadataJson?.contains("av_analysis") == true)
    }
}
