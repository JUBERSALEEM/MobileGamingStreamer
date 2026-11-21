package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I canvas contract: reference dimensions, aspect
 * preservation, orientation mapping, in-bounds placement on both
 * canvases, and preview/renderer parity (same dims ⇒ identical
 * normalized boxes — the preview IS the renderer resolve).
 */
class SceneCanvasProfileTest {

    @Test
    fun referenceDimensionsAre1080pBothWays() {
        assertEquals(
            SceneCanvas(1920, 1080),
            SceneCanvasProfile.referenceCanvas(SceneDesignOrientation.LANDSCAPE)
        )
        assertEquals(
            SceneCanvas(1080, 1920),
            SceneCanvasProfile.referenceCanvas(SceneDesignOrientation.PORTRAIT)
        )
    }

    @Test
    fun aspectsPreserved() {
        // 16:9 landscape, 9:16 portrait — cross-multiplied, exact.
        val land = SceneCanvasProfile.referenceCanvas(SceneDesignOrientation.LANDSCAPE)
        assertEquals(16 * land.heightPx, 9 * land.widthPx)
        val port = SceneCanvasProfile.referenceCanvas(SceneDesignOrientation.PORTRAIT)
        assertEquals(9 * port.heightPx, 16 * port.widthPx)
    }

    @Test
    fun orientationOfMapsCanvases() {
        assertEquals(
            SceneDesignOrientation.LANDSCAPE,
            SceneCanvasProfile.orientationOf(SceneCanvas(1920, 1080))
        )
        assertEquals(
            SceneDesignOrientation.PORTRAIT,
            SceneCanvasProfile.orientationOf(SceneCanvas(1080, 1920))
        )
        // Encoder-real outputs at other tiers map the same way.
        assertEquals(
            SceneDesignOrientation.PORTRAIT,
            SceneCanvasProfile.orientationOf(SceneCanvas(720, 1280))
        )
        assertEquals(
            SceneDesignOrientation.LANDSCAPE,
            SceneCanvasProfile.orientationOf(SceneCanvas(2560, 1440))
        )
    }

    @Test
    fun orientationOfRejectsSquareAndInvalid() {
        assertNull(SceneCanvasProfile.orientationOf(SceneCanvas(1080, 1080)))
        assertNull(SceneCanvasProfile.orientationOf(SceneCanvas(0, 1080)))
        assertNull(SceneCanvasProfile.orientationOf(SceneCanvas(1920, -1)))
        assertNull(SceneCanvasProfile.orientationOf(SceneCanvas(20000, 1080)))
    }

    private fun specFor(anchor: SceneOverlayAnchor, kind: SceneLayerKind, id: String): SceneOverlaySpec =
        SceneOverlaySpec.of(
            id, if (kind == SceneLayerKind.TEXT) "Parity $id" else "", anchor,
            1, 27, 1920, 1f, true, kind,
            if (kind == SceneLayerKind.IMAGE) "content://x/$id" else null
        )!!

    @Test
    fun everyAnchorAndKindResolvesInBoundsBothOrientations() {
        for (orientation in SceneDesignOrientation.values()) {
            val canvas = SceneCanvasProfile.referenceCanvas(orientation)
            val outer = SceneRectPx(0, 0, canvas.widthPx, canvas.heightPx)
            for (anchor in SceneOverlayAnchor.values()) {
                for (kind in SceneLayerKind.values()) {
                    val spec = specFor(anchor, kind, "${anchor.name}-${kind.name}")
                    val rect = SceneOverlayStack.placeRect(
                        canvas, SceneSizePx(320, 96), spec
                    )
                    assertTrue(
                        "$orientation $anchor $kind",
                        rect != null && rect.isInside(outer)
                    )
                }
            }
        }
    }

    @Test
    fun previewMatchesRendererOnIdenticalCanvas() {
        // Parity core, both orientations: preview normalization of a
        // resolve == renderer rects on the same dims (mixed stack of
        // text, image, and widget, distinct z for a pinned order).
        for (orientation in SceneDesignOrientation.values()) {
            val canvas = SceneCanvasProfile.referenceCanvas(orientation)
            val inputs = listOf(
                SceneOverlayLayerInput(
                    specFor(SceneOverlayAnchor.TOP_LEFT, SceneLayerKind.TEXT, "t")
                        .copy(zOrder = 1),
                    SceneSizePx(320, 96)
                ),
                SceneOverlayLayerInput(
                    specFor(SceneOverlayAnchor.BOTTOM_RIGHT, SceneLayerKind.IMAGE, "i")
                        .copy(zOrder = 2),
                    SceneSizePx(400, 300)
                ),
                SceneOverlayLayerInput(
                    specFor(SceneOverlayAnchor.CENTER, SceneLayerKind.CLOCK, "c")
                        .copy(zOrder = 3),
                    SceneSizePx(200, 80)
                )
            )
            val rendered = SceneOverlayStack.resolve(canvas, inputs)!!
            val preview = ScenePreviewBoxes.layout(canvas.widthPx, canvas.heightPx, inputs)!!
            assertEquals(listOf("t", "i", "c"), rendered.map { it.id })
            assertEquals(
                rendered.map { it.id },
                preview.filter { it.enabled }.map { it.id }
            )
            for (entry in rendered) {
                val box = preview.first { it.id == entry.id }
                assertEquals(entry.rectPx.left.toFloat() / canvas.widthPx, box.left)
                assertEquals(entry.rectPx.top.toFloat() / canvas.heightPx, box.top)
                assertEquals(entry.rectPx.right.toFloat() / canvas.widthPx, box.right)
                assertEquals(entry.rectPx.bottom.toFloat() / canvas.heightPx, box.bottom)
                assertEquals(entry.alpha, box.alpha)
            }
        }
    }

    @Test
    fun oversizedLayersCapWithoutLeavingPortraitCanvas() {
        // Landscape-tuned geometry on a portrait canvas: capped, kept.
        val canvas = SceneCanvasProfile.referenceCanvas(SceneDesignOrientation.PORTRAIT)
        val outer = SceneRectPx(0, 0, canvas.widthPx, canvas.heightPx)
        val spec = SceneOverlaySpec.of(
            "wide", "Landscape-tuned text row", SceneOverlayAnchor.TOP_LEFT,
            1, 27, 1920, 1f, true, SceneLayerKind.TEXT, null
        )!!
        val rect = SceneOverlayStack.placeRect(canvas, SceneSizePx(1800, 96), spec)!!
        assertTrue(rect.isInside(outer))
        assertEquals(27, rect.left)
        assertEquals(1080 - 27, rect.right)
    }

    @Test
    fun extremePortraitCanvasResolves() {
        val canvas = SceneCanvas(1080, 16384)
        assertEquals(SceneDesignOrientation.PORTRAIT, SceneCanvasProfile.orientationOf(canvas))
        val inputs = listOf(
            SceneOverlayLayerInput(
                specFor(SceneOverlayAnchor.BOTTOM_RIGHT, SceneLayerKind.TIMER, "u"),
                SceneSizePx(300, 90)
            )
        )
        val resolved = SceneOverlayStack.resolve(canvas, inputs)!!
        assertTrue(resolved.single().rectPx.isInside(SceneRectPx(0, 0, 1080, 16384)))
    }
}
