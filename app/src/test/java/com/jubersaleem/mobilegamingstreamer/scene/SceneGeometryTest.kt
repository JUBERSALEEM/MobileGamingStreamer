package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic tests for the H0 geometry foundation. Every test drives the
 * real [SceneLayoutCalculator] / [SceneCanvas] / [SceneSourceGeometry] with
 * exact pinned rectangles — no randomness, no Android, no sleeps.
 */
class SceneGeometryTest {
    // ---- Canvas validation and orientation ----

    @Test
    fun canvasPortraitOrientation() {
        val canvas = SceneCanvas(1080, 1920)
        assertTrue(canvas.isValid)
        assertEquals(SceneOrientation.PORTRAIT, canvas.orientation)
    }

    @Test
    fun canvasLandscapeOrientation() {
        val canvas = SceneCanvas(1920, 1080)
        assertTrue(canvas.isValid)
        assertEquals(SceneOrientation.LANDSCAPE, canvas.orientation)
    }

    @Test
    fun canvasSquareIsExplicit() {
        val canvas = SceneCanvas(1080, 1080)
        assertTrue(canvas.isValid)
        assertEquals(SceneOrientation.SQUARE, canvas.orientation)
    }

    @Test
    fun canvasRejectsZeroDimensions() {
        assertFalse(SceneCanvas(0, 1080).isValid)
        assertFalse(SceneCanvas(1080, 0).isValid)
        assertFalse(SceneCanvas(0, 0).isValid)
    }

    @Test
    fun canvasRejectsNegativeDimensions() {
        assertFalse(SceneCanvas(-1, 1080).isValid)
        assertFalse(SceneCanvas(1080, -1).isValid)
        assertFalse(SceneCanvas(-1920, -1080).isValid)
    }

    @Test
    fun canvasRejectsOverMaxDimensions() {
        assertFalse(SceneCanvas(MAX_SCENE_DIMENSION_PX + 1, 1080).isValid)
        assertFalse(SceneCanvas(1080, MAX_SCENE_DIMENSION_PX + 1).isValid)
        assertFalse(SceneCanvas(Int.MAX_VALUE, Int.MAX_VALUE).isValid)
    }

    @Test
    fun canvasAcceptsMaxBoundary() {
        val canvas = SceneCanvas(MAX_SCENE_DIMENSION_PX, MAX_SCENE_DIMENSION_PX)
        assertTrue(canvas.isValid)
        assertEquals(SceneOrientation.SQUARE, canvas.orientation)
    }

    @Test
    fun canvasAcceptsMinBoundary() {
        val canvas = SceneCanvas(1, 1)
        assertTrue(canvas.isValid)
        assertEquals(SceneOrientation.SQUARE, canvas.orientation)
    }

    @Test
    fun orientationNullWhenInvalid() {
        assertNull(SceneCanvas(0, 1080).orientation)
        assertNull(SceneCanvas(1080, 0).orientation)
        assertNull(SceneCanvas(-5, 100).orientation)
        assertNull(SceneCanvas(MAX_SCENE_DIMENSION_PX + 1, 100).orientation)
    }

    // ---- Source geometry and rotation ----

    @Test
    fun sourceEffectiveSizeNoRotation() {
        val source = SceneSourceGeometry(1920, 1080, 0)
        assertTrue(source.isValid)
        assertEquals(SceneSizePx(1920, 1080), source.effectiveSizePx())
    }

    @Test
    fun sourceEffectiveSizeSwapsOn90And270() {
        assertEquals(SceneSizePx(1080, 1920), SceneSourceGeometry(1920, 1080, 90).effectiveSizePx())
        assertEquals(SceneSizePx(1080, 1920), SceneSourceGeometry(1920, 1080, 270).effectiveSizePx())
    }

    @Test
    fun sourceEffectiveSizeUnchangedOn180() {
        val source = SceneSourceGeometry(1920, 1080, 180)
        assertTrue(source.isValid)
        assertEquals(SceneSizePx(1920, 1080), source.effectiveSizePx())
    }

    @Test
    fun sourceRejectsNonQuarterTurnRotation() {
        for (rotation in listOf(45, -90, 1, 100, 359, 360, 720, Int.MIN_VALUE)) {
            val source = SceneSourceGeometry(1920, 1080, rotation)
            assertFalse("rotation=$rotation", source.isValidRotation)
            assertFalse("rotation=$rotation", source.isValid)
            assertNull(source.effectiveSizePx())
        }
    }

    @Test
    fun sourceRejectsBadDimensions() {
        assertFalse(SceneSourceGeometry(0, 1080).isValidDimensions)
        assertFalse(SceneSourceGeometry(1080, 0).isValidDimensions)
        assertFalse(SceneSourceGeometry(-1, 1080).isValidDimensions)
        assertFalse(SceneSourceGeometry(1080, MAX_SCENE_DIMENSION_PX + 1).isValidDimensions)
        assertFalse(SceneSourceGeometry(Int.MAX_VALUE, 1080).isValid)
        assertNull(SceneSourceGeometry(0, 1080).effectiveSizePx())
    }

    // ---- Aspect ratio ----

    @Test
    fun aspectRatioLandscape() {
        val ratio = SceneSourceGeometry(1920, 1080).effectiveAspectRatio
        assertNotNull(ratio)
        assertTrue(abs(ratio!! - 16.0 / 9.0) < 1e-9)
    }

    @Test
    fun aspectRatioPortrait() {
        val ratio = SceneSourceGeometry(1080, 1920).effectiveAspectRatio
        assertNotNull(ratio)
        assertTrue(abs(ratio!! - 9.0 / 16.0) < 1e-9)
    }

    @Test
    fun aspectRatioSquareIsOne() {
        assertEquals(1.0, SceneSourceGeometry(1000, 1000).effectiveAspectRatio)
    }

    @Test
    fun aspectRatioFollowsRotation() {
        val ratio = SceneSourceGeometry(1920, 1080, 90).effectiveAspectRatio
        assertNotNull(ratio)
        assertTrue(abs(ratio!! - 9.0 / 16.0) < 1e-9)
    }

    @Test
    fun aspectRatioNullWhenInvalid() {
        assertNull(SceneSourceGeometry(0, 1080).effectiveAspectRatio)
        assertNull(SceneSourceGeometry(1920, 1080, 45).effectiveAspectRatio)
    }

    @Test
    fun aspectRatioNeverNaNOrInfinite() {
        val dims = listOf(1, 2, 1079, 1080, 1920, MAX_SCENE_DIMENSION_PX)
        for (w in dims) {
            for (h in dims) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    val ratio = SceneSourceGeometry(w, h, rotation).effectiveAspectRatio
                    assertTrue("w=$w h=$h r=$rotation", ratio != null)
                    assertTrue("w=$w h=$h r=$rotation", ratio!!.isFinite())
                    assertTrue("w=$w h=$h r=$rotation", ratio > 0.0)
                }
            }
        }
    }

    // ---- FIT ----

    @Test
    fun fitSameAspectFillsCanvas() {
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(1080, 1920), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result.cropRectPx)
    }

    @Test
    fun fitLandscapeSourceToPortraitCanvas() {
        // 1920x1080 into 1080x1920: destW=1080, destH=floor(1080*1080/1920)=607,
        // top=(1920-607)/2=656.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(1920, 1080), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 656, 1080, 1263), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1920, 1080), result.cropRectPx)
    }

    @Test
    fun fitPortraitSourceToLandscapeCanvas() {
        // 1080x1920 into 1920x1080: destH=1080, destW=floor(1080*1080/1920)=607,
        // left=(1920-607)/2=656.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1920, 1080), SceneSourceGeometry(1080, 1920), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(656, 0, 1263, 1080), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result.cropRectPx)
    }

    @Test
    fun fitSquareSourceToLandscapeCanvas() {
        // 1080x1080 into 1920x1080: dest=1080x1080, left=(1920-1080)/2=420.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1920, 1080), SceneSourceGeometry(1080, 1080), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(420, 0, 1500, 1080), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1080, 1080), result.cropRectPx)
    }

    @Test
    fun fitExtremeWideSourceToTallCanvas() {
        // 21:9 (2520x1080) into 1080x1920: destH=floor(1080*1080/2520)=462,
        // top=(1920-462)/2=729.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(2520, 1080), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 729, 1080, 1191), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 2520, 1080), result.cropRectPx)
    }

    @Test
    fun fitClampsFlooredZeroToOnePixel() {
        // 16384x1 into 100x100: destH=floor(100*1/16384)=0 -> coerced to 1.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(100, 100), SceneSourceGeometry(16384, 1), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 49, 100, 50), result!!.destRectPx)
        assertTrue(result.destRectPx.isValid)
        assertTrue(result.destRectPx.isInside(SceneRectPx(0, 0, 100, 100)))
    }

    @Test
    fun fitCropAlwaysFullSource() {
        val cases = listOf(
            Pair(SceneCanvas(1080, 1920), SceneSourceGeometry(1920, 1080)),
            Pair(SceneCanvas(1920, 1080), SceneSourceGeometry(1080, 1920)),
            Pair(SceneCanvas(100, 100), SceneSourceGeometry(16384, 1)),
            Pair(SceneCanvas(1, 1), SceneSourceGeometry(MAX_SCENE_DIMENSION_PX, 7))
        )
        for ((canvas, source) in cases) {
            val result = SceneLayoutCalculator.layout(canvas, source, SceneLayoutPolicy.FIT)
            assertTrue("$canvas $source", result != null)
            val effective = source.effectiveSizePx()!!
            assertEquals(
                "$canvas $source",
                SceneRectPx(0, 0, effective.widthPx, effective.heightPx),
                result!!.cropRectPx
            )
        }
    }

    @Test
    fun fitCenteringRemainderGoesRight() {
        // 1080x1080 into 1919x1080: slack=839, left=floor(839/2)=419,
        // right margin=420 — the leftover pixel goes right.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1919, 1080), SceneSourceGeometry(1080, 1080), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(419, 0, 1499, 1080), result!!.destRectPx)
    }

    @Test
    fun fitRotatedSourceUsesEffectiveSize() {
        // 1920x1080 rotated 90 behaves exactly as 1080x1920: full-bleed FIT.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(1920, 1080, 90), SceneLayoutPolicy.FIT
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result.cropRectPx)
    }

    // ---- FILL ----

    @Test
    fun fillSameAspectFull() {
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1920, 1080), SceneSourceGeometry(1920, 1080), SceneLayoutPolicy.FILL
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1920, 1080), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 1920, 1080), result.cropRectPx)
    }

    @Test
    fun fillLandscapeSourceToPortraitCanvas() {
        // 1920x1080 covering 1080x1920: cropH=1080,
        // cropW=floor(1080*1080/1920)=607, left=(1920-607)/2=656.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(1920, 1080), SceneLayoutPolicy.FILL
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result!!.destRectPx)
        assertEquals(SceneRectPx(656, 0, 1263, 1080), result.cropRectPx)
    }

    @Test
    fun fillPortraitSourceToLandscapeCanvas() {
        // 1080x1920 covering 1920x1080: cropW=1080,
        // cropH=floor(1080*1080/1920)=607, top=(1920-607)/2=656.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1920, 1080), SceneSourceGeometry(1080, 1920), SceneLayoutPolicy.FILL
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1920, 1080), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 656, 1080, 1263), result.cropRectPx)
    }

    @Test
    fun fillSquareSourceToPortraitCanvas() {
        // 1000x1000 covering 1080x1920: cropH=1000,
        // cropW=floor(1000*1080/1920)=562, left=(1000-562)/2=219.
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(1000, 1000), SceneLayoutPolicy.FILL
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result!!.destRectPx)
        assertEquals(SceneRectPx(219, 0, 781, 1000), result.cropRectPx)
    }

    @Test
    fun fillDestAlwaysFullCanvas() {
        val canvases = listOf(
            SceneCanvas(1080, 1920), SceneCanvas(1920, 1080),
            SceneCanvas(1, 1), SceneCanvas(1919, 719)
        )
        val sources = listOf(
            SceneSourceGeometry(1920, 1080), SceneSourceGeometry(1080, 1920),
            SceneSourceGeometry(1, 1), SceneSourceGeometry(100, 700, 270)
        )
        for (canvas in canvases) {
            for (source in sources) {
                val result = SceneLayoutCalculator.layout(canvas, source, SceneLayoutPolicy.FILL)
                assertTrue("$canvas $source", result != null)
                assertEquals(
                    "$canvas $source",
                    SceneRectPx(0, 0, canvas.widthPx, canvas.heightPx),
                    result!!.destRectPx
                )
            }
        }
    }

    // ---- STRETCH ----

    @Test
    fun stretchIgnoresAspect() {
        val result = SceneLayoutCalculator.layout(
            SceneCanvas(1080, 1920), SceneSourceGeometry(100, 700), SceneLayoutPolicy.STRETCH
        )
        assertNotNull(result)
        assertEquals(SceneRectPx(0, 0, 1080, 1920), result!!.destRectPx)
        assertEquals(SceneRectPx(0, 0, 100, 700), result.cropRectPx)
    }

    // ---- Rejection and safety ----

    @Test
    fun layoutRejectsInvalidCanvas() {
        val source = SceneSourceGeometry(1920, 1080)
        for (policy in SceneLayoutPolicy.values()) {
            assertNull(SceneLayoutCalculator.layout(SceneCanvas(0, 1080), source, policy))
            assertNull(SceneLayoutCalculator.layout(SceneCanvas(1080, 0), source, policy))
            assertNull(SceneLayoutCalculator.layout(SceneCanvas(-1, 1080), source, policy))
            assertNull(
                SceneLayoutCalculator.layout(
                    SceneCanvas(MAX_SCENE_DIMENSION_PX + 1, 1080), source, policy
                )
            )
        }
    }

    @Test
    fun layoutRejectsInvalidSource() {
        val canvas = SceneCanvas(1080, 1920)
        for (policy in SceneLayoutPolicy.values()) {
            assertNull(SceneLayoutCalculator.layout(canvas, SceneSourceGeometry(0, 0), policy))
            assertNull(SceneLayoutCalculator.layout(canvas, SceneSourceGeometry(1920, -1080), policy))
            assertNull(
                SceneLayoutCalculator.layout(
                    canvas, SceneSourceGeometry(MAX_SCENE_DIMENSION_PX + 1, 1080), policy
                )
            )
        }
    }

    @Test
    fun layoutRejectsInvalidRotation() {
        val canvas = SceneCanvas(1080, 1920)
        for (policy in SceneLayoutPolicy.values()) {
            assertNull(SceneLayoutCalculator.layout(canvas, SceneSourceGeometry(1920, 1080, 45), policy))
            assertNull(SceneLayoutCalculator.layout(canvas, SceneSourceGeometry(1920, 1080, -90), policy))
        }
    }

    @Test
    fun layoutRejectsHugeDimensionsWithoutOverflow() {
        // Int.MAX_VALUE cross-products would overflow Int math; the Long
        // implementation plus validity bound must reject without throwing.
        val canvas = SceneCanvas(1080, 1920)
        val huge = SceneSourceGeometry(Int.MAX_VALUE, Int.MAX_VALUE)
        assertFalse(huge.isValid)
        for (policy in SceneLayoutPolicy.values()) {
            assertNull(SceneLayoutCalculator.layout(canvas, huge, policy))
            assertNull(
                SceneLayoutCalculator.layout(
                    SceneCanvas(Int.MAX_VALUE, Int.MAX_VALUE),
                    SceneSourceGeometry(1920, 1080), policy
                )
            )
        }
    }

    @Test
    fun layoutDeterministic() {
        val canvas = SceneCanvas(1919, 1080)
        val source = SceneSourceGeometry(1080, 1080)
        for (policy in SceneLayoutPolicy.values()) {
            val first = SceneLayoutCalculator.layout(canvas, source, policy)
            val second = SceneLayoutCalculator.layout(canvas, source, policy)
            assertNotNull(first)
            assertEquals(first, second)
        }
    }

    @Test
    fun layoutSweepStaysInBounds() {
        val dims = listOf(1, 2, 3, 100, 1079, 1080, 1920, 16383, MAX_SCENE_DIMENSION_PX)
        for (canvasW in dims) {
            for (canvasH in dims) {
                for (rotation in listOf(0, 90, 180, 270)) {
                    // Transposed source covers portrait<->landscape combos.
                    val canvas = SceneCanvas(canvasW, canvasH)
                    val source = SceneSourceGeometry(canvasH, canvasW, rotation)
                    val effective = source.effectiveSizePx()!!
                    for (policy in SceneLayoutPolicy.values()) {
                        val result = SceneLayoutCalculator.layout(canvas, source, policy)
                        val tag = "canvas=$canvas source=$source policy=$policy"
                        assertTrue(tag, result != null)
                        assertTrue(tag, result!!.destRectPx.isValid)
                        assertTrue(tag, result.cropRectPx.isValid)
                        assertTrue(
                            tag,
                            result.destRectPx.isInside(SceneRectPx(0, 0, canvasW, canvasH))
                        )
                        assertTrue(
                            tag,
                            result.cropRectPx.isInside(
                                SceneRectPx(0, 0, effective.widthPx, effective.heightPx)
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- Rect helpers ----

    @Test
    fun rectDimensionsAndValidity() {
        val rect = SceneRectPx(10, 20, 30, 50)
        assertEquals(20, rect.width)
        assertEquals(30, rect.height)
        assertTrue(rect.isValid)
        assertFalse(SceneRectPx(30, 20, 10, 50).isValid)
        assertFalse(SceneRectPx(10, 50, 30, 20).isValid)
        assertFalse(SceneRectPx(10, 20, 10, 50).isValid)
    }

    @Test
    fun rectInsideAllowsTouchingEdges() {
        val outer = SceneRectPx(0, 0, 100, 100)
        assertTrue(SceneRectPx(0, 0, 100, 100).isInside(outer))
        assertTrue(SceneRectPx(10, 10, 90, 90).isInside(outer))
        assertTrue(SceneRectPx(0, 50, 100, 100).isInside(outer))
    }

    @Test
    fun rectOutsideRejected() {
        val outer = SceneRectPx(0, 0, 100, 100)
        assertFalse(SceneRectPx(-1, 0, 100, 100).isInside(outer))
        assertFalse(SceneRectPx(0, 0, 101, 100).isInside(outer))
        assertFalse(SceneRectPx(0, 0, 100, 101).isInside(outer))
        assertFalse(SceneRectPx(50, 50, 40, 60).isInside(outer))
    }
}
