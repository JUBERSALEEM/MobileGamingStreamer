package com.jubersaleem.mobilegamingstreamer.scene

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/**
 * J2 video inspection shell: metadata probe + poster extraction via
 * framework `MediaMetadataRetriever`. Device-gated (no JVM coverage):
 * MMR cannot execute on the JVM. Verified by Android compilation,
 * lint, and CI packaging.
 *
 * Every entry point never throws (null on any failure, including
 * revoked/renamed documents, unsupported containers, and OOM): the
 * compositor drops the slot loudly and the editor shows FAILED.
 * Callers run these off the GL thread for posters (editor
 * `remember`), or once per slot at overlay build (probe).
 */
internal object SceneVideoProbe {
    private const val TAG = "MGS-VideoProbe"

    /**
     * Display dimensions of the video track, container rotation
     * applied (MediaPlayer renders pre-rotated frames). Null unless
     * the document has a video track with sane positive dims.
     */
    fun probeSize(context: Context, uriString: String): SceneSizePx? {
        try {
            val uri = parseUri(uriString) ?: return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    return null
                }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: return null
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: return null
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                return applyVideoRotation(width, height, rotation)
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    /** Duration in ms, or null when unavailable. Never throws. */
    fun probeDurationMs(context: Context, uriString: String): Long? {
        try {
            val uri = parseUri(uriString) ?: return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it >= 0 }
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Sampled poster frame (closest sync frame to t=0), longest edge
     * capped to [maxEdgePx]. Null when the video cannot be probed or
     * the frame cannot be extracted. Never throws. The caller owns
     * the returned bitmap.
     */
    fun posterBitmap(context: Context, uriString: String, maxEdgePx: Int): Bitmap? {
        if (maxEdgePx <= 0) return null
        try {
            val uri = parseUri(uriString) ?: return null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: return null
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: return null
                if (width <= 0 || height <= 0) return null
                val longest = maxOf(width, height).toLong()
                val dstW = (width.toLong() * maxEdgePx / longest).toInt().coerceAtLeast(1)
                val dstH = (height.toLong() * maxEdgePx / longest).toInt().coerceAtLeast(1)
                return retriever.getScaledFrameAtTime(
                    0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            Log.w(TAG, "poster extraction failed")
            return null
        }
    }

    private fun parseUri(uriString: String): Uri? {
        if (uriString.isBlank()) return null
        return try {
            Uri.parse(uriString)
        } catch (_: Throwable) {
            null
        }
    }
}
