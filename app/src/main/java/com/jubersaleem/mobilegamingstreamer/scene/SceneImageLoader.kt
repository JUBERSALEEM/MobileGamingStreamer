package com.jubersaleem.mobilegamingstreamer.scene

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size

/**
 * H6 image-byte loading over [ContentResolver]. Every entry point catches
 * [Throwable] and returns null: missing files, revoked URI permission,
 * corrupt bytes, oversized images, and even decode-time [OutOfMemoryError]
 * degrade to "layer skipped", never a crash and never a dead stream.
 *
 * Decoded bitmaps are downsampled so their longest side is at most
 * [MAX_IMAGE_BITMAP_PX]: overlay pixels stay small regardless of source
 * resolution (a 12 MP gallery photo never becomes a 48 MB texture).
 */
internal const val MAX_IMAGE_BITMAP_PX = 512

internal object SceneImageLoader {
    /**
     * Cheap decoded dimensions for aspect math (allocates no bitmap).
     * Null when the URI cannot be opened or has no decodable bounds.
     */
    fun decodeBounds(resolver: ContentResolver, uriString: String): SceneSizePx? {
        return try {
            val uri = Uri.parse(uriString)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) null else SceneSizePx(width, height)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Downsampled ARGB bitmap (longest side ≤ [maxDimPx], keeps alpha for
     * PNG logos). Two-pass bounds + `inSampleSize` decode plus an exact
     * scale-down; the transient full-sample bitmap is recycled before
     * returning. Null on any failure.
     */
    fun decodeBitmap(
        resolver: ContentResolver,
        uriString: String,
        maxDimPx: Int = MAX_IMAGE_BITMAP_PX
    ): Bitmap? {
        return try {
            if (maxDimPx <= 0) return null
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val rawWidth = bounds.outWidth
            val rawHeight = bounds.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null
            var sampleSize = 1
            while (rawWidth / (sampleSize * 2) >= maxDimPx &&
                rawHeight / (sampleSize * 2) >= maxDimPx
            ) {
                sampleSize *= 2
            }
            val decode = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decode)
            } ?: return null
            if (sampled.width <= 0 || sampled.height <= 0) {
                try {
                    sampled.recycle()
                } catch (_: Throwable) {
                }
                return null
            }
            val longest = maxOf(sampled.width, sampled.height)
            if (longest <= maxDimPx) return sampled
            // Exact cap: scale down, recycle the transient (createScaledBitmap
            // may return the same instance when sizes match — never
            // double-own it).
            val scale = maxDimPx.toFloat() / longest.toFloat()
            val exact = Bitmap.createScaledBitmap(
                sampled,
                (sampled.width * scale).toInt().coerceAtLeast(1),
                (sampled.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (exact !== sampled) {
                try {
                    sampled.recycle()
                } catch (_: Throwable) {
                }
            }
            exact
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Small bitmap for editor UI (thumbnails, preview fill) via the
     * platform thumbnailer. Null on any failure.
     */
    fun loadThumbnailBitmap(
        resolver: ContentResolver,
        uriString: String,
        sizePx: Int
    ): Bitmap? {
        return try {
            if (sizePx <= 0) return null
            resolver.loadThumbnail(Uri.parse(uriString), Size(sizePx, sizePx), null)
        } catch (_: Throwable) {
            null
        }
    }
}
