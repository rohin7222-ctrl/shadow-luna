package com.personal.devicememory.collector.audio

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioAnalysisResult(
    val acousticScene: String,       // "Speech-Dominant", "Music / Harmonic", "Ambient / Noise", "Quiet / Silence"
    val speechRatio: Double,         // 0.0..1.0 Voice Activity Detection fraction
    val spectralCentroidHz: Int,     // brightness of spectrum in Hz
    val spectralFlatness: Double,    // 0.0 (pure tonal/harmonic) .. 1.0 (white noise)
    val spectralFlux: Double,        // temporal modulation / beat / syllable onset energy
    val rmsDb: Int,                  // loudness dBFS
    val mfccSignature: List<Double>, // 13-dim compact MFCC mean fingerprint (13 coeffs)
    val melPatchLatent: List<Double>,// 16-dim AudioMAE coarse Mel-patch energy latent
    val acousticCode: String         // BEATs-style discrete codebook token (e.g. "ac_042")
)

/**
 * Pure-Kotlin 2026 Self-Supervised Audio Spectral & Acoustic Tokenizer Engine.
 *
 * Implements:
 *  1) Hann-windowed Radix-2 Cooley-Tukey FFT over 512-sample short-time frames
 *  2) Psychoacoustic 26-band Mel Filterbank -> 16D AudioMAE spectral patch embedding
 *  3) 13-dim MFCC cepstral vector via DCT-II
 *  4) Spectral Flux, Centroid, Flatness, ZCR & Adaptive Energy Voice Activity Detection (VAD)
 *  5) Deterministic BEATs-inspired Acoustic Codebook Quantizer (`ac_000..ac_127`)
 */
object SpectralAudioEngine {

    const val TARGET_SAMPLE_RATE = 16000
    const val FFT_SIZE = 512
    const val HOP_SIZE = 256
    private const val MEL_BANDS = 26
    private const val MFCC_COUNT = 13

    private val HANN_WINDOW = DoubleArray(FFT_SIZE) { n ->
        0.5 * (1.0 - cos(2.0 * PI * n / (FFT_SIZE - 1)))
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    private fun Double.pow(p: Double): Double = kotlin.math.exp(p * ln(this))

    /** In-place iterative Radix-2 Cooley-Tukey FFT for real+imag arrays of matching power-of-2 length. */
    fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n == imag.size) { "real (${real.size}) and imag (${imag.size}) arrays must match" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length ($n) must be a positive power of 2" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenRe = cos(angle)
            val wLenIm = sin(angle)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * wRe - imag[i + k + len / 2] * wIm
                    val vIm = real[i + k + len / 2] * wIm + imag[i + k + len / 2] * wRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun buildMelFilterbank(sampleRate: Int): Array<DoubleArray> {
        val bins = FFT_SIZE / 2 + 1
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel((sampleRate / 2.0).coerceAtMost(8000.0))
        val melPoints = DoubleArray(MEL_BANDS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (MEL_BANDS + 1)
        }
        val binIndices = IntArray(MEL_BANDS + 2) { i ->
            (((FFT_SIZE + 1) * melToHz(melPoints[i])) / sampleRate).toInt().coerceIn(0, bins - 1)
        }

        return Array(MEL_BANDS) { m ->
            val filter = DoubleArray(bins)
            val left = binIndices[m]
            val center = max(left + 1, binIndices[m + 1])
            val right = max(center + 1, binIndices[m + 2])
            for (k in left until center) {
                filter[k] = (k - left).toDouble() / (center - left).toDouble()
            }
            for (k in center..min(right, bins - 1)) {
                filter[k] = (right - k).toDouble() / (right - center).coerceAtLeast(1).toDouble()
            }
            filter
        }
    }

    fun analyzePcm(samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE): AudioAnalysisResult {
        val safeRate = sampleRate.coerceAtLeast(1000)
        if (samples.size < FFT_SIZE) {
            return AudioAnalysisResult(
                acousticScene = "Quiet / Silence",
                speechRatio = 0.0,
                spectralCentroidHz = 0,
                spectralFlatness = 0.5,
                spectralFlux = 0.0,
                rmsDb = -60,
                mfccSignature = List(MFCC_COUNT) { 0.0 },
                melPatchLatent = List(16) { 0.0 },
                acousticCode = "ac_000"
            )
        }

        val melFilters = buildMelFilterbank(safeRate)
        val specBins = FFT_SIZE / 2 + 1
        val freqPerBin = safeRate.toDouble() / FFT_SIZE

        val mfccAccum = DoubleArray(MFCC_COUNT)
        val mel16Accum = DoubleArray(16)
        var centroidSum = 0.0
        var flatnessSum = 0.0
        var fluxSum = 0.0
        var fluxFrameCount = 0
        var rmsSum = 0.0
        var zcrSum = 0.0
        var voiceActiveFrames = 0
        var frameCount = 0

        var prevMag = DoubleArray(specBins)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        var start = 0
        while (start + FFT_SIZE <= samples.size) {
            var frameSq = 0.0
            var zc = 0
            var prevSample = 0.0
            for (i in 0 until FFT_SIZE) {
                val raw = samples[start + i].toDouble()
                val x = if (raw.isFinite()) raw.coerceIn(-1.0, 1.0) else 0.0
                frameSq += x * x
                if (i > 0 && ((prevSample >= 0.0) != (x >= 0.0))) {
                    zc++
                }
                prevSample = x
                real[i] = x * HANN_WINDOW[i]
                imag[i] = 0.0
            }
            val frameRms = sqrt(frameSq / FFT_SIZE)
            val zcr = zc.toDouble() / FFT_SIZE
            rmsSum += frameRms
            zcrSum += zcr

            fftInPlace(real, imag)

            val mag = DoubleArray(specBins)
            var magSum = 1e-12
            var weightedFreqSum = 0.0
            var logMagSum = 0.0
            var frameFlux = 0.0
            var voiceBandEnergy = 0.0
            var totalBandEnergy = 1e-12

            for (k in 0 until specBins) {
                val m = sqrt(real[k] * real[k] + imag[k] * imag[k])
                mag[k] = m
                val pwr = m * m
                val hz = k * freqPerBin
                magSum += m
                weightedFreqSum += hz * m
                logMagSum += ln(max(m, 1e-12))
                totalBandEnergy += pwr
                if (hz in 300.0..3400.0) voiceBandEnergy += pwr

                if (frameCount > 0) {
                    val diff = m - prevMag[k]
                    if (diff > 0.0) frameFlux += diff
                }
            }
            prevMag = mag

            val centroid = weightedFreqSum / magSum
            val arithMean = magSum / specBins
            val geomMean = exp(logMagSum / specBins)
            val flatness = (geomMean / max(arithMean, 1e-12)).coerceIn(0.0, 1.0)

            centroidSum += centroid
            flatnessSum += flatness
            if (frameCount > 0) {
                fluxSum += (frameFlux / max(magSum, 1e-6)).coerceIn(0.0, 4.0)
                fluxFrameCount++
            }

            // 2026 VAD Heuristic: energy above noise floor + concentrated in 300-3400Hz speech band + moderate flatness & ZCR
            val voiceBandShare = voiceBandEnergy / totalBandEnergy
            if (frameRms > 0.015 && voiceBandShare > 0.42 && zcr in 0.02..0.28 && flatness < 0.55) {
                voiceActiveFrames++
            }

            // Mel filterbank energies -> AudioMAE 16D patch + MFCC DCT-II
            val logMels = DoubleArray(MEL_BANDS)
            for (m in 0 until MEL_BANDS) {
                var e = 1e-10
                val filter = melFilters[m]
                for (k in 0 until specBins) {
                    e += mag[k] * mag[k] * filter[k]
                }
                logMels[m] = ln(e)
            }
            for (b in 0 until 16) {
                val srcIdx = (b * MEL_BANDS) / 16
                mel16Accum[b] += logMels[srcIdx]
            }
            val dctScale = sqrt(2.0 / MEL_BANDS)
            for (c in 0 until MFCC_COUNT) {
                var sum = 0.0
                for (m in 0 until MEL_BANDS) {
                    sum += logMels[m] * cos(PI * c * (m + 0.5) / MEL_BANDS)
                }
                mfccAccum[c] += sum * dctScale
            }

            frameCount++
            start += HOP_SIZE
        }

        val denom = frameCount.coerceAtLeast(1).toDouble()
        val avgRms = rmsSum / denom
        val rmsDb = (20.0 * log10(max(avgRms, 1e-5))).toInt().coerceIn(-70, 0)
        val avgCentroid = (centroidSum / denom).toInt()
        val avgFlatness = (flatnessSum / denom).coerceIn(0.0, 1.0)
        val avgFlux = if (fluxFrameCount > 0) fluxSum / fluxFrameCount else 0.0
        val speechRatio = (voiceActiveFrames.toDouble() / denom).coerceIn(0.0, 1.0)
        val avgZcr = zcrSum / denom

        val mfccMean = mfccAccum.map { (it / denom) }
        val mel16Raw = DoubleArray(16) { mel16Accum[it] / denom }
        val melNormSq = mel16Raw.sumOf { it * it }
        val melNorm = sqrt(max(melNormSq, 1e-9))
        val mel16Norm = mel16Raw.map { it / melNorm }

        // Classify acoustic scene from physical spectral features
        val scene = when {
            avgRms < 0.008 || rmsDb <= -46 -> "Quiet / Silence"
            speechRatio >= 0.30 && avgFlux >= 0.08 && avgCentroid in 250..3600 -> "Speech-Dominant"
            avgFlatness < 0.28 && avgFlux >= 0.04 -> "Music / Harmonic"
            else -> if (speechRatio >= 0.16) "Speech & Ambient Mix" else "Ambient / Noise"
        }

        // BEATs-inspired binary hash codebook quantization over 16D AudioMAE spectral patch + centroid bit
        var codeInt = 0
        val meanPatch = mel16Norm.average()
        for (i in 0 until 6) {
            if (mel16Norm[i * 2] > meanPatch) codeInt = codeInt or (1 shl i)
        }
        if (avgZcr > 0.10) codeInt = codeInt or (1 shl 6)
        val codeStr = String.format(Locale.US, "ac_%03d", codeInt and 0x7F)

        return AudioAnalysisResult(
            acousticScene = scene,
            speechRatio = speechRatio,
            spectralCentroidHz = avgCentroid,
            spectralFlatness = avgFlatness,
            spectralFlux = avgFlux,
            rmsDb = rmsDb,
            mfccSignature = mfccMean,
            melPatchLatent = mel16Norm,
            acousticCode = codeStr
        )
    }
}
