package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H5 preview math: normalized overlay boxes for the editor's 16:9 canvas.
 * Pure Kotlin (the composable measures text with `Paint`, then calls
 * here). Placement reuses [SceneOverlayStack] exactly, so the preview
 * cannot drift from the renderer's layout rules: enabled layers resolve
 * through the same [SceneOverlayStack.resolve]; disabled layers get
 * individual ghost boxes via [SceneOverlayStack.placeRect] (shown as
 * outlines, never streamed).
 */
internal data class ScenePreviewBox(
    val id: String,
    val zOrder: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val alpha: Float,
    val enabled: Boolean
)

internal object ScenePreviewBoxes {
    /**
     * Normalized (0..1) boxes for [layers] on a [canvasWidthPx]x
     * [canvasHeightPx] canvas, back-to-front for enabled layers with
     * disabled ghosts appended. Null when the canvas is invalid or the
     * enabled stack fails H4 resolution (the preview then shows an error
     * state instead of a wrong composition).
     */
    fun layout(
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        layers: List<SceneOverlayLayerInput>
    ): List<ScenePreviewBox>? {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) return null
        val canvas = SceneCanvas(canvasWidthPx, canvasHeightPx)
        if (!canvas.isValid) return null
        val enabled = layers.filter { it.spec.enabled }
        val entries = SceneOverlayStack.resolve(canvas, enabled) ?: return null
        val boxes = entries.map { entry ->
            ScenePreviewBox(
                id = entry.id,
                zOrder = entry.zOrder,
                left = entry.rectPx.left.toFloat() / canvasWidthPx,
                top = entry.rectPx.top.toFloat() / canvasHeightPx,
                right = entry.rectPx.right.toFloat() / canvasWidthPx,
                bottom = entry.rectPx.bottom.toFloat() / canvasHeightPx,
                alpha = entry.alpha,
                enabled = true
            )
        }
        val ghosts = layers.filter { !it.spec.enabled }.mapNotNull { input ->
            val rect = SceneOverlayStack.placeRect(canvas, input.measuredPx, input.spec)
                ?: return@mapNotNull null
            ScenePreviewBox(
                id = input.spec.id,
                zOrder = input.spec.zOrder,
                left = rect.left.toFloat() / canvasWidthPx,
                top = rect.top.toFloat() / canvasHeightPx,
                right = rect.right.toFloat() / canvasWidthPx,
                bottom = rect.bottom.toFloat() / canvasHeightPx,
                alpha = input.spec.alpha,
                enabled = false
            )
        }
        return boxes + ghosts.sortedWith(compareBy({ it.zOrder }, { it.id }))
    }
}
