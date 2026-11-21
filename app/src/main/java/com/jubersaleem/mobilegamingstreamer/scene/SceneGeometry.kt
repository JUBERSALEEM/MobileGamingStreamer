package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H0 scene-engine foundation: pure Kotlin canvas/source geometry and layout math.
 *
 * This is a future scene-composition contract, NOT a replacement for the live
 * pipeline: RootEncoder 2.8.1 [ScreenSource] remains the active production
 * source and its viewport/orientation logic is untouched. No caller wires this
 * file into the encoder; H1 must verify viewport parity against the library
 * before any production use, never assume it.
 *
 * Design rules:
 * - Pure Kotlin: no Android, EGL, GLES, Surface or MediaCodec types.
 * - All linear units are integer pixels; rotation is integer degrees.
 * - All scaling uses Long integer math, so Int inputs can never overflow,
 *   divide by zero (dimensions are validity-gated to >= 1 first) or produce
 *   NaN. Floating point appears only in [SceneSourceGeometry.effectiveAspectRatio],
 *   which is computed for valid geometries only and is therefore always finite.
 * - Deterministic: equal inputs always produce equal outputs, including the
 *   documented sub-pixel remainder bias (integer floor; a leftover pixel goes
 *   right/bottom when centering).
 */
internal const val MAX_SCENE_DIMENSION_PX = 16384

/**
 * Explicit output orientation. SQUARE is its own case so callers never have
 * to guess which branch a square canvas takes.
 */
internal enum class SceneOrientation {
    PORTRAIT,
    LANDSCAPE,
    SQUARE
}

/**
 * Immutable output-canvas geometry in pixels. Validity is an arithmetic-safety
 * bound ([MAX_SCENE_DIMENSION_PX]), not a product gate: real encoder sizes
 * continue to come from VideoProfileSelector.
 */
internal data class SceneCanvas(val widthPx: Int, val heightPx: Int) {
    val isValid: Boolean
        get() = widthPx in 1..MAX_SCENE_DIMENSION_PX &&
            heightPx in 1..MAX_SCENE_DIMENSION_PX

    /** Null when the canvas is invalid, so callers cannot orient a bad canvas. */
    val orientation: SceneOrientation?
        get() = when {
            !isValid -> null
            heightPx > widthPx -> SceneOrientation.PORTRAIT
            widthPx > heightPx -> SceneOrientation.LANDSCAPE
            else -> SceneOrientation.SQUARE
        }
}

/** Immutable width/height pair in pixels (post-rotation effective sizes). */
internal data class SceneSizePx(val widthPx: Int, val heightPx: Int)

/**
 * Immutable input-source geometry in pixels plus optional rotation metadata.
 *
 * Only quarter-turn rotations are accepted: H1 sources (display rotation,
 * camera sensor orientation) report multiples of 90 degrees, and anything
 * else is a caller bug that must be rejected, not silently normalized.
 */
internal data class SceneSourceGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int = 0
) {
    val isValidDimensions: Boolean
        get() = widthPx in 1..MAX_SCENE_DIMENSION_PX &&
            heightPx in 1..MAX_SCENE_DIMENSION_PX

    val isValidRotation: Boolean
        get() = rotationDegrees == 0 || rotationDegrees == 90 ||
            rotationDegrees == 180 || rotationDegrees == 270

    val isValid: Boolean get() = isValidDimensions && isValidRotation

    /**
     * Post-rotation size (width/height swap on 90/270). Null when invalid.
     * All layout math operates in this effective space; mapping a crop back
     * to pre-rotation buffer coordinates is an H1 renderer concern.
     */
    fun effectiveSizePx(): SceneSizePx? {
        if (!isValid) return null
        return if (rotationDegrees == 90 || rotationDegrees == 270) {
            SceneSizePx(heightPx, widthPx)
        } else {
            SceneSizePx(widthPx, heightPx)
        }
    }

    /**
     * Post-rotation width-over-height ratio. Null when invalid; otherwise
     * always finite (valid dimensions are >= 1, so no zero divisor or NaN).
     */
    val effectiveAspectRatio: Double?
        get() {
            val effective = effectiveSizePx() ?: return null
            return effective.widthPx.toDouble() / effective.heightPx.toDouble()
        }
}

/**
 * Placement policy for a layer on the canvas.
 *
 * FIT is the default: the whole source is visible, letterboxed/pillarboxed as
 * needed. FILL crops the source to cover the canvas. STRETCH is justified only
 * for future full-bleed backgrounds and graphics drawn for the exact canvas,
 * where aspect distortion is intentional — never as a default.
 */
internal enum class SceneLayoutPolicy {
    FIT,
    FILL,
    STRETCH
}

/**
 * Immutable pixel rectangle, right/bottom exclusive. Width/height are
 * meaningful only when [isValid].
 */
internal data class SceneRectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    val isValid: Boolean get() = right > left && bottom > top

    /** True when this rect lies fully inside [outer] (edges may touch). */
    fun isInside(outer: SceneRectPx): Boolean =
        isValid && outer.isValid &&
            left >= outer.left && top >= outer.top &&
            right <= outer.right && bottom <= outer.bottom
}

/**
 * Layout result: [destRectPx] is the canvas region (pixels, canvas space) the
 * layer occupies; [cropRectPx] is the source region (pixels, effective
 * post-rotation source space) sampled into it.
 */
internal data class SceneLayout(val destRectPx: SceneRectPx, val cropRectPx: SceneRectPx)

/**
 * Deterministic source-to-canvas layout. Returns null for any invalid input
 * (bad canvas, bad source dimensions, non-quarter-turn rotation) — never
 * throws, never clamps silently into a different layout.
 *
 * Scaled dimensions use floored integer math and are coerced to a minimum of
 * one pixel: a valid source always occupies at least one pixel, and a 1px
 * result is always inside its canvas/source because both are >= 1px.
 */
internal object SceneLayoutCalculator {
    fun layout(
        canvas: SceneCanvas,
        source: SceneSourceGeometry,
        policy: SceneLayoutPolicy
    ): SceneLayout? {
        if (!canvas.isValid) return null
        val effective = source.effectiveSizePx() ?: return null
        val canvasW = canvas.widthPx
        val canvasH = canvas.heightPx
        val srcW = effective.widthPx
        val srcH = effective.heightPx
        return when (policy) {
            SceneLayoutPolicy.FIT -> layoutFit(canvasW, canvasH, srcW, srcH)
            SceneLayoutPolicy.FILL -> layoutFill(canvasW, canvasH, srcW, srcH)
            SceneLayoutPolicy.STRETCH -> SceneLayout(
                destRectPx = SceneRectPx(0, 0, canvasW, canvasH),
                cropRectPx = SceneRectPx(0, 0, srcW, srcH)
            )
        }
    }

    private fun layoutFit(canvasW: Int, canvasH: Int, srcW: Int, srcH: Int): SceneLayout {
        // Cross-multiplied in Long: srcW/srcH >= canvasW/canvasH means the
        // source is relatively wider, so canvas width is the binding edge.
        val destW: Int
        val destH: Int
        if (srcW.toLong() * canvasH >= canvasW.toLong() * srcH) {
            destW = canvasW
            destH = (canvasW.toLong() * srcH / srcW).toInt().coerceIn(1, canvasH)
        } else {
            destH = canvasH
            destW = (canvasH.toLong() * srcW / srcH).toInt().coerceIn(1, canvasW)
        }
        val left = (canvasW - destW) / 2
        val top = (canvasH - destH) / 2
        return SceneLayout(
            destRectPx = SceneRectPx(left, top, left + destW, top + destH),
            cropRectPx = SceneRectPx(0, 0, srcW, srcH)
        )
    }

    private fun layoutFill(canvasW: Int, canvasH: Int, srcW: Int, srcH: Int): SceneLayout {
        val cropW: Int
        val cropH: Int
        if (srcW.toLong() * canvasH >= canvasW.toLong() * srcH) {
            cropH = srcH
            cropW = (srcH.toLong() * canvasW / canvasH).toInt().coerceIn(1, srcW)
        } else {
            cropW = srcW
            cropH = (srcW.toLong() * canvasH / canvasW).toInt().coerceIn(1, srcH)
        }
        val left = (srcW - cropW) / 2
        val top = (srcH - cropH) / 2
        return SceneLayout(
            destRectPx = SceneRectPx(0, 0, canvasW, canvasH),
            cropRectPx = SceneRectPx(left, top, left + cropW, top + cropH)
        )
    }
}
