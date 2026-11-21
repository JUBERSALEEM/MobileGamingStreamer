package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic tests for the H4 static multi-layer overlay stack:
 * ordering, anchored placement, bounds/clamping, visibility/alpha,
 * invalid configurations, the layer cap, and H3 single-layer
 * equivalence. Float comparisons use explicit epsilon checks (never
 * 2-arg float assertEquals, which real JUnit rejects without a delta).
 */
class SceneOverlayStackTest {

    private fun assertNear(message: String, expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun assertQuadsNear(message: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$message size", expected.size, actual.size)
        for (i in expected.indices) {
            assertNear("$message [$i]", expected[i], actual[i])
        }
    }

    private fun spec(
        id: String = "text",
        text: String = "MGS",
        anchor: SceneOverlayAnchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder: Int = 1,
        marginPx: Int = 48,
        maxWidthPx: Int = 1080,
        alpha: Float = 1f,
        enabled: Boolean = true
    ): SceneOverlaySpec =
        SceneOverlaySpec.of(id, text, anchor, zOrder, marginPx, maxWidthPx, alpha, enabled)!!

    private fun input(spec: SceneOverlaySpec, w: Int = 200, h: Int = 40) =
        SceneOverlayLayerInput(spec, SceneSizePx(w, h))

    private val canvas = SceneCanvas(1080, 1920)

    // ---- Spec validation ----

    @Test
    fun specAcceptsTypical() {
        val parsed = spec(id = "sub", anchor = SceneOverlayAnchor.BOTTOM_LEFT, zOrder = 2)
        assertEquals("sub", parsed.id)
        assertEquals("MGS", parsed.text)
        assertEquals(SceneOverlayAnchor.BOTTOM_LEFT, parsed.anchor)
        assertEquals(2, parsed.zOrder)
        assertEquals(48, parsed.marginPx)
        assertEquals(1f, parsed.alpha, 0.001f)
        assertTrue(parsed.enabled)
    }

    @Test
    fun specRejectsBlankIdAndText() {
        assertNull(SceneOverlaySpec.of("", "MGS", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 1f))
        assertNull(SceneOverlaySpec.of("  ", "MGS", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 1f))
        assertNull(SceneOverlaySpec.of("t", "", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 1f))
        assertNull(SceneOverlaySpec.of("t", "   ", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 1f))
    }

    @Test
    fun specRejectsOversizedTextAndNegativeZ() {
        val long = "x".repeat(MAX_TEXT_LAYER_CHARS + 1)
        assertNull(SceneOverlaySpec.of("t", long, SceneOverlayAnchor.TOP_LEFT, 1, 8, 2000, 1f))
        assertNull(SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, -1, 8, 100, 1f))
        assertNotNull(
            SceneOverlaySpec.of(
                "t", "x".repeat(MAX_TEXT_LAYER_CHARS),
                SceneOverlayAnchor.TOP_LEFT, 0, 8, 2000, 1f
            )
        )
    }

    @Test
    fun specRejectsBadGeometryAndCoercesAlpha() {
        assertNull(SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, -1, 100, 1f))
        assertNull(SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, 8, 0, 1f))
        assertNull(
            SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, Float.NaN)
        )
        assertEquals(
            1f,
            SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 2f)!!.alpha,
            0.001f
        )
        assertEquals(
            0f,
            SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, -0.5f)!!.alpha,
            0.001f
        )
        assertEquals(
            0.5f,
            SceneOverlaySpec.of("t", "ok", SceneOverlayAnchor.TOP_LEFT, 1, 8, 100, 0.5f)!!.alpha,
            0.001f
        )
    }

    // ---- Placement ----

    @Test
    fun topLeftDelegatesToH3Layout() {
        // H4/H3 equivalence: identical inputs must produce the identical
        // rect, so the H3 single-layer output cannot drift.
        val overlay = spec()
        val h3Spec = SceneTextLayerSpec.of("MGS", marginPx = 48, maxWidthPx = 1080, alpha = 1f)!!
        val measured = SceneSizePx(200, 40)
        assertEquals(
            SceneTextLayerLayout.layout(canvas, measured, h3Spec),
            SceneOverlayStack.placeRect(canvas, measured, overlay)
        )
    }

    @Test
    fun cornersPlaceAgainstInsetEdges() {
        val measured = SceneSizePx(200, 40)
        assertEquals(
            SceneRectPx(1080 - 48 - 200, 48, 1080 - 48, 48 + 40),
            SceneOverlayStack.placeRect(canvas, measured, spec(anchor = SceneOverlayAnchor.TOP_RIGHT))
        )
        assertEquals(
            SceneRectPx(48, 1920 - 48 - 40, 48 + 200, 1920 - 48),
            SceneOverlayStack.placeRect(canvas, measured, spec(anchor = SceneOverlayAnchor.BOTTOM_LEFT))
        )
        assertEquals(
            SceneRectPx(1080 - 48 - 200, 1920 - 48 - 40, 1080 - 48, 1920 - 48),
            SceneOverlayStack.placeRect(canvas, measured, spec(anchor = SceneOverlayAnchor.BOTTOM_RIGHT))
        )
    }

    @Test
    fun centerFloorsRemainderToRightAndBottom() {
        // Room 84px, content 11px: left offset floors to 36, leaving 37 on
        // the right — the leftover pixel goes right/bottom, matching the
        // SceneLayoutCalculator remainder rule.
        val small = SceneCanvas(100, 100)
        val rect = SceneOverlayStack.placeRect(
            small, SceneSizePx(11, 11),
            spec(anchor = SceneOverlayAnchor.CENTER, marginPx = 8, maxWidthPx = 100)
        )!!
        assertEquals(SceneRectPx(44, 44, 55, 55), rect)
    }

    @Test
    fun placementCapsByMaxWidthAndCanvasRoom() {
        val wide = SceneSizePx(2000, 40)
        val capped = SceneOverlayStack.placeRect(
            canvas, wide,
            spec(anchor = SceneOverlayAnchor.BOTTOM_RIGHT, maxWidthPx = 500)
        )!!
        assertEquals(500, capped.width)
        assertEquals(1080 - 48, capped.right)
        // Canvas room wins over maxWidth when the inset is tighter.
        val tight = SceneOverlayStack.placeRect(
            SceneCanvas(300, 100), wide,
            spec(anchor = SceneOverlayAnchor.CENTER, marginPx = 8, maxWidthPx = 2000)
        )!!
        assertEquals(284, tight.width)
        assertTrue(tight.isInside(SceneRectPx(0, 0, 300, 100)))
    }

    @Test
    fun placementStaysInsideCanvasForAllAnchors() {
        val outer = SceneRectPx(0, 0, canvas.widthPx, canvas.heightPx)
        for (anchor in SceneOverlayAnchor.values()) {
            val rect = SceneOverlayStack.placeRect(canvas, SceneSizePx(200, 40), spec(anchor = anchor))
            assertTrue("anchor $anchor placed", rect != null)
            assertTrue("anchor $anchor inside", rect!!.isInside(outer))
        }
    }

    @Test
    fun placeRectNullWhenNothingFits() {
        val overlay = spec(anchor = SceneOverlayAnchor.BOTTOM_RIGHT)
        assertNull(SceneOverlayStack.placeRect(SceneCanvas(16, 16), SceneSizePx(200, 40), overlay))
        assertNull(SceneOverlayStack.placeRect(SceneCanvas(0, 100), SceneSizePx(200, 40), overlay))
        assertNull(SceneOverlayStack.placeRect(canvas, SceneSizePx(0, 40), overlay))
        assertNull(SceneOverlayStack.placeRect(canvas, SceneSizePx(200, -1), overlay))
    }

    // ---- Resolution ----

    @Test
    fun resolveOrdersByZThenId() {
        val entries = SceneOverlayStack.resolve(
            canvas,
            listOf(
                input(spec(id = "c", zOrder = 2)),
                input(spec(id = "b", zOrder = 1)),
                input(spec(id = "a", zOrder = 1))
            )
        )!!
        assertEquals(listOf("a", "b", "c"), entries.map { it.id })
        assertEquals(listOf(1, 1, 2), entries.map { it.zOrder })
    }

    @Test
    fun resolveDropsDisabledAndEmptyIsPassthrough() {
        val entries = SceneOverlayStack.resolve(
            canvas,
            listOf(
                input(spec(id = "on")),
                input(spec(id = "off", enabled = false))
            )
        )!!
        assertEquals(listOf("on"), entries.map { it.id })
        assertEquals(0, SceneOverlayStack.resolve(canvas, emptyList())!!.size)
        assertEquals(
            0,
            SceneOverlayStack.resolve(canvas, listOf(input(spec(enabled = false))))!!.size
        )
    }

    @Test
    fun resolveRejectsOverCap() {
        val five = (0 until MAX_STATIC_OVERLAY_LAYERS + 1).map { i -> input(spec(id = "t$i")) }
        assertNull(SceneOverlayStack.resolve(canvas, five))
        val four = (0 until MAX_STATIC_OVERLAY_LAYERS).map { i -> input(spec(id = "t$i")) }
        assertEquals(MAX_STATIC_OVERLAY_LAYERS, SceneOverlayStack.resolve(canvas, four)!!.size)
    }

    @Test
    fun resolveRejectsDuplicateIds() {
        assertNull(
            SceneOverlayStack.resolve(
                canvas, listOf(input(spec(id = "dup")), input(spec(id = "dup")))
            )
        )
        // Colliding with the screen layer id fails via the same backstop.
        assertNull(
            SceneOverlayStack.resolve(
                canvas, listOf(input(spec(id = SCREEN_LAYER_ID)))
            )
        )
    }

    @Test
    fun resolveRejectsUnplaceableStack() {
        assertNull(
            SceneOverlayStack.resolve(
                SceneCanvas(16, 16),
                listOf(input(spec()), input(spec(id = "other")))
            )
        )
        assertNull(SceneOverlayStack.resolve(SceneCanvas(-4, 100), listOf(input(spec()))))
    }

    @Test
    fun resolveCarriesAlphaAndNdc() {
        val entries = SceneOverlayStack.resolve(
            canvas, listOf(input(spec(alpha = 0.5f)))
        )!!
        assertEquals(1, entries.size)
        assertEquals(0.5f, entries[0].alpha, 0.001f)
        assertQuadsNear(
            "ndc",
            SceneTextLayerLayout.ndcQuad(entries[0].rectPx, 1080, 1920)!!,
            entries[0].ndcQuad
        )
    }

    @Test
    fun resolveIsDeterministic() {
        val layers = listOf(
            input(spec(id = "b", anchor = SceneOverlayAnchor.BOTTOM_LEFT, zOrder = 2)),
            input(spec(id = "a", anchor = SceneOverlayAnchor.TOP_RIGHT, zOrder = 1))
        )
        val first = SceneOverlayStack.resolve(canvas, layers)!!
        val second = SceneOverlayStack.resolve(canvas, layers)!!
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.rectPx }, second.map { it.rectPx })
        assertQuadsNear("run1vs2", first[0].ndcQuad, second[0].ndcQuad)
        assertQuadsNear("run1vs2", first[1].ndcQuad, second[1].ndcQuad)
    }

    @Test
    fun h3SingleLayerEquivalence() {
        // One TOP_LEFT layer through the H4 stack resolves to the exact H3
        // rect and the [screen, text] composition order.
        val h3Spec = SceneTextLayerSpec.of("MGS", marginPx = 48, maxWidthPx = 1080, alpha = 1f)!!
        val measured = SceneSizePx(200, 40)
        val entries = SceneOverlayStack.resolve(canvas, listOf(input(spec())))!!
        assertEquals(1, entries.size)
        assertEquals(SceneTextLayerLayout.layout(canvas, measured, h3Spec), entries[0].rectPx)
        val composition = SceneTextLayerLayout.compositionFor(textEnabled = true)
        assertEquals(
            listOf(SCREEN_LAYER_ID, TEXT_LAYER_ID),
            composition.visibleLayersInDrawOrder.map { it.id }
        )
    }

    @Test
    fun imageTopLeftMatchesH3LayoutExactly() {
        // H6: image top-left uses the generic branch; it must equal the
        // H3-delegated text placement for identical inputs.
        val canvas = SceneCanvas(1920, 1080)
        val measured = SceneSizePx(200, 40)
        val text = SceneOverlaySpec.of("t", "MGS", SceneOverlayAnchor.TOP_LEFT, 1, 48, 1920, 1f)!!
        val image = SceneOverlaySpec.of(
            "img", "", SceneOverlayAnchor.TOP_LEFT, 1, 48, 1920, 1f, true,
            SceneLayerKind.IMAGE, "content://media/ok"
        )!!
        assertEquals(
            SceneOverlayStack.placeRect(canvas, measured, text),
            SceneOverlayStack.placeRect(canvas, measured, image)
        )
    }
}
