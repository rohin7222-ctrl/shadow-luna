package com.personal.devicememory.collector.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class PhotoEnrichment(
    val visual: PhotoVisualResult?,
    val ocrText: String?
)

/**
 * Hardware-friendly On-Device Photo Visual & Offline ML Kit OCR Analyzer.
 * Downsamples large gallery photos cleanly (< 640px) before decoding pixels or running bundled OCR,
 * redacts any OTP/card patterns via [SensitiveContentFilter], and enforces strict timeouts.
 */
object AndroidPhotoAnalyzer {

    private const val TAG = "AndroidPhotoAnalyzer"
    private const val MAX_DIM = 640
    private const val OCR_TIMEOUT_MS = 1200L

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun analyzeUri(context: Context, uri: Uri, isScreenshotHint: Boolean): PhotoEnrichment {
        val bmp = decodeCompactBitmap(context, uri) ?: return PhotoEnrichment(null, null)
        return try {
            val visual = computeVisual(bmp)
            val runOcr = isScreenshotHint || (visual?.isDocumentOrReceipt == true)
            val ocrText = if (runOcr) runOfflineOcr(bmp) else null
            PhotoEnrichment(visual = visual, ocrText = ocrText)
        } catch (t: Throwable) {
            Log.d(TAG, "Photo enrichment skipped for $uri: ${t.message}")
            PhotoEnrichment(null, null)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun decodeCompactBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while ((bounds.outWidth / sample) > MAX_DIM || (bounds.outHeight / sample) > MAX_DIM) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeVisual(bmp: Bitmap): PhotoVisualResult? {
        val scaled = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        return try {
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val lumaGrid = FloatArray(pixels.size)
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c) / 255.0
                val g = Color.green(c) / 255.0
                val b = Color.blue(c) / 255.0
                sumR += r
                sumG += g
                sumB += b
                lumaGrid[i] = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
            }
            val n = pixels.size.toDouble().coerceAtLeast(1.0)
            PhotoVisualAnalyzer.analyzeGrid(w, h, lumaGrid, sumR / n, sumG / n, sumB / n)
        } finally {
            if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun runOfflineOcr(bmp: Bitmap): String? {
        if (bmp.width < 32 || bmp.height < 32) return null
        val latch = CountDownLatch(1)
        val outRef = AtomicReference<String?>(null)
        return try {
            val image = InputImage.fromBitmap(bmp, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val cleaned = result.text
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                    if (cleaned.length >= 4) {
                        val redacted = SensitiveContentFilter.redact(cleaned) ?: cleaned
                        outRef.set(redacted.take(160).trim())
                    }
                    latch.countDown()
                }
                .addOnFailureListener {
                    latch.countDown()
                }
            latch.await(OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            outRef.get()
        } catch (_: Throwable) {
            null
        }
    }
}
