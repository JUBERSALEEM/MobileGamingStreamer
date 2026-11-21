package com.jubersaleem.mobilegamingstreamer.scene

/**
 * Phase I design orientation of a scene canvas. Public: carried by
 * the public UI-held model and persisted in the v4 codec header.
 * SQUARE is deliberately excluded: scenes are designed for one of
 * the two stream shapes (square outputs still compose through the
 * orientation-agnostic resolve).
 */
enum class SceneDesignOrientation {
    PORTRAIT,
    LANDSCAPE
}

/** Landscape reference canvas: 1080p encoder dims. */
internal const val LANDSCAPE_REF_WIDTH_PX = 1920
internal const val LANDSCAPE_REF_HEIGHT_PX = 1080

/** Portrait reference canvas: 1080p encoder dims. */
internal const val PORTRAIT_REF_WIDTH_PX = 1080
internal const val PORTRAIT_REF_HEIGHT_PX = 1920

/**
 * Phase I single home for orientation-to-canvas mapping. The editor
 * preview measures against [referenceCanvas]; the renderer resolves
 * the same specs on the output canvas. At 1080p reference == output,
 * so preview boxes == renderer rects pixel-for-pixel.
 */
internal object SceneCanvasProfile {
    fun referenceCanvas(orientation: SceneDesignOrientation): SceneCanvas =
        when (orientation) {
            SceneDesignOrientation.LANDSCAPE ->
                SceneCanvas(LANDSCAPE_REF_WIDTH_PX, LANDSCAPE_REF_HEIGHT_PX)
            SceneDesignOrientation.PORTRAIT ->
                SceneCanvas(PORTRAIT_REF_WIDTH_PX, PORTRAIT_REF_HEIGHT_PX)
        }

    /**
     * Design orientation matching [canvas], or null when the canvas
     * is invalid or square (square matches neither design shape).
     */
    fun orientationOf(canvas: SceneCanvas): SceneDesignOrientation? =
        when (canvas.orientation) {
            SceneOrientation.LANDSCAPE -> SceneDesignOrientation.LANDSCAPE
            SceneOrientation.PORTRAIT -> SceneDesignOrientation.PORTRAIT
            else -> null
        }
}
