package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H3 static text overlay: pure layout/validation core. The Android/GL shell
 * (measure with `Paint`, raster to `Bitmap`, upload + draw in
 * `SceneCompositorGL`) executes; everything decidable — spec validity,
 * placement, bounds, alpha, NDC mapping, layer ordering — lives here,
 * unit-tested, with no Android types, threads, or I/O.
 *
 * Fixed H3 policy (pinned by tests, not caller convention):
 * - Text is static for the session: validated once, rasterized once.
 * - Placement is top-left at a deterministic margin; the rect is clamped
 *   inside the canvas, so an oversized measurement can never draw
 *   out-of-bounds.
 * - Alpha is coerced to [0,1]; NaN is invalid (never silently 0 or 1).
 * - Ordering reuses [SceneLayer]/[SceneComposition]: screen z=0 draws
 *   first, text z=1 second; a disabled text layer reduces the visible
 *   composition to passthrough with zero GL delta.
 */
internal const val MAX_TEXT_LAYER_CHARS = 64
internal const val SCREEN_LAYER_ID = "screen"
internal const val TEXT_LAYER_ID = "text"
internal const val SUBTEXT_LAYER_ID = "subtext"

/**
 * Validated static-text specification. [of] returns null (never throws)
 * for blank/oversized text, negative margins, non-positive max width, or
 * NaN alpha; [alpha] is coerced into [0,1].
 */
internal data class SceneTextLayerSpec(
    val text: String,
    val marginPx: Int,
    val maxWidthPx: Int,
    val alpha: Float,
    val enabled: Boolean = true
) {
    companion object {
        fun of(
            text: String,
            marginPx: Int,
            maxWidthPx: Int,
            alpha: Float,
            enabled: Boolean = true
        ): SceneTextLayerSpec? {
            if (text.isBlank()) return null
            if (text.length > MAX_TEXT_LAYER_CHARS) return null
            if (marginPx < 0) return null
            if (maxWidthPx <= 0) return null
            if (alpha.isNaN()) return null
            return SceneTextLayerSpec(text, marginPx, maxWidthPx, alpha.coerceIn(0f, 1f), enabled)
        }
    }
}

/** Deterministic text metrics + placement + ordering. All pure. */
internal object SceneTextLayerLayout {
    /**
     * Raster text size for an output height: 1/20th of the frame, at least
     * 24px so small canvases stay legible. Returns 0 for invalid heights
     * (the shell treats 0 as "cannot raster" and fails start loudly).
     */
    fun textSizePxFor(outputHeightPx: Int): Int =
        if (outputHeightPx <= 0) 0 else (outputHeightPx / 20).coerceAtLeast(24)

    /**
     * Uniform inset margin: 1/40th of the frame height, at least 8px.
     * Returns 0 for invalid heights.
     */
    fun marginPxFor(outputHeightPx: Int): Int =
        if (outputHeightPx <= 0) 0 else (outputHeightPx / 40).coerceAtLeast(8)

    /**
     * Destination rect for measured text on [canvas]: top-left at the spec
     * margin, size capped by the measurement, [SceneTextLayerSpec.maxWidthPx],
     * and the canvas inset. Null when the layer is disabled, the canvas or
     * measurement is invalid, or the margins leave no room — the shell
     * draws nothing (passthrough) on null.
     */
    fun layout(
        canvas: SceneCanvas,
        measuredPx: SceneSizePx,
        spec: SceneTextLayerSpec
    ): SceneRectPx? {
        if (!spec.enabled) return null
        if (!canvas.isValid) return null
        if (measuredPx.widthPx <= 0 || measuredPx.heightPx <= 0) return null
        val roomW = canvas.widthPx - spec.marginPx * 2
        val roomH = canvas.heightPx - spec.marginPx * 2
        if (roomW <= 0 || roomH <= 0) return null
        val width = minOf(measuredPx.widthPx, spec.maxWidthPx, roomW)
        val height = minOf(measuredPx.heightPx, roomH)
        if (width <= 0 || height <= 0) return null
        return SceneRectPx(
            left = spec.marginPx,
            top = spec.marginPx,
            right = spec.marginPx + width,
            bottom = spec.marginPx + height
        )
    }

    /**
     * TRIANGLE_STRIP vertex quad (x,y per vertex, 8 floats) mapping [rectPx]
     * into NDC for an [outputWidthPx]x[outputHeightPx] viewport. Pixel-Y is
     * down, NDC-Y is up, so the mapping flips vertically. Null on invalid
     * geometry; the vertex order matches [0,1] texture coordinates.
     */
    fun ndcQuad(rectPx: SceneRectPx, outputWidthPx: Int, outputHeightPx: Int): FloatArray? {
        if (!rectPx.isValid) return null
        if (outputWidthPx <= 0 || outputHeightPx <= 0) return null
        fun nx(px: Int): Float = (px.toFloat() / outputWidthPx) * 2f - 1f
        fun ny(py: Int): Float = 1f - (py.toFloat() / outputHeightPx) * 2f
        return floatArrayOf(
            nx(rectPx.left), ny(rectPx.bottom),
            nx(rectPx.right), ny(rectPx.bottom),
            nx(rectPx.left), ny(rectPx.top),
            nx(rectPx.right), ny(rectPx.top)
        )
    }

    /**
     * H3 scene composition: screen passthrough behind, text in front. The
     * text layer carries the [textEnabled] flag, so disabling it reduces
     * [SceneComposition.visibleLayersInDrawOrder] to passthrough. Never null
     * (fixed valid ids); a null would mean the H0 contract broke.
     */
    fun compositionFor(textEnabled: Boolean): SceneComposition {
        val composition = SceneComposition.of(
            listOf(
                SceneLayer(id = SCREEN_LAYER_ID, sourceKey = SCREEN_LAYER_ID, zOrder = 0),
                SceneLayer(
                    id = TEXT_LAYER_ID,
                    sourceKey = TEXT_LAYER_ID,
                    zOrder = 1,
                    enabled = textEnabled
                )
            )
        )
        checkNotNull(composition) { "fixed H3 layer ids must always compose" }
        return composition
    }
}
