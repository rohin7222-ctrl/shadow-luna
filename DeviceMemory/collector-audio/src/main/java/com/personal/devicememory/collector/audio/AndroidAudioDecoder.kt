package com.personal.devicememory.collector.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Hardware-accelerated sparse PCM audio sampler using Android's MediaExtractor + MediaCodec.
 * Decodes brief representative segments (up to ~2.5s across file start/middle) directly on-device
 * into 16kHz normalized mono FloatArray for [SpectralAudioEngine].
 */
object AndroidAudioDecoder {

    private const val TAG = "AndroidAudioDecoder"
    private const val MAX_OUTPUT_SAMPLES = SpectralAudioEngine.TARGET_SAMPLE_RATE * 2 // ~2s sparse budget
    private const val DECODE_TIMEOUT_MS = 1200L

    fun analyzeUri(context: Context, uri: Uri): AudioAnalysisResult? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || audioFormat == null) return null

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            extractor.selectTrack(audioTrackIdx)
            if (durationUs > 6_000_000L) {
                // Seek into interior (~20%) to skip leading silence/intro fade-ins
                extractor.seekTo(durationUs / 5L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            var sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100
            var channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 1

            val pcmOut = ArrayList<Float>(MAX_OUTPUT_SAMPLES)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val startedMs = System.currentTimeMillis()
            var decimatedStep = maxOf(1, sampleRate / SpectralAudioEngine.TARGET_SAMPLE_RATE)
            var sampleCounter = 0

            while (pcmOut.size < MAX_OUTPUT_SAMPLES && (System.currentTimeMillis() - startedMs) < DECODE_TIMEOUT_MS) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val chunkSize = extractor.readSampleData(buf, 0)
                            if (chunkSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, chunkSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        decimatedStep = maxOf(1, sampleRate / SpectralAudioEngine.TARGET_SAMPLE_RATE)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                    }
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val totalShorts = shortBuf.remaining()
                        var i = 0
                        while (i + channels <= totalShorts && pcmOut.size < MAX_OUTPUT_SAMPLES) {
                            if (sampleCounter % decimatedStep == 0) {
                                var monoSum = 0f
                                for (c in 0 until channels) {
                                    monoSum += shortBuf.get(i + c) / 32768f
                                }
                                pcmOut.add(monoSum / channels)
                            }
                            sampleCounter++
                            i += channels
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }

            if (pcmOut.size < SpectralAudioEngine.FFT_SIZE) return null
            val effectiveRate = (sampleRate / decimatedStep).coerceIn(8000, 48000)
            return SpectralAudioEngine.analyzePcm(pcmOut.toFloatArray(), effectiveRate)
        } catch (t: Throwable) {
            Log.w(TAG, "Audio spectral decode skipped for $uri: ${t.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }
}
