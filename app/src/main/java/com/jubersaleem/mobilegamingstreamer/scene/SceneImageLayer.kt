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

    /**
     * J1 FILL frame: the full available bounds capped to room
     * (maxWidth × full available height). Null on any invalid input.
     * Both renderer and preview call this, so FILL boxes agree.
     */
    fun fillSize(
        maxWidthPx: Int,
        maxHeightPx: Int,
        roomW: Int,
        roomH: Int
    ): SceneSizePx? {
        if (maxWidthPx <= 0 || maxHeightPx <= 0) return null
        if (roomW <= 0 || roomH <= 0) return null
        val width = minOf(maxWidthPx, roomW)
        val height = minOf(maxHeightPx, roomH)
        if (width <= 0 || height <= 0) return null
        return SceneSizePx(width, height)
    }

    /**
     * J1 centered cover-crop of a [bitmapWidthPx]x[bitmapHeightPx]
     * bitmap into a [boxW]x[boxH] frame, in bitmap pixels. The crop
     * keeps the frame aspect exactly; the overflow is cut centered
     * with floored bias (a leftover pixel goes right/bottom,
     * matching the repo centering convention). Long math, null on
     * any invalid input or a collapsed crop.
     */
    fun fillCrop(
        bitmapWidthPx: Int,
        bitmapHeightPx: Int,
        boxW: Int,
        boxH: Int
    ): SceneRectPx? {
        if (bitmapWidthPx <= 0 || bitmapHeightPx <= 0) return null
        if (boxW <= 0 || boxH <= 0) return null
        val cropW: Long
        val cropH: Long
        if (boxW.toLong() * bitmapHeightPx >= boxH.toLong() * bitmapWidthPx) {
            // Frame relatively wider: full width, crop the height.
            cropW = bitmapWidthPx.toLong()
            cropH = bitmapWidthPx.toLong() * boxH / boxW
        } else {
            cropH = bitmapHeightPx.toLong()
            cropW = bitmapHeightPx.toLong() * boxW / boxH
        }
        if (cropW <= 0 || cropH <= 0) return null
        if (cropW > Int.MAX_VALUE || cropH > Int.MAX_VALUE) return null
        val cw = cropW.toInt()
        val ch = cropH.toInt()
        val left = (bitmapWidthPx - cw) / 2
        val top = (bitmapHeightPx - ch) / 2
        return SceneRectPx(left, top, left + cw, top + ch)
    }
}
