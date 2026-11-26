package com.personal.devicememory.collector.videos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.personal.devicememory.collector.audio.AndroidAudioDecoder
import com.personal.devicememory.collector.audio.AudioAnalysisResult
import kotlin.math.min

data class CrossModalVideoAnalysis(
    val visual: VideoAnalysisResult?,
    val audio: AudioAnalysisResult?
)

/**
 * 2026 Hardware-Accelerated Sparse Keyframe Sampler & Audio-Visual Fusion Engine.
 *
 * Samples 4 adaptive keyframes (`12%`, `37%`, `62%`, `87%` of duration) at 32x32 resolution via
 * [MediaMetadataRetriever], inspects codec / FPS / audio-track metadata via [MediaExtractor],
 * computes V-JEPA spatio-temporal latent dynamics via [VideoJepaAnalyzer], and fuses acoustic
 * scene classification from the embedded audio track via [AndroidAudioDecoder].
 */
object AndroidVideoFrameSampler {

    private const val TAG = "AndroidVideoSampler"
    private const val FRAME_BUDGET_MS = 1600L

    fun analyzeUri(context: Context, uri: Uri, durationMs: Long): CrossModalVideoAnalysis {
        var codec: String? = null
        var estimatedFps: Double? = null
        var hasAudioTrack = false

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    codec = simplifyVideoCodec(mime)
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        estimatedFps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    } else if (fmt.containsKey(MediaFormat.KEY_CAPTURE_RATE)) {
                        estimatedFps = fmt.getFloat(MediaFormat.KEY_CAPTURE_RATE).toDouble()
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudioTrack = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaExtractor inspection skipped for $uri: ${e.message}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        val samples = mutableListOf<FrameSample>()
        val retriever = MediaMetadataRetriever()
        val startedMs = System.currentTimeMillis()
        try {
            retriever.setDataSource(context, uri)
            val safeDurationMs = if (durationMs > 0L) durationMs else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }

            val targetTimesMs = if (safeDurationMs >= 1000L) {
                listOf(
                    (safeDurationMs * 0.12).toLong(),
                    (safeDurationMs * 0.37).toLong(),
                    (safeDurationMs * 0.62).toLong(),
                    (safeDurationMs * 0.87).toLong()
                )
            } else {
                listOf(0L)
            }

            for (tsMs in targetTimesMs) {
                if (System.currentTimeMillis() - startedMs > FRAME_BUDGET_MS) break
                val bmp = retrieveCompactBitmap(retriever, tsMs * 1000L) ?: continue
                try {
                    samples.add(bitmapToFrameSample(tsMs, bmp))
                } finally {
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keyframe sampling failed for $uri: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val visual = VideoJepaAnalyzer.analyzeFrames(
            samples = samples,
            codec = codec,
            estimatedFps = estimatedFps,
            hasAudioTrack = hasAudioTrack
        )
        val audio = if (hasAudioTrack) {
            try {
                AndroidAudioDecoder.analyzeUri(context, uri)
            } catch (e: Exception) {
                Log.d(TAG, "Video audio track decode failed: ${e.message}")
                null
            }
        } else null

        return CrossModalVideoAnalysis(visual = visual, audio = audio)
    }

    private fun retrieveCompactBitmap(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    32,
                    32
                )
            } else {
                val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val scaled = Bitmap.createScaledBitmap(full, 32, 32, true)
                if (scaled != full) full.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToFrameSample(timestampMs: Long, bmp: Bitmap): FrameSample {
        val w = bmp.width.coerceAtLeast(4)
        val h = bmp.height.coerceAtLeast(4)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val patchSums = FloatArray(16)
        val patchCounts = IntArray(16)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0

        for (y in 0 until h) {
            val gy = min(3, (y * 4) / h)
            for (x in 0 until w) {
                val gx = min(3, (x * 4) / w)
                val pIdx = gy * 4 + gx
                val c = pixels[y * w + x]
                val r = Color.red(c) / 255.0
                val g = Color.green(c) / 255.0
                val b = Color.blue(c) / 255.0
                sumR += r
                sumG += g
                sumB += b
                patchSums[pIdx] += (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
                patchCounts[pIdx]++
            }
        }

        val patch = FloatArray(16) { idx ->
            if (patchCounts[idx] > 0) patchSums[idx] / patchCounts[idx] else 0f
        }
        val n = pixels.size.toDouble().coerceAtLeast(1.0)
        return FrameSample(
            timestampMs = timestampMs,
            patch4x4Luma = patch,
            avgR = sumR / n,
            avgG = sumG / n,
            avgB = sumB / n
        )
    }

    private fun simplifyVideoCodec(mime: String): String {
        val lower = mime.lowercase()
        return when {
            "av01" in lower || "av1" in lower -> "AV1"
            "hevc" in lower || "hvc1" in lower -> "HEVC"
            "avc" in lower || "h264" in lower -> "AVC"
            "vp9" in lower -> "VP9"
            "vp8" in lower -> "VP8"
            else -> mime.substringAfter("video/").uppercase()
        }
    }
}
