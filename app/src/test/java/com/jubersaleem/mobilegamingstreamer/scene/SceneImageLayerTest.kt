package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H6 aspect-fit math: fit-inside scaling, no upscaling, invalid-input
 * nulls, and overflow-safe extremes (Long math, never Int overflow).
 */
class SceneImageLayerTest {

    @Test
    fun wideImageFitsWidth() {
        assertEquals(
            SceneSizePx(640, 360),
            SceneImageLayer.measuredSize(1920, 1080, 640, 1026)
        )
    }

    @Test
    fun tallImageFitsHeight() {
        assertEquals(
            SceneSizePx(300, 600),
            SceneImageLayer.measuredSize(600, 1200, 640, 600)
        )
    }

    @Test
    fun squareImageFitsBoth() {
        assertEquals(
            SceneSizePx(480, 480),
            SceneImageLayer.measuredSize(2000, 2000, 640, 480)
        )
    }

    @Test
    fun smallImageKeepsNativeSize() {
        // Never upscale: a small bitmap stays sharp at native size.
        assertEquals(
            SceneSizePx(100, 50),
            SceneImageLayer.measuredSize(100, 50, 640, 1026)
        )
    }

    @Test
    fun exactFitPassesThrough() {
        assertEquals(
            SceneSizePx(640, 360),
            SceneImageLayer.measuredSize(640, 360, 640, 360)
        )
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(SceneImageLayer.measuredSize(0, 100, 640, 480))
        assertNull(SceneImageLayer.measuredSize(100, 0, 640, 480))
        assertNull(SceneImageLayer.measuredSize(-1, 100, 640, 480))
        assertNull(SceneImageLayer.measuredSize(100, 100, 0, 480))
        assertNull(SceneImageLayer.measuredSize(100, 100, 640, 0))
        assertNull(SceneImageLayer.measuredSize(100, 100, -5, 480))
    }

    @Test
    fun collapseToZeroReturnsNull() {
        assertNull(SceneImageLayer.measuredSize(1, 100000, 640, 1))
    }

    @Test
    fun extremeDimensionsDoNotOverflow() {
        // Int math would overflow (MAX_VALUE * 640); Long math fits.
        assertEquals(
            SceneSizePx(480, 480),
            SceneImageLayer.measuredSize(Int.MAX_VALUE, Int.MAX_VALUE, 640, 480)
        )
        assertEquals(
            SceneSizePx(640, 320),
            SceneImageLayer.measuredSize(Int.MAX_VALUE, Int.MAX_VALUE / 2 + 100, 640, 360)
        )
    }
}
