package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H4 static multi-layer overlay stack: pure resolution core. The Android/GL
 * shell (measure with `Paint`, raster to `Bitmap`, upload + draw in
 * `SceneCompositorGL`) executes; everything decidable — spec validity,
 * placement, bounds, alpha, NDC mapping, layer ordering — lives here,
 * unit-tested, with no Android types, threads, or I/O.
 *
 * Fixed H4 policy (pinned by tests, not caller convention):
 * - The stack holds at most [MAX_STATIC_OVERLAY_LAYERS] layers; more is an
 *   invalid stack (loud null → shell start failure), never silent trimming.
 * - `TOP_LEFT` placement delegates to [SceneTextLayerLayout.layout], so the
 *   H3 single-layer rect is reproduced exactly (equivalence pinned by test).
 * - Disabled layers are ignored before composition: they occupy no slot
 *   and can never fail the stack.
 * - Ordering is total and deterministic: the draw list follows
 *   [SceneComposition.visibleLayersInDrawOrder] over (screen z=0 + overlay
 *   layers), so duplicate ids fail the whole stack via the H0 backstop.
 * - No placement can produce an out-of-bounds rect (proven in `placeRect`).
 */
internal const val MAX_STATIC_OVERLAY_LAYERS = 4

/** Corner/center anchors for static overlay placement. Public: user-facing
 * editor configuration crosses public composable boundaries. */
enum class SceneOverlayAnchor {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER
}

/**
 * Validated static-overlay specification (H4 content is text only; other
 * content kinds are an H5 decision). [of] returns null (never throws) for
 * blank ids/text, oversized text, negative z-order/margins, non-positive
 * max width, or NaN alpha; [alpha] is coerced into [0,1].
 */
internal data class SceneOverlaySpec(
    val id: String,
    val text: String,
    val anchor: SceneOverlayAnchor,
    val zOrder: Int,
    val marginPx: Int,
    val maxWidthPx: Int,
    val alpha: Float,
    val enabled: Boolean = true,
    val kind: SceneLayerKind = SceneLayerKind.TEXT,
    val imageUri: String? = null,
    val textColor: Int = TEXT_COLOR_DEFAULT,
    val backgroundColor: Int? = null,
    val borderWidthPx: Int = 0,
    val borderColor: Int = BORDER_COLOR_DEFAULT,
    val cornerRadiusPx: Int = 0,
    val shadowOffsetPx: Int = 0,
    val shadowColor: Int = SHADOW_COLOR_DEFAULT
) {
    companion object {
        fun of(
            id: String,
            text: String,
            anchor: SceneOverlayAnchor,
            zOrder: Int,
            marginPx: Int,
            maxWidthPx: Int,
            alpha: Float,
            enabled: Boolean = true,
            kind: SceneLayerKind = SceneLayerKind.TEXT,
            imageUri: String? = null,
            textColor: Int = TEXT_COLOR_DEFAULT,
            backgroundColor: Int? = null,
            borderWidthPx: Int = 0,
            borderColor: Int = BORDER_COLOR_DEFAULT,
            cornerRadiusPx: Int = 0,
            shadowOffsetPx: Int = 0,
            shadowColor: Int = SHADOW_COLOR_DEFAULT
        ): SceneOverlaySpec? {
            if (id.isBlank()) return null
            when (kind) {
                SceneLayerKind.TEXT -> {
                    if (text.isBlank()) return null
                    if (text.length > MAX_TEXT_LAYER_CHARS) return null
                }
                SceneLayerKind.IMAGE -> {
                    if (!isValidImageUri(imageUri)) return null
                }
                // H9 widgets carry no content: time is rendered, not stored.
                SceneLayerKind.CLOCK, SceneLayerKind.TIMER -> { }
            }
            if (zOrder < 0) return null
            if (marginPx < 0) return null
            if (maxWidthPx <= 0) return null
            if (alpha.isNaN()) return null
            if (borderWidthPx < 0 || borderWidthPx > MAX_STYLE_BORDER_PX) return null
            if (cornerRadiusPx < 0 || cornerRadiusPx > MAX_STYLE_RADIUS_PX) return null
            if (shadowOffsetPx < 0 || shadowOffsetPx > MAX_STYLE_SHADOW_PX) return null
            return SceneOverlaySpec(
                id, if (kind == SceneLayerKind.TEXT) text else "", anchor, zOrder, marginPx, maxWidthPx,
                alpha.coerceIn(0f, 1f), enabled, kind,
                if (kind == SceneLayerKind.IMAGE) imageUri else null,
                textColor, backgroundColor, borderWidthPx, borderColor,
                cornerRadiusPx, shadowOffsetPx, shadowColor
            )
        }
    }
}

/** One overlay spec plus its shell-measured raster size in pixels. */
internal data class SceneOverlayLayerInput(
    val spec: SceneOverlaySpec,
    val measuredPx: SceneSizePx
)

/** Resolved draw entry: back-to-front order, canvas-clamped rect + NDC. */
internal data class SceneOverlayDrawEntry(
    val id: String,
    val zOrder: Int,
    val rectPx: SceneRectPx,
    val ndcQuad: FloatArray,
    val alpha: Float
)

/** Deterministic overlay-stack resolution. All pure. */
internal object SceneOverlayStack {
    /**
     * Destination rect for one overlay on [canvas]. Text `TOP_LEFT` delegates
     * to the H3 placement (identical output); image top-left uses the
     * generic branch (arithmetically identical for top-left, pinned by
     * test); other anchors reuse the same
     * width/height capping, then place against the canvas-inset edges (or
     * centered with floored bias: a leftover pixel goes right/bottom,
     * matching [SceneLayoutCalculator]). Null when the canvas or
     * measurement is invalid or the margins leave no room.
     *
     * Bounds proof for non-delegated anchors: capped width ≤ roomW, so a
     * right-placed left edge (canvasW − margin − width) is ≥ margin and
     * its right edge is exactly canvasW − margin; centered left is ≥
     * margin with right ≤ canvasW − margin. Same vertically. The rect is
     * therefore always inside the canvas.
     */
    fun placeRect(
        canvas: SceneCanvas,
        measuredPx: SceneSizePx,
        spec: SceneOverlaySpec
    ): SceneRectPx? {
        if (spec.anchor == SceneOverlayAnchor.TOP_LEFT && spec.kind == SceneLayerKind.TEXT) {
            val textSpec = SceneTextLayerSpec.of(
                text = spec.text,
                marginPx = spec.marginPx,
                maxWidthPx = spec.maxWidthPx,
                alpha = spec.alpha
            ) ?: return null
            return SceneTextLayerLayout.layout(canvas, measuredPx, textSpec)
        }
        if (!canvas.isValid) return null
        if (measuredPx.widthPx <= 0 || measuredPx.heightPx <= 0) return null
        val roomW = canvas.widthPx - spec.marginPx * 2
        val roomH = canvas.heightPx - spec.marginPx * 2
        if (roomW <= 0 || roomH <= 0) return null
        val width = minOf(measuredPx.widthPx, spec.maxWidthPx, roomW)
        val height = minOf(measuredPx.heightPx, roomH)
        if (width <= 0 || height <= 0) return null
        val margin = spec.marginPx
        val left = when (spec.anchor) {
            SceneOverlayAnchor.TOP_LEFT -> margin // Unreachable (delegated above).
            SceneOverlayAnchor.TOP_RIGHT,
            SceneOverlayAnchor.BOTTOM_RIGHT -> canvas.widthPx - margin - width
            SceneOverlayAnchor.BOTTOM_LEFT -> margin
            SceneOverlayAnchor.CENTER -> margin + (roomW - width) / 2
        }
        val top = when (spec.anchor) {
            SceneOverlayAnchor.TOP_LEFT,
            SceneOverlayAnchor.TOP_RIGHT -> margin // TOP_LEFT unreachable (delegated above).
            SceneOverlayAnchor.BOTTOM_LEFT,
            SceneOverlayAnchor.BOTTOM_RIGHT -> canvas.heightPx - margin - height
            SceneOverlayAnchor.CENTER -> margin + (roomH - height) / 2
        }
        return SceneRectPx(left, top, left + width, top + height)
    }

    /**
     * Resolves the static overlay stack to a back-to-front draw list, or
     * null when the stack is invalid (bad canvas, over the layer cap,
     * invalid measurement, unplaceable layer, duplicate ids). Disabled
     * layers are dropped first and can never fail the stack. Empty input
     * (or all disabled) resolves to the empty list — passthrough, not an
     * error. The shell calls this once at init, never per frame.
     */
    fun resolve(
        canvas: SceneCanvas,
        layers: List<SceneOverlayLayerInput>
    ): List<SceneOverlayDrawEntry>? {
        if (!canvas.isValid) return null
        val active = layers.filter { it.spec.enabled }
        if (active.size > MAX_STATIC_OVERLAY_LAYERS) return null
        val placed = active.map { input ->
            val rect = placeRect(canvas, input.measuredPx, input.spec) ?: return null
            val quad = SceneTextLayerLayout.ndcQuad(rect, canvas.widthPx, canvas.heightPx)
                ?: return null
            SceneOverlayDrawEntry(
                id = input.spec.id,
                zOrder = input.spec.zOrder,
                rectPx = rect,
                ndcQuad = quad,
                alpha = input.spec.alpha
            )
        }
        val compositionLayers = active.map { input ->
            SceneLayer(id = input.spec.id, sourceKey = input.spec.id, zOrder = input.spec.zOrder)
        } + SceneLayer(id = SCREEN_LAYER_ID, sourceKey = SCREEN_LAYER_ID, zOrder = 0)
        // Duplicate overlay ids (or a collision with "screen") fail the
        // whole stack here — the H0 validation backstop, genuinely
        // runtime-consumed for the first time.
        val composition = SceneComposition.of(compositionLayers) ?: return null
        val order = composition.visibleLayersInDrawOrder
            .filter { it.id != SCREEN_LAYER_ID }
            .map { it.id }
        val byId = placed.associateBy { it.id }
        return order.map { id -> byId.getValue(id) }
    }
}
