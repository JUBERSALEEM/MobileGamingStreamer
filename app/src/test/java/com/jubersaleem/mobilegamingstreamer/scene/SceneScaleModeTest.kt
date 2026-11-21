package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J1 graphics/asset tests: FILL frame + cover-crop math, codec v5
 * round-trip and back-compat (v1–v4 decode to the FIT default),
 * v5 scale-token validation, and model→spec scale passthrough.
 */
class SceneScaleModeTest {

    private fun imageLayer(
        id: String = "img",
        scaleMode: SceneScaleMode = SceneScaleMode.FILL
    ) = StreamSceneLayer(
        id = id,
        text = "",
        anchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder = 1,
        marginPx = 27,
        maxWidthPx = 1920,
        alpha = 1f,
        enabled = true,
        kind = SceneLayerKind.IMAGE,
        imageUri = "content://media/img",
        scaleMode = scaleMode
    )

    // ---- fillSize ----

    @Test
    fun fillSizeCoversBoundsCappedToRoom() {
        assertEquals(
            SceneSizePx(1866, 1026),
            SceneImageLayer.fillSize(1920, 1026, 1866, 1026)
        )
        // Room tighter than the request caps the frame.
        assertEquals(
            SceneSizePx(800, 600),
            SceneImageLayer.fillSize(1920, 1026, 800, 600)
        )
    }

    @Test
    fun fillSizeRejectsInvalid() {
        assertNull(SceneImageLayer.fillSize(0, 100, 100, 100))
        assertNull(SceneImageLayer.fillSize(100, 100, 100, 0))
        assertNull(SceneImageLayer.fillSize(-5, 100, 100, 100))
        assertNull(SceneImageLayer.fillSize(100, 100, -1, 100))
    }

    // ---- fillCrop ----

    @Test
    fun fillCropKeepsFrameAspectCentered() {
        // Wide bitmap (2:1) into a square frame: crop width centered.
        assertEquals(
            SceneRectPx(300, 0, 900, 600),
            SceneImageLayer.fillCrop(1200, 600, 300, 300)
        )
        // Tall bitmap (1:2) into a wide frame: crop height centered.
        assertEquals(
            SceneRectPx(0, 450, 600, 750),
            SceneImageLayer.fillCrop(600, 1200, 600, 300)
        )
    }

    @Test
    fun fillCropMatchingAspectKeepsFullBitmap() {
        assertEquals(
            SceneRectPx(0, 0, 1920, 1080),
            SceneImageLayer.fillCrop(1920, 1080, 1920, 1080)
        )
    }

    @Test
    fun fillCropFloorsOddLeftovers() {
        // 101px wide into a square frame: (101-100)/2 = 0 left, the
        // leftover pixel goes right (repo centering convention).
        assertEquals(
            SceneRectPx(0, 0, 100, 100),
            SceneImageLayer.fillCrop(101, 100, 50, 50)
        )
    }

    @Test
    fun fillCropRejectsInvalid() {
        assertNull(SceneImageLayer.fillCrop(0, 100, 50, 50))
        assertNull(SceneImageLayer.fillCrop(100, 100, 0, 50))
        assertNull(SceneImageLayer.fillCrop(-1, 100, 50, 50))
        assertNull(SceneImageLayer.fillCrop(100, 100, 50, -2))
    }

    // ---- codec v5 ----

    @Test
    fun encodeWritesV6WithScaleMode() {
        val scene = StreamScene("s", "S", listOf(imageLayer()))
        val encoded = SceneConfigCodec.encodeScene(scene)
        assertTrue(encoded.startsWith("v6\n"))
        assertTrue(encoded.endsWith("\tFILL\t"))
        assertEquals(scene, SceneConfigCodec.decodeScene(encoded))
    }

    @Test
    fun legacyVersionsDecodeToFitDefault() {
        val v1 = SceneConfigCodec.decodeScene("v1\nscene:s\tS\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue")!!
        assertEquals(SceneScaleMode.FIT, v1.layers.single().scaleMode)
        val v2line = "layer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tTEXT\t"
        val v2 = SceneConfigCodec.decodeScene("v2\nscene:s\tS\n" + v2line)!!
        assertEquals(SceneScaleMode.FIT, v2.layers.single().scaleMode)
        val v4line = "layer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tTEXT\t\t" +
            "FFFFFFFF\t\t0\tFF000000\t0\t0\tFF000000"
        val v4 = SceneConfigCodec.decodeScene("v4\nscene:s\tS\tLANDSCAPE\n" + v4line)!!
        assertEquals(SceneScaleMode.FIT, v4.layers.single().scaleMode)
        // FILL never appears unless a v5 line carries it.
        val encoded = SceneConfigCodec.encodeScene(v4)
        assertTrue(encoded.endsWith("\tFIT\t"))
    }

    @Test
    fun corruptScaleTokenDropsLayer() {
        val good = "layer:good\t\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tIMAGE\tcontent://m\t" +
            "FFFFFFFF\t\t0\tFF000000\t0\t0\tFF000000\tFILL"
        val bad = good.replace("good", "bad").replace("\tFILL", "\tSTRETCH")
        val scene = SceneConfigCodec.decodeScene("v5\nscene:s\tS\tLANDSCAPE\n" + good + "\n" + bad)!!
        assertEquals(listOf("good"), scene.layers.map { it.id })
        assertEquals(SceneScaleMode.FILL, scene.layers.single().scaleMode)
    }

    @Test
    fun fitRoundTripsToo() {
        val scene = StreamScene("s", "S", listOf(imageLayer(scaleMode = SceneScaleMode.FIT)))
        val decoded = SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(scene))!!
        assertEquals(SceneScaleMode.FIT, decoded.layers.single().scaleMode)
    }

    // ---- model → spec passthrough ----

    @Test
    fun scaleModeReachesOverlaySpec() {
        assertEquals(
            SceneScaleMode.FILL,
            imageLayer().toOverlaySpec()!!.scaleMode
        )
        assertEquals(
            SceneScaleMode.FIT,
            imageLayer(scaleMode = SceneScaleMode.FIT).toOverlaySpec()!!.scaleMode
        )
    }
}
