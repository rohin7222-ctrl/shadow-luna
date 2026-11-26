package com.personal.devicememory.collector.audio

import android.content.Context
import java.util.Locale
import kotlin.math.sqrt

/**
 * 2026 On-Device Acoustic Speaker Voice-Print Discovery (Spherical K-Means over MFCC[1..12]).
 * Clusters speech-dominant recordings/videos into recurring anonymous speaker IDs (`spk_01`, `spk_02`...)
 * without any cloud transmission or pre-trained voice database.
 */
object AcousticSpeakerClusterer {

    private const val PREFS_NAME = "shadow_speaker_centroids"
    private const val PREF_CENTROIDS = "centroids_blob"
    private const val MAX_SPEAKERS = 20
    private const val MATCH_COSINE_THRESHOLD = 0.86

    data class Centroid(val id: String, val vec: DoubleArray, val hits: Int)

    /** Extracts L2-normalized cepstral shape vector from MFCC indices 1..12 (excluding C0 loudness). */
    fun normalizeVoicePrint(mfccSignature: List<Double>): DoubleArray? {
        if (mfccSignature.size < 13) return null
        val sub = DoubleArray(12) { idx ->
            val v = mfccSignature[idx + 1]
            if (v.isFinite()) v else 0.0
        }
        val norm = sqrt(sub.sumOf { it * it })
        if (norm < 1e-6) return null
        return DoubleArray(12) { i -> sub[i] / norm }
    }

    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot.coerceIn(-1.0, 1.0)
    }

    /** Pure-Kotlin matching + EMA centroid update usable both in-memory (tests) and persisted store. */
    fun assignOrCreateCluster(
        voicePrint: DoubleArray,
        centroids: MutableList<Centroid>
    ): String {
        var bestIdx = -1
        var bestSim = -1.0
        for (i in centroids.indices) {
            val sim = cosine(voicePrint, centroids[i].vec)
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }
        if (bestIdx >= 0 && bestSim >= MATCH_COSINE_THRESHOLD) {
            val prev = centroids[bestIdx]
            val blended = DoubleArray(12) { d -> prev.vec[d] * 0.80 + voicePrint[d] * 0.20 }
            val norm = sqrt(blended.sumOf { it * it }).coerceAtLeast(1e-6)
            val normVec = DoubleArray(12) { d -> blended[d] / norm }
            centroids[bestIdx] = Centroid(prev.id, normVec, prev.hits + 1)
            return prev.id
        }

        if (centroids.size >= MAX_SPEAKERS) {
            // Replace weakest centroid only if hit == 1 else return closest match
            val weakest = centroids.indices.minByOrNull { centroids[it].hits } ?: 0
            if (centroids[weakest].hits == 1) {
                val id = centroids[weakest].id
                centroids[weakest] = Centroid(id, voicePrint.copyOf(), 1)
                return id
            }
            return if (bestIdx >= 0) centroids[bestIdx].id else "spk_01"
        }

        val nextId = String.format(Locale.US, "spk_%02d", centroids.size + 1)
        centroids.add(Centroid(nextId, voicePrint.copyOf(), 1))
        return nextId
    }

    @Synchronized
    fun identifyAndPersist(context: Context, mfccSignature: List<Double>, speechRatio: Double): String? {
        if (speechRatio < 0.25) return null
        val vp = normalizeVoicePrint(mfccSignature) ?: return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_CENTROIDS, null)
        val centroids = parseBlob(raw).toMutableList()
        val clusterId = assignOrCreateCluster(vp, centroids)
        prefs.edit().putString(PREF_CENTROIDS, serializeBlob(centroids)).commit()
        return clusterId
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun parseBlob(blob: String?): List<Centroid> {
        if (blob.isNullOrBlank()) return emptyList()
        return blob.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0]
            val hits = parts[1].toIntOrNull() ?: 1
            val vals = parts[2].split(",").mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
            if (vals.size == 12) Centroid(id, vals, hits) else null
        }
    }

    private fun serializeBlob(centroids: List<Centroid>): String =
        centroids.joinToString("\n") { c ->
            val vecStr = c.vec.joinToString(",") { String.format(Locale.US, "%.4f", it) }
            "${c.id}|${c.hits}|$vecStr"
        }
}
