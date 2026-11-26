package com.personal.devicememory.collector.photos

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PhotoVisualResult(
    val lightingScene: String,       // "Bright / Outdoor", "Balanced / Indoor", "Dark / Low-Light"
    val colorTone: String,           // "Warm", "Cool", "Green / Nature", "Neutral"
    val avgLuminance: Double,        // [0..1]
    val contrastStd: Double,         // luminance std dev [0..1]
    val edgeDensity: Double,         // high-contrast horizontal/vertical gradient density [0..1]
    val isDocumentOrReceipt: Boolean,// high-contrast + edge-dense likely document/screenshot/receipt
    val visualLatent16: List<Double>,// 16D spatial 4x4 patch latent compatible with VideoJepaAnalyzer
    val visualCode: String           // discrete codebook token "vis_000..vis_127"
)

/**
 * Pure-Kotlin 2026 Photo Spatial Visual Analyzer.
 * Shares the 16D 4x4 luma latent and discrete `vis_000..127` codebook space with [VideoJepaAnalyzer]
 * so Photos & Videos align cross-modally, plus computes document/text-density likelihood for OCR routing.
 */
object PhotoVisualAnalyzer {

    private const val GRID = 4

    fun analyzeGrid(
        width: Int,
        height: Int,
        lumaGrid: FloatArray, // normalized [0..1] pixel luminances (w * h)
        avgR: Double,
        avgG: Double,
        avgB: Double
    ): PhotoVisualResult? {
        if (width <= 0 || height <= 0 || lumaGrid.isEmpty()) return null
        val safeR = if (avgR.isFinite()) avgR.coerceIn(0.0, 1.0) else 0.0
        val safeG = if (avgG.isFinite()) avgG.coerceIn(0.0, 1.0) else 0.0
        val safeB = if (avgB.isFinite()) avgB.coerceIn(0.0, 1.0) else 0.0

        val patchSums = DoubleArray(GRID * GRID)
        val patchCounts = IntArray(GRID * GRID)
        var sumLum = 0.0
        var sumLumSq = 0.0
        var edgeHits = 0
        var edgeChecks = 0

        val total = min(width * height, lumaGrid.size)
        for (idx in 0 until total) {
            val y = idx / width
            val x = idx % width
            val rawL = lumaGrid[idx].toDouble()
            val lum = if (rawL.isFinite()) rawL.coerceIn(0.0, 1.0) else 0.0
            sumLum += lum
            sumLumSq += lum * lum

            val gy = min(3, (y * 4) / height)
            val gx = min(3, (x * 4) / width)
            val pIdx = gy * 4 + gx
            patchSums[pIdx] += lum
            patchCounts[pIdx]++

            if (x + 1 < width) {
                val rightL = lumaGrid[idx + 1].toDouble().let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
                if (abs(lum - rightL) >= 0.20) edgeHits++
                edgeChecks++
            }
        }

        val count = total.coerceAtLeast(1).toDouble()
        val meanLum = (sumLum / count).coerceIn(0.0, 1.0)
        val variance = max(0.0, (sumLumSq / count) - (meanLum * meanLum))
        val contrastStd = sqrt(variance).coerceIn(0.0, 1.0)
        val edgeDensity = if (edgeChecks > 0) (edgeHits.toDouble() / edgeChecks).coerceIn(0.0, 1.0) else 0.0

        val lightingScene = when {
            meanLum >= 0.58 -> "Bright / Outdoor"
            meanLum >= 0.25 -> "Balanced / Indoor"
            else -> "Dark / Low-Light"
        }
        val colorTone = classifyTone(safeR, safeG, safeB)
        val isDoc = contrastStd >= 0.22 && edgeDensity >= 0.08

        val patch16 = DoubleArray(16) { i ->
            if (patchCounts[i] > 0) (patchSums[i] / patchCounts[i]).coerceIn(0.0, 1.0) else meanLum
        }
        val visualCode = quantizeVisualCode(patch16, meanLum)

        return PhotoVisualResult(
            lightingScene = lightingScene,
            colorTone = colorTone,
            avgLuminance = round4(meanLum),
            contrastStd = round4(contrastStd),
            edgeDensity = round4(edgeDensity),
            isDocumentOrReceipt = isDoc,
            visualLatent16 = patch16.map { round4(it) },
            visualCode = visualCode
        )
    }

    private fun classifyTone(r: Double, g: Double, b: Double): String {
        val spread = max(r, max(g, b)) - min(r, min(g, b))
        if (spread < 0.06) return "Neutral"
        return when {
            r > b + 0.08 && r >= g -> "Warm"
            b > r + 0.08 && b >= g -> "Cool"
            g > r + 0.06 && g > b + 0.04 -> "Green / Nature"
            else -> "Neutral"
        }
    }

    private fun quantizeVisualCode(patch: DoubleArray, lum: Double): String {
        var bits = 0
        val q0 = (patch[0] + patch[1] + patch[4] + patch[5]) / 4.0
        val q1 = (patch[2] + patch[3] + patch[6] + patch[7]) / 4.0
        val q2 = (patch[8] + patch[9] + patch[12] + patch[13]) / 4.0
        val q3 = (patch[10] + patch[11] + patch[14] + patch[15]) / 4.0
        if (q0 > lum) bits = bits or 1
        if (q1 > lum) bits = bits or 2
        if (q2 > lum) bits = bits or 4
        if (q3 > lum) bits = bits or 8
        val lumBucket = (lum * 3.99).toInt().coerceIn(0, 3)
        bits = bits or (lumBucket shl 5)
        return String.format(Locale.US, "vis_%03d", bits and 0x7F)
    }

    private fun round4(v: Double): Double = if (v.isFinite()) (v * 10000.0).roundToInt() / 10000.0 else 0.0
}
