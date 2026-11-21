package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Preview-math tests: the editor canvas reuses H4 placement exactly, so
 * the preview cannot drift from the renderer. Float comparisons use
 * explicit epsilon checks.
 */
class ScenePreviewBoxesTest {

    private fun assertNear(message: String, expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun spec(
        id: String = "text",
        anchor: SceneOverlayAnchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder: Int = 1,
        alpha: Float = 1f,
        enabled: Boolean = true
    ): SceneOverlaySpec =
        SceneOverlaySpec.of(id, "MGS", anchor, zOrder, 48, 1920, alpha, enabled)!!

    private fun input(spec: SceneOverlaySpec, w: Int = 200, h: Int = 40) =
        SceneOverlayLayerInput(spec, SceneSizePx(w, h))

    @Test
    fun boxesMatchH4PlacementExactly() {
        val layers = listOf(
            input(spec(id = "a", anchor = SceneOverlayAnchor.TOP_LEFT)),
            input(spec(id = "b", anchor = SceneOverlayAnchor.BOTTOM_RIGHT, zOrder = 2))
        )
        val boxes = ScenePreviewBoxes.layout(1920, 1080, layers)!!
        assertEquals(2, boxes.size)
        for ((box, layer) in boxes.zip(layers)) {
            val rect = SceneOverlayStack.placeRect(
                SceneCanvas(1920, 1080), layer.measuredPx, layer.spec
            )!!
            assertNear("${box.id} left", rect.left.toFloat() / 1920f, box.left)
            assertNear("${box.id} top", rect.top.toFloat() / 1080f, box.top)
            assertNear("${box.id} right", rect.right.toFloat() / 1920f, box.right)
            assertNear("${box.id} bottom", rect.bottom.toFloat() / 1080f, box.bottom)
        }
    }

    @Test
    fun boxesFollowDrawOrderAndCarryAlpha() {
        val boxes = ScenePreviewBoxes.layout(
            1920, 1080,
            listOf(
                input(spec(id = "top", zOrder = 2, alpha = 0.5f)),
                input(spec(id = "bottom", zOrder = 1))
            )
        )!!
        assertEquals(listOf("bottom", "top"), boxes.map { it.id })
        assertEquals(0.5f, boxes[1].alpha)
        assertTrue(boxes.all { it.enabled })
    }

    @Test
    fun disabledLayersBecomeGhosts() {
        val boxes = ScenePreviewBoxes.layout(
            1920, 1080,
            listOf(
                input(spec(id = "on")),
                input(spec(id = "off", enabled = false))
            )
        )!!
        assertEquals(2, boxes.size)
        assertEquals("on", boxes[0].id)
        assertTrue(boxes[0].enabled)
        assertEquals("off", boxes[1].id)
        assertTrue(!boxes[1].enabled)
    }

    @Test
    fun invalidCanvasOrStackReturnsNull() {
        assertNull(ScenePreviewBoxes.layout(0, 1080, listOf(input(spec()))))
        assertNull(ScenePreviewBoxes.layout(1920, -1, listOf(input(spec()))))
        val dup = listOf(input(spec(id = "dup")), input(spec(id = "dup")))
        assertNull(ScenePreviewBoxes.layout(1920, 1080, dup))
    }

    @Test
    fun emptyStackIsEmptyPreview() {
        assertEquals(0, ScenePreviewBoxes.layout(1920, 1080, emptyList())!!.size)
    }
}
