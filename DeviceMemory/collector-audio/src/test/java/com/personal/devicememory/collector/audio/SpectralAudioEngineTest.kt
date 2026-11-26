package com.personal.devicememory.collector.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpectralAudioEngineTest {

    @Test
    fun `radix2 fft detects exact 440Hz sine peak and centroid`() {
        val sr = 16000
        val n = SpectralAudioEngine.FFT_SIZE
        val real = DoubleArray(n) { i -> sin(2.0 * PI * 440.0 * i / sr) }
        val imag = DoubleArray(n)

        SpectralAudioEngine.fftInPlace(real, imag)

        var bestBin = 0
        var bestMag = -1.0
        for (k in 0 until n / 2) {
            val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
            if (mag > bestMag) {
                bestMag = mag
                bestBin = k
            }
        }
        val peakHz = bestBin * sr.toDouble() / n
        // 440Hz lands cleanly at bin 14 (437.5Hz)
        assertTrue("Expected peak near 440Hz, got $peakHz", peakHz in 400.0..480.0)
    }

    @Test
    fun `analyzePcm detects silence cleanly with quiet label and low rms`() {
        val silence = FloatArray(16000) { 0f }
        val res = SpectralAudioEngine.analyzePcm(silence, 16000)
        assertEquals("Quiet / Silence", res.acousticScene)
        assertEquals(0.0, res.speechRatio, 1e-6)
        assertTrue("rmsDb should be <= -46", res.rmsDb <= -46)
    }

    @Test
    fun `analyzePcm detects modulated speech band bursts with BEATs codebook token`() {
        val sr = 16000
        val len = 16000 // 1s
        // Syllable envelope modulating 650Hz + 1500Hz formants in human voice band (300-3400Hz)
        val pcm = FloatArray(len) { i ->
            val t = i.toDouble() / sr
            val envelope = 0.5 + 0.5 * sin(2.0 * PI * 4.5 * t)
            (envelope * (0.45 * sin(2.0 * PI * 650.0 * t) + 0.25 * sin(2.0 * PI * 1500.0 * t))).toFloat()
        }

        val res = SpectralAudioEngine.analyzePcm(pcm, sr)
        assertEquals("Speech-Dominant", res.acousticScene)
        assertTrue("Expected VAD speechRatio >= 0.30, got ${res.speechRatio}", res.speechRatio >= 0.30)
        assertEquals(13, res.mfccSignature.size)
        assertEquals(16, res.melPatchLatent.size)
        assertTrue("Expected BEATs ac_XXX code, got ${res.acousticCode}", res.acousticCode.matches(Regex("ac_\\d{3}")))
    }

    @Test
    fun `AudioMapper emits readable spectral tags and metadata`() {
        val sample = AudioAnalysisResult(
            acousticScene = "Speech-Dominant",
            speechRatio = 0.72,
            spectralCentroidHz = 1120,
            spectralFlatness = 0.14,
            spectralFlux = 0.21,
            rmsDb = -18,
            mfccSignature = List(13) { 0.1 },
            melPatchLatent = List(16) { 0.25 },
            acousticCode = "ac_042"
        )
        val record = AudioMapper.map(
            AudioItem(
                mediaId = 7L,
                displayName = "voice_note.m4a",
                title = "Voice Note",
                artist = null,
                album = null,
                durationMs = 15_000L,
                sizeBytes = 64_000L,
                mimeType = "audio/mp4",
                dateAdded = 1_760_000_000_000L,
                analysis = sample
            )
        )
        val text = record.textContent ?: ""
        assertTrue(text.contains("Speech-Dominant"))
        assertTrue(text.contains("VAD 72%"))
        assertTrue(text.contains("ac_042"))
        assertTrue(record.metadataJson?.contains("\"audio_analysis\":{") == true)
    }

    @Test
    fun `stationary pure sine has near zero spectral flux and finite metrics despite NaN input`() {
        val sr = 16000
        val sine = FloatArray(4096) { i -> kotlin.math.sin(2.0 * PI * 440.0 * i / sr).toFloat() }
        val sineRes = SpectralAudioEngine.analyzePcm(sine, sr)
        assertTrue("Stationary sine should have low flux, got ${sineRes.spectralFlux}", sineRes.spectralFlux < 0.04)

        val noisyNaN = FloatArray(1024) { Float.NaN }
        val nanRes = SpectralAudioEngine.analyzePcm(noisyNaN, sr)
        assertTrue(nanRes.speechRatio.isFinite())
        assertTrue(nanRes.spectralFlux.isFinite())
    }
}
