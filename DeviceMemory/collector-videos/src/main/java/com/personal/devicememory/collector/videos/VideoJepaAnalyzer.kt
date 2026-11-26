package com.personal.devicememory.collector.videos

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FrameSample(
    val timestampMs: Long,
    val patch4x4Luma: FloatArray, // 16 normalized spatial patch luminances [0..1]
    val avgR: Double,
    val avgG: Double,
    val avgB: Double
)

data class VideoAnalysisResult(
    val motionScene: String,         // "Fast Motion", "Moderate Motion", "Static Scene"
    val motionScore: Double,         // normalized [0..1] temporal L1 spatial delta
    val vJepaNovelty: Double,        // V-JEPA spatio-temporal latent prediction error [0..1]
    val lightingScene: String,       // "Bright / Outdoor", "Balanced / Indoor", "Dark / Low-Light"
    val avgLuminance: Double,        // [0..1]
    val sceneCutCount: Int,          // discontinuous latent/luminance jumps across sampled frames
    val colorTone: String,           // "Warm", "Cool", "Green / Nature", "Neutral"
    val codec: String?,              // "AV1", "HEVC", "AVC", "VP9", etc.
    val estimatedFps: Double?,
    val hasAudioTrack: Boolean,
    val visualLatent16: List<Double>,// 16D spatial-temporal video embedding
    val visualCode: String           // discrete codebook token "vis_000..vis_127"
)

/**
 * Pure-Kotlin 2026 Video V-JEPA (Joint-Embedding Predictive Architecture) & Motion Scene Engine.
 *
 * Analyzes temporally sparse sampled 4x4 luminance patch latents without loading full video bitstreams:
 *  - Predicts future spatial patch latent z_hat_{t+1} = z_t + 0.65 * (z_t - z_{t-1})
 *  - Computes predictive surprise / novelty (vJepaNovelty) separately from uniform camera pan
 *  - Detects scene cuts, motion intensity, ambient lighting, dominant color tone, and discrete vis_XXX token
 */
object VideoJepaAnalyzer {

    private const val PATCH_DIM = 16

    fun analyzeFrames(
        samples: List<FrameSample>,
        codec: String? = null,
        estimatedFps: Double? = null,
        hasAudioTrack: Boolean = false
    ): VideoAnalysisResult? {
        if (samples.isEmpty()) return null

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val meanPatch = DoubleArray(PATCH_DIM)
        val frameLums = DoubleArray(samples.size)

        for (i in samples.indices) {
            val s = samples[i]
            sumR += s.avgR
            sumG += s.avgG
            sumB += s.avgB
            frameLums[i] = (0.299 * s.avgR + 0.587 * s.avgG + 0.114 * s.avgB).coerceIn(0.0, 1.0)
            val len = min(PATCH_DIM, s.patch4x4Luma.size)
            for (d in 0 until len) {
                meanPatch[d] += s.patch4x4Luma[d].toDouble().coerceIn(0.0, 1.0)
            }
        }

        val count = samples.size.toDouble()
        val meanR = (sumR / count).coerceIn(0.0, 1.0)
        val meanG = (sumG / count).coerceIn(0.0, 1.0)
        val meanB = (sumB / count).coerceIn(0.0, 1.0)
        val avgLuminance = (0.299 * meanR + 0.587 * meanG + 0.114 * meanB).coerceIn(0.0, 1.0)
        for (d in 0 until PATCH_DIM) {
            meanPatch[d] = (meanPatch[d] / count).coerceIn(0.0, 1.0)
        }

        val lightingScene = when {
            avgLuminance >= 0.58 -> "Bright / Outdoor"
            avgLuminance >= 0.25 -> "Balanced / Indoor"
            else -> "Dark / Low-Light"
        }

        val colorTone = classifyColorTone(meanR, meanG, meanB)

        var totalMotionL1 = 0.0
        var stepCount = 0
        var sceneCuts = 0
        var totalNovelty = 0.0
        var noveltySteps = 0

        for (t in 1 until samples.size) {
            val prev = samples[t - 1].patch4x4Luma
            val curr = samples[t].patch4x4Luma
            val len = min(PATCH_DIM, min(prev.size, curr.size))
            if (len == 0) continue

            var l1 = 0.0
            for (d in 0 until len) {
                l1 += abs(curr[d] - prev[d])
            }
            val avgL1 = l1 / len
            totalMotionL1 += avgL1
            stepCount++

            val lumDelta = abs(frameLums[t] - frameLums[t - 1])
            if (avgL1 >= 0.22 || lumDelta >= 0.25) {
                sceneCuts++
            }

            // V-JEPA latent linear trajectory predictor:
            // z_hat_{t} = z_{t-1} + 0.65 * (z_{t-1} - z_{t-2})
            var errSq = 0.0
            for (d in 0 until len) {
                val zPrev = prev[d].toDouble()
                val pred = if (t >= 2 && d < samples[t - 2].patch4x4Luma.size) {
                    val velocity = zPrev - samples[t - 2].patch4x4Luma[d].toDouble()
                    (zPrev + 0.65 * velocity).coerceIn(0.0, 1.0)
                } else {
                    zPrev
                }
                val diff = curr[d].toDouble() - pred
                errSq += diff * diff
            }
            val rmsSurprise = sqrt(errSq / len).coerceIn(0.0, 1.0)
            totalNovelty += rmsSurprise
            noveltySteps++
        }

        val motionScore = if (stepCount > 0) (totalMotionL1 / stepCount).coerceIn(0.0, 1.0) else 0.0
        val vJepaNovelty = if (noveltySteps > 0) (totalNovelty / noveltySteps).coerceIn(0.0, 1.0) else 0.0

        val motionScene = when {
            motionScore >= 0.18 || sceneCuts >= 2 -> "Fast Motion"
            motionScore >= 0.05 || sceneCuts == 1 -> "Moderate Motion"
            else -> "Static Scene"
        }

        val visualCode = quantizeVisualCode(meanPatch, motionScore, avgLuminance)

        return VideoAnalysisResult(
            motionScene = motionScene,
            motionScore = round4(motionScore),
            vJepaNovelty = round4(vJepaNovelty),
            lightingScene = lightingScene,
            avgLuminance = round4(avgLuminance),
            sceneCutCount = sceneCuts,
            colorTone = colorTone,
            codec = codec,
            estimatedFps = estimatedFps?.let { (it * 10.0).roundToInt() / 10.0 },
            hasAudioTrack = hasAudioTrack,
            visualLatent16 = meanPatch.map { round4(it) },
            visualCode = visualCode
        )
    }

    private fun classifyColorTone(r: Double, g: Double, b: Double): String {
        val spread = max(r, max(g, b)) - min(r, min(g, b))
        if (spread < 0.06) return "Neutral"
        return when {
            r > b + 0.08 && r >= g -> "Warm"
            b > r + 0.08 && b >= g -> "Cool"
            g > r + 0.06 && g > b + 0.04 -> "Green / Nature"
            else -> "Neutral"
        }
    }

    private fun quantizeVisualCode(patch: DoubleArray, motionScore: Double, luminance: Double): String {
        var bits = 0
        // 4 coarse quadrant energy bits + 1 motion bit + 2 luminance bits = 7 bits (128 tokens)
        val q0 = (patch[0] + patch[1] + patch[4] + patch[5]) / 4.0
        val q1 = (patch[2] + patch[3] + patch[6] + patch[7]) / 4.0
        val q2 = (patch[8] + patch[9] + patch[12] + patch[13]) / 4.0
        val q3 = (patch[10] + patch[11] + patch[14] + patch[15]) / 4.0
        if (q0 > luminance) bits = bits or 1
        if (q1 > luminance) bits = bits or 2
        if (q2 > luminance) bits = bits or 4
        if (q3 > luminance) bits = bits or 8
        if (motionScore >= 0.08) bits = bits or 16
        val lumBucket = (luminance * 3.99).toInt().coerceIn(0, 3)
        bits = bits or (lumBucket shl 5)
        return String.format(Locale.US, "vis_%03d", bits and 0x7F)
    }

    private fun round4(v: Double): Double = (v * 10000.0).roundToInt() / 10000.0
}
