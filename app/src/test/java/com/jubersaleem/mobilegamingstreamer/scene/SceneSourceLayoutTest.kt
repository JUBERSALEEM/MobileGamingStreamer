package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic parity tests for [SceneSourceLayoutCalculator] against the
 * exact RootEncoder 2.8.1 rules it mirrors (`ScreenSource` sizing +
 * `prepareVideo` encoder size + `getOrientationConfig`). No Android.
 */
class SceneSourceLayoutTest {
    @Test
    fun layoutPortraitUnrotated() {
        assertEquals(
            SceneSourceLayout(1080, 1920, 1080, 1920),
            SceneSourceLayoutCalculator.layout(1080, 1920, 0)
        )
    }

    @Test
    fun layoutLandscapeUnrotated() {
        assertEquals(
            SceneSourceLayout(1920, 1080, 1920, 1080),
            SceneSourceLayoutCalculator.layout(1920, 1080, 0)
        )
    }

    @Test
    fun layoutRot90Swaps() {
        assertEquals(
            SceneSourceLayout(1080, 1920, 1080, 1920),
            SceneSourceLayoutCalculator.layout(1920, 1080, 90)
        )
    }

    @Test
    fun layoutRot270Swaps() {
        assertEquals(
            SceneSourceLayout(1080, 1920, 1080, 1920),
            SceneSourceLayoutCalculator.layout(1920, 1080, 270)
        )
    }

    @Test
    fun layoutRot180Unswapped() {
        assertEquals(
            SceneSourceLayout(1920, 1080, 1920, 1080),
            SceneSourceLayoutCalculator.layout(1920, 1080, 180)
        )
    }

    @Test
    fun layoutOtherRotationUnswapped() {
        // The library never validates rotation; mirror that leniency.
        for (rotation in listOf(45, -90, 1, 360, 720)) {
            assertEquals(
                "rotation=$rotation",
                SceneSourceLayout(1920, 1080, 1920, 1080),
                SceneSourceLayoutCalculator.layout(1920, 1080, rotation)
            )
        }
    }

    @Test
    fun layoutRejectsOddDimensions() {
        assertNull(SceneSourceLayoutCalculator.layout(1920, 1081, 0))
        assertNull(SceneSourceLayoutCalculator.layout(1919, 1080, 0))
        assertNull(SceneSourceLayoutCalculator.layout(1919, 1081, 90))
    }

    @Test
    fun layoutRejectsZeroAndNegative() {
        assertNull(SceneSourceLayoutCalculator.layout(0, 1080, 0))
        assertNull(SceneSourceLayoutCalculator.layout(1920, 0, 0))
        assertNull(SceneSourceLayoutCalculator.layout(-1920, 1080, 0))
        assertNull(SceneSourceLayoutCalculator.layout(1920, -1080, 0))
    }

    @Test
    fun layoutAcceptsMinEven() {
        assertNotNull(SceneSourceLayoutCalculator.layout(2, 2, 0))
    }

    @Test
    fun inputEqualsOutputPassthrough() {
        // 1:1 passthrough today; modeled as separate spaces for future layers.
        for ((w, h) in listOf(Pair(1080, 1920), Pair(1920, 1080), Pair(1280, 720), Pair(2, 2))) {
            for (rotation in listOf(0, 90, 180, 270)) {
                val result = SceneSourceLayoutCalculator.layout(w, h, rotation)!!
                assertEquals("w=$w h=$h r=$rotation", result.displayWidth, result.outputWidth)
                assertEquals("w=$w h=$h r=$rotation", result.displayHeight, result.outputHeight)
            }
        }
    }

    @Test
    fun portraitFormulaUnrotated() {
        assertTrue(SceneSourceLayoutCalculator.isPortraitStream(1080, 1920, 0))
        assertFalse(SceneSourceLayoutCalculator.isPortraitStream(1920, 1080, 0))
        assertFalse(SceneSourceLayoutCalculator.isPortraitStream(1000, 1000, 0))
        assertFalse(SceneSourceLayoutCalculator.isPortraitStream(1920, 1080, 180))
    }

    @Test
    fun portraitFormulaRotated() {
        // (rotation==90||rotation==270) != (height > width), exactly.
        assertTrue(SceneSourceLayoutCalculator.isPortraitStream(1920, 1080, 90))
        assertTrue(SceneSourceLayoutCalculator.isPortraitStream(1920, 1080, 270))
        assertFalse(SceneSourceLayoutCalculator.isPortraitStream(1080, 1920, 90))
        assertFalse(SceneSourceLayoutCalculator.isPortraitStream(1080, 1920, 270))
        assertTrue(SceneSourceLayoutCalculator.isPortraitStream(1000, 1000, 90))
    }
}
