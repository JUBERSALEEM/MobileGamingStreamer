package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic tests for the H3 static text overlay core. Float
 * comparisons use explicit epsilon checks (never 2-arg float assertEquals,
 * which real JUnit rejects without a delta).
 */
class SceneTextLayerTest {

    private fun assertNear(message: String, expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun validSpec() =
        SceneTextLayerSpec.of("MGS", marginPx = 27, maxWidthPx = 1920, alpha = 1f)!!

    // ---- Spec validation ----

    @Test
    fun specAcceptsTypical() {
        val spec = validSpec()
        assertEquals("MGS", spec.text)
        assertEquals(27, spec.marginPx)
        assertEquals(1920, spec.maxWidthPx)
        assertNear("alpha", 1f, spec.alpha)
        assertTrue(spec.enabled)
    }

    @Test
    fun specRejectsBlankText() {
        assertNull(SceneTextLayerSpec.of("", 8, 1920, 1f))
        assertNull(SceneTextLayerSpec.of("   ", 8, 1920, 1f))
    }

    @Test
    fun specRejectsOversizedText() {
        val ok = "x".repeat(MAX_TEXT_LAYER_CHARS)
        assertNotNull(SceneTextLayerSpec.of(ok, 8, 1920, 1f))
        assertNull(SceneTextLayerSpec.of("$ok!", 8, 1920, 1f))
    }

    @Test
    fun specRejectsNegativeMarginAndNonPositiveMaxWidth() {
        assertNull(SceneTextLayerSpec.of("MGS", -1, 1920, 1f))
        assertNull(SceneTextLayerSpec.of("MGS", 8, 0, 1f))
        assertNull(SceneTextLayerSpec.of("MGS", 8, -50, 1f))
    }

    @Test
    fun specRejectsNaNAlphaAndCoercesRange() {
        assertNull(SceneTextLayerSpec.of("MGS", 8, 1920, Float.NaN))
        assertNear("low", 0f, SceneTextLayerSpec.of("MGS", 8, 1920, -0.5f)!!.alpha)
        assertNear("high", 1f, SceneTextLayerSpec.of("MGS", 8, 1920, 2f)!!.alpha)
        assertNear("mid", 0.5f, SceneTextLayerSpec.of("MGS", 8, 1920, 0.5f)!!.alpha)
    }

    @Test
    fun specHonorsEnabledFlag() {
        assertFalse(SceneTextLayerSpec.of("MGS", 8, 1920, 1f, enabled = false)!!.enabled)
    }

    // ---- Metric policy ----

    @Test
    fun textSizeScalesWithHeightAndFloors() {
        assertEquals(0, SceneTextLayerLayout.textSizePxFor(0))
        assertEquals(0, SceneTextLayerLayout.textSizePxFor(-1080))
        assertEquals(24, SceneTextLayerLayout.textSizePxFor(240))
        assertEquals(54, SceneTextLayerLayout.textSizePxFor(1080))
        assertEquals(108, SceneTextLayerLayout.textSizePxFor(2160))
    }

    @Test
    fun marginScalesWithHeightAndFloors() {
        assertEquals(0, SceneTextLayerLayout.marginPxFor(0))
        assertEquals(8, SceneTextLayerLayout.marginPxFor(240))
        assertEquals(27, SceneTextLayerLayout.marginPxFor(1080))
        assertEquals(54, SceneTextLayerLayout.marginPxFor(2160))
    }

    // ---- Layout ----

    @Test
    fun layoutPlacesTextTopLeftAtMargin() {
        val rect = SceneTextLayerLayout.layout(
            SceneCanvas(1920, 1080), SceneSizePx(300, 60), validSpec()
        )!!
        assertEquals(27, rect.left)
        assertEquals(27, rect.top)
        assertEquals(327, rect.right)
        assertEquals(87, rect.bottom)
        assertTrue(rect.isValid)
        assertTrue(rect.isInside(SceneRectPx(0, 0, 1920, 1080)))
    }

    @Test
    fun layoutCapsByMaxWidthAndCanvasRoom() {
        val narrow = SceneTextLayerSpec.of("MGS", 27, 100, 1f)!!
        val capped = SceneTextLayerLayout.layout(
            SceneCanvas(1920, 1080), SceneSizePx(300, 60), narrow
        )!!
        assertEquals(100, capped.width)
        val tiny = SceneTextLayerLayout.layout(
            SceneCanvas(100, 100), SceneSizePx(300, 60),
            SceneTextLayerSpec.of("MGS", 27, 1920, 1f)!!
        )!!
        assertEquals(46, tiny.width)
        assertEquals(46, tiny.height)
    }

    @Test
    fun layoutReturnsNullWhenNothingToDraw() {
        val canvas = SceneCanvas(1920, 1080)
        val measured = SceneSizePx(300, 60)
        assertNull(
            SceneTextLayerLayout.layout(
                canvas, measured,
                SceneTextLayerSpec.of("MGS", 27, 1920, 1f, enabled = false)!!
            )
        )
        assertNull(SceneTextLayerLayout.layout(SceneCanvas(0, 1080), measured, validSpec()))
        assertNull(SceneTextLayerLayout.layout(canvas, SceneSizePx(0, 60), validSpec()))
        assertNull(SceneTextLayerLayout.layout(canvas, SceneSizePx(300, -1), validSpec()))
        assertNull(
            SceneTextLayerLayout.layout(
                SceneCanvas(40, 40), measured,
                SceneTextLayerSpec.of("MGS", 27, 1920, 1f)!!
            )
        )
    }

    // ---- NDC mapping ----

    @Test
    fun ndcQuadMapsFullCanvasToClipSpace() {
        val quad = SceneTextLayerLayout.ndcQuad(SceneRectPx(0, 0, 100, 50), 100, 50)!!
        assertEquals(8, quad.size)
        val expected = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        expected.forEachIndexed { i, v -> assertNear("q[$i]", v, quad[i]) }
    }

    @Test
    fun ndcQuadFlipsPixelYToGlY() {
        // Top-left quarter of a 200x100 frame: NDC x in [-1,0], y in [0,1].
        val quad = SceneTextLayerLayout.ndcQuad(SceneRectPx(0, 0, 100, 50), 200, 100)!!
        val expected = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 1f, 0f, 1f)
        expected.forEachIndexed { i, v -> assertNear("q[$i]", v, quad[i]) }
    }

    @Test
    fun ndcQuadRejectsInvalidGeometry() {
        assertNull(SceneTextLayerLayout.ndcQuad(SceneRectPx(10, 10, 10, 20), 100, 100))
        assertNull(SceneTextLayerLayout.ndcQuad(SceneRectPx(0, 0, 10, 10), 0, 100))
        assertNull(SceneTextLayerLayout.ndcQuad(SceneRectPx(0, 0, 10, 10), 100, -1))
    }

    // ---- Ordering ----

    @Test
    fun compositionOrdersScreenBehindText() {
        val order = SceneTextLayerLayout.compositionFor(textEnabled = true).layersInDrawOrder
        assertEquals(2, order.size)
        assertEquals(SCREEN_LAYER_ID, order[0].id)
        assertEquals(TEXT_LAYER_ID, order[1].id)
        assertTrue(order[0].zOrder < order[1].zOrder)
    }

    @Test
    fun disabledTextReducesVisibleCompositionToPassthrough() {
        val composition = SceneTextLayerLayout.compositionFor(textEnabled = false)
        assertEquals(2, composition.size)
        val visible = composition.visibleLayersInDrawOrder
        assertEquals(1, visible.size)
        assertEquals(SCREEN_LAYER_ID, visible[0].id)
        val enabled = SceneTextLayerLayout.compositionFor(textEnabled = true)
        assertEquals(2, enabled.visibleLayersInDrawOrder.size)
    }
}
