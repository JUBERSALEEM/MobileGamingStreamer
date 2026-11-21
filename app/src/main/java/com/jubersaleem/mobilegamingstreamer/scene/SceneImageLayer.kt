package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H6 pure image-layer math: aspect-preserving display size for a decoded
 * bitmap inside caller-supplied bounds. Pure Kotlin (no Android, no
 * bitmaps): callers decode (capped) and pass dimensions here, then feed
 * the result to [SceneOverlayStack.placeRect] like any measurement.
 *
 * Display size is absolute pixels: the same bitmap shows at the same
 * pixel size on any canvas (placement rules stay canvas-relative, so
 * preview and renderer agree exactly). Images never upscale: a small
 * bitmap keeps its native size instead of blurring up to the bounds.
 */
internal object SceneImageLayer {
    /**
     * Fit-inside display size for a [bitmapWidthPx]x[bitmapHeightPx]
     * bitmap within [maxWidthPx]x[maxHeightPx]. Long integer math (no
     * overflow, no divide-by-zero); null on any invalid input or when
     * the fit collapses to zero.
     */
    fun measuredSize(
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
        maxWidthPx: Int,
        maxHeightPx: Int
    ): SceneSizePx? {
        if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0) return null
        if (maxWidthPx <= 0 || maxHeightPx <= 0) return null
        var width = bitmapWidthPx.toLong()
        var height = bitmapHeightPx.toLong()
        if (width > maxWidthPx) {
            height = height * maxWidthPx / width
            width = maxWidthPx.toLong()
        }
        if (height > maxHeightPx) {
            width = width * maxHeightPx / height
            height = maxHeightPx.toLong()
        }
        if (width <= 0 || height <= 0) return null
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return SceneSizePx(width.toInt(), height.toInt())
    }
}
