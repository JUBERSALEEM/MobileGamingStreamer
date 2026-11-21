package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H7 style-geometry contract: padding math and styled sizing shared by
 * the GL renderer and the editor preview. Null-safety and overflow
 * behavior pinned — callers drop (preview) or fail loudly (GL build).
 */
class SceneLayerStyleTest {

    @Test
    fun unstyledPaddingIsZero() {
        assertEquals(
            StylePaddingPx(0, 0, 0, 0),
            SceneLayerStyle.paddingPx(0, 0)
        )
    }

    @Test
    fun borderFramesAllSidesShadowExtendsRightBottom() {
        assertEquals(
            StylePaddingPx(4, 4, 12, 12),
            SceneLayerStyle.paddingPx(4, 8)
        )
    }

    @Test
    fun styledSizeAddsPadding() {
        assertEquals(
            SceneSizePx(216, 56),
            SceneLayerStyle.styledSize(SceneSizePx(200, 40), 4, 8)
        )
    }

    @Test
    fun unstyledSizePassesThrough() {
        assertEquals(
            SceneSizePx(200, 40),
            SceneLayerStyle.styledSize(SceneSizePx(200, 40), 0, 0)
        )
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(SceneLayerStyle.styledSize(SceneSizePx(0, 40), 4, 8))
        assertNull(SceneLayerStyle.styledSize(SceneSizePx(200, 0), 4, 8))
        assertNull(SceneLayerStyle.styledSize(SceneSizePx(200, 40), -1, 8))
        assertNull(SceneLayerStyle.styledSize(SceneSizePx(200, 40), 4, -1))
    }

    @Test
    fun overflowReturnsNull() {
        assertNull(
            SceneLayerStyle.styledSize(SceneSizePx(Int.MAX_VALUE, Int.MAX_VALUE), 64, 64)
        )
    }
}
