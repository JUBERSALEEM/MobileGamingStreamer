package com.jubersaleem.mobilegamingstreamer.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Surface-input H.264 encoder validation.
 *
 * Encoded access units are currently measured and discarded. RTMP transport is a
 * separate milestone; this class never stores video or sends it over the network.
 */
class H264SurfaceEncoder(
    private val width: Int,
    private val height: Int
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var encodedFrames = 0L
    @Volatile private var encodedBytes = 0L
    @Volatile private var reportStartedAtMs = 0L
    private var frameRate = 30

    fun start(): Surface {
        check(codec == null) { "Encoder is already running" }
        val selected = selectEncoder(width, height)
        frameRate = selected.second
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (frameRate == 60) 8_000_000 else 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        }

        val encoder = MediaCodec.createByCodecName(selected.first)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder
            inputSurface = surface
            encodedFrames = 0L
            encodedBytes = 0L
            reportStartedAtMs = android.os.SystemClock.elapsedRealtime()
            running.set(true)
            drainThread = Thread({ drainOutput(encoder) }, "MGS-H264-Drain").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "H.264 encoder started: ${width}x${height} @ ${frameRate}fps, ${selected.first}")
            return surface
        } catch (error: Throwable) {
            try { encoder.stop() } catch (_: Exception) { }
            encoder.release()
            throw error
        }
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        while (running.get()) {
            try {
                val index = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index >= 0 -> {
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            encodedFrames++
                            encodedBytes += bufferInfo.size
                            val elapsed = android.os.SystemClock.elapsedRealtime() - reportStartedAtMs
                            if (elapsed >= REPORT_INTERVAL_MS) {
                                val fps = encodedFrames * 1000.0 / elapsed
                                val kbps = encodedBytes * 8.0 / elapsed
                                Log.i(
                                    TAG,
                                    "Encoder stats: ${width}x${height}, outputFps=${"%.1f".format(java.util.Locale.US, fps)}, bitrate=${"%.0f".format(java.util.Locale.US, kbps)}kbps"
                                )
                                encodedFrames = 0L
                                encodedBytes = 0L
                                reportStartedAtMs = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                        activeCodec.releaseOutputBuffer(index, false)
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        Log.i(TAG, "Encoder output format: ${activeCodec.outputFormat}")
                }
            } catch (error: IllegalStateException) {
                if (running.get()) Log.e(TAG, "Encoder output drain failed", error)
                break
            } catch (error: RuntimeException) {
                if (running.get()) Log.e(TAG, "Unexpected encoder drain error", error)
                break
            }
        }
    }

    fun stop() {
        running.set(false)
        try { drainThread?.join(1_000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        val activeCodec = codec
        codec = null
        if (activeCodec != null) {
            try { activeCodec.stop() } catch (_: Exception) { }
            activeCodec.release()
        }
        inputSurface?.release()
        inputSurface = null
        Log.i(TAG, "H.264 encoder stopped")
    }

    private fun selectEncoder(width: Int, height: Int): Pair<String, Int> {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

        for (fps in listOf(60, 30)) {
            for (info in candidates) {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = caps.videoCapabilities ?: continue
                if (video.areSizeAndRateSupported(width, height, fps.toDouble())) {
                    return info.name to fps
                }
            }
        }
        throw IllegalStateException("No surface H.264 encoder supports ${width}x${height} at 30fps")
    }

    companion object {
        private const val TAG = "MGS-Encoder"
        private const val REPORT_INTERVAL_MS = 5_000L

        /** Scale to a practical first-pass maximum while preserving source aspect ratio. */
        fun scaledSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
            val scale = min(1.0, 1920.0 / maxOf(sourceWidth, sourceHeight))
            val width = ((sourceWidth * scale).toInt() / 2) * 2
            val height = ((sourceHeight * scale).toInt() / 2) * 2
            return maxOf(2, width) to maxOf(2, height)
        }
    }
}
