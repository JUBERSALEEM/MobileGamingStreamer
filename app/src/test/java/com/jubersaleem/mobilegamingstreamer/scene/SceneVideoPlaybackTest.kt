package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J2 real-video tests: VIDEO model/codec gates (v5-carried, legacy
 * untouched), the playback state machine, the decoder budget, rotation
 * + FIT/FILL geometry on video dims, planner behavior with VIDEO
 * scenes, and portrait/landscape placement through the pure stack.
 * All behavioral; no source-string assertions.
 */
class SceneVideoPlaybackTest {

    private fun videoLayer(
        id: String = "vid",
        uri: String? = "content://media/video.mp4",
        enabled: Boolean = true,
        scaleMode: SceneScaleMode = SceneScaleMode.FIT,
        anchor: SceneOverlayAnchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder: Int = 1
    ) = StreamSceneLayer(
        id = id,
        text = "",
        anchor = anchor,
        zOrder = zOrder,
        marginPx = 27,
        maxWidthPx = 1920,
        alpha = 1f,
        enabled = enabled,
        kind = SceneLayerKind.VIDEO,
        imageUri = uri,
        scaleMode = scaleMode
    )

    private fun videoSpec(
        id: String = "vid",
        uri: String? = "content://media/video.mp4",
        enabled: Boolean = true
    ): SceneOverlaySpec = videoLayer(id = id, uri = uri, enabled = enabled).toOverlaySpec()!!

    // ---- VIDEO model + codec (v5-carried) ----

    @Test
    fun videoRoundTripsOnV5() {
        val scene = StreamScene("s", "S", listOf(videoLayer()))
        val encoded = SceneConfigCodec.encodeScene(scene)
        assertTrue(encoded.startsWith("v6\n"))
        assertEquals(scene, SceneConfigCodec.decodeScene(encoded))
    }

    @Test
    fun corruptVideoUriDropsLayerKeepsScene() {
        val bad = listOf(
            videoLayer(id = "b1", uri = null),
            videoLayer(id = "b2", uri = ""),
            videoLayer(id = "b3", uri = "   "),
            videoLayer(id = "b4", uri = "https://evil.example/v.mp4"),
            videoLayer(id = "b5", uri = "content://" + "a".repeat(2048))
        )
        for (layer in bad) {
            assertTrue("codec ${layer.id}", layer.toOverlaySpec() == null)
        }
        val scene = StreamScene(
            "s", "S",
            listOf(videoLayer(id = "good")) + bad.take(2)
        )
        val decoded = SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(scene))!!
        assertEquals(listOf("good"), decoded.layers.map { it.id })
    }

    @Test
    fun corruptVideoKindLineDropsOnlyThatLine() {
        val good = "layer:good\t\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tVIDEO\tcontent://m\t" +
            "FFFFFFFF\t\t0\tFF000000\t0\t0\tFF000000\tFIT"
        val bad = good.replace("good", "bad").replace("content://m", "ftp://x")
        val scene = SceneConfigCodec.decodeScene("v5\nscene:s\tS\tLANDSCAPE\n" + good + "\n" + bad)!!
        assertEquals(listOf("good"), scene.layers.map { it.id })
        assertEquals(SceneLayerKind.VIDEO, scene.layers.single().kind)
    }

    @Test
    fun legacyVersionsUnaffectedByVideo() {
        // v1–v4 carry no VIDEO kind: old defaults hold exactly.
        val v1 = SceneConfigCodec.decodeScene("v1\nscene:s\tS\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue")!!
        assertEquals(SceneLayerKind.TEXT, v1.layers.single().kind)
        assertEquals(SceneScaleMode.FIT, v1.layers.single().scaleMode)
        // A VIDEO line with the wrong field count for its header is
        // structural garbage — drops, never half-decodes.
        val v2line = "layer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tVIDEO\tcontent://m\tFIT"
        assertTrue(SceneConfigCodec.decodeScene("v2\nscene:s\tS\n" + v2line)!!.layers.isEmpty())
    }

    @Test
    fun streamableSpecsIncludeValidVideo() {
        val scene = StreamScene(
            "s", "S",
            listOf(
                videoLayer(id = "v"),
                videoLayer(id = "off", enabled = false),
                videoLayer(id = "nogood", uri = "notauri")
            )
        )
        assertEquals(listOf("v"), scene.streamableSpecs().map { it.id })
        assertEquals("content://media/video.mp4", scene.streamableSpecs().single().imageUri)
    }

    @Test
    fun specKeepsUriOnlyForMediaKinds() {
        assertEquals(
            "content://media/video.mp4",
            videoSpec().imageUri
        )
        val text = StreamSceneLayer(
            "t", "Hi", SceneOverlayAnchor.TOP_LEFT, 1, 8, 1920, 1f, true,
            SceneLayerKind.TEXT, "content://media/ignored"
        ).toOverlaySpec()!!
        assertNull(text.imageUri)
    }

    // ---- State machine ----

    @Test
    fun happyPathReachesPlaying() {
        val c = SceneVideoPlaybackController()
        assertEquals(SceneVideoState.NOT_LOADED, c.state)
        assertTrue(c.onSlotAssigned())
        assertEquals(SceneVideoState.READY, c.state)
        assertTrue(c.onPlaying())
        assertEquals(SceneVideoState.PLAYING, c.state)
    }

    @Test
    fun pauseResumeCycle() {
        val c = SceneVideoPlaybackController()
        assertTrue(c.onSlotAssigned())
        assertTrue(c.onPlaying())
        assertTrue(c.onPaused())
        assertEquals(SceneVideoState.PAUSED, c.state)
        assertTrue(c.onPlaying())
        assertEquals(SceneVideoState.PLAYING, c.state)
    }

    @Test
    fun invalidTransitionsRejected() {
        val c = SceneVideoPlaybackController()
        // Nothing valid before a slot is assigned.
        assertTrue(!c.onPlaying())
        assertTrue(!c.onPaused())
        assertTrue(!c.onStopped())
        assertEquals(SceneVideoState.NOT_LOADED, c.state)
        assertTrue(c.onSlotAssigned())
        // No double-assign, no pause before playing.
        assertTrue(!c.onSlotAssigned())
        assertTrue(!c.onPaused())
        assertTrue(c.onPlaying())
        // No re-play, no re-assign mid-play.
        assertTrue(!c.onPlaying())
        assertTrue(!c.onSlotAssigned())
    }

    @Test
    fun errorIsStickyUntilRebuild() {
        val c = SceneVideoPlaybackController()
        assertTrue(c.onSlotAssigned())
        assertTrue(c.onError("prepare failed"))
        assertEquals(SceneVideoState.FAILED, c.state)
        assertEquals("prepare failed", c.failureReason)
        // Sticky: play/pause rejected, second error kept out — but
        // teardown stop still releases a failed slot.
        assertTrue(!c.onPlaying())
        assertTrue(!c.onPaused())
        assertTrue(!c.onError("second"))
        assertEquals("prepare failed", c.failureReason)
        assertTrue(c.onStopped())
        assertEquals(SceneVideoState.STOPPED, c.state)
        assertTrue(c.onRebuild())
        assertEquals(SceneVideoState.NOT_LOADED, c.state)
        assertNull(c.failureReason)
        // Reusable after rebuild.
        assertTrue(c.onSlotAssigned())
        assertTrue(c.onPlaying())
    }

    @Test
    fun errorFromAnyLiveState() {
        for (setup in listOf(
            { c: SceneVideoPlaybackController -> c.onSlotAssigned() },
            { c: SceneVideoPlaybackController -> c.onSlotAssigned(); c.onPlaying() },
            { c: SceneVideoPlaybackController ->
                c.onSlotAssigned(); c.onPlaying(); c.onPaused()
            }
        )) {
            val c = SceneVideoPlaybackController()
            setup(c)
            assertTrue(c.onError("x"))
            assertEquals(SceneVideoState.FAILED, c.state)
        }
    }

    @Test
    fun stopIsTerminalUntilRebuild() {
        val c = SceneVideoPlaybackController()
        assertTrue(c.onSlotAssigned())
        assertTrue(c.onPlaying())
        assertTrue(c.onStopped())
        assertEquals(SceneVideoState.STOPPED, c.state)
        assertTrue(!c.onPlaying())
        assertTrue(!c.onPaused())
        assertTrue(!c.onStopped())
        assertTrue(!c.onError("late"))
        assertTrue(c.onRebuild())
        assertEquals(SceneVideoState.NOT_LOADED, c.state)
    }

    // ---- Decoder budget ----

    @Test
    fun budgetIsTwoInStreamableOrder() {
        assertEquals(2, MAX_CONCURRENT_VIDEO_LAYERS)
        val specs = listOf(videoSpec("a"), videoSpec("b"), videoSpec("c"))
        assertEquals(setOf("a", "b"), SceneVideoBudget.assign(specs))
    }

    @Test
    fun budgetSkipsDisabledAndNonVideo() {
        val text = SceneOverlaySpec.of(
            "t", "Hi", SceneOverlayAnchor.TOP_LEFT, 1, 8, 1920, 1f, true
        )!!
        val specs = listOf(
            videoSpec("off", enabled = false),
            text,
            videoSpec("a"),
            videoSpec("b"),
            videoSpec("c")
        )
        assertEquals(setOf("a", "b"), SceneVideoBudget.assign(specs))
    }

    @Test
    fun budgetEmptyInEmptyOut() {
        assertTrue(SceneVideoBudget.assign(emptyList()).isEmpty())
        val text = SceneOverlaySpec.of(
            "t", "Hi", SceneOverlayAnchor.TOP_LEFT, 1, 8, 1920, 1f, true
        )!!
        assertTrue(SceneVideoBudget.assign(listOf(text)).isEmpty())
    }

    // ---- Rotation + geometry ----

    @Test
    fun rotationSwapsOnlyQuarterTurns() {
        assertEquals(SceneSizePx(1080, 1920), applyVideoRotation(1920, 1080, 90))
        assertEquals(SceneSizePx(1080, 1920), applyVideoRotation(1920, 1080, 270))
        assertEquals(SceneSizePx(1920, 1080), applyVideoRotation(1920, 1080, 0))
        assertEquals(SceneSizePx(1920, 1080), applyVideoRotation(1920, 1080, 180))
        // Negative/overflowing/garbage degrees normalize, never swap wrongly.
        assertEquals(SceneSizePx(1080, 1920), applyVideoRotation(1920, 1080, -270))
        assertEquals(SceneSizePx(1920, 1080), applyVideoRotation(1920, 1080, 45))
        assertEquals(SceneSizePx(1920, 1080), applyVideoRotation(1920, 1080, 720))
        assertNull(applyVideoRotation(0, 1080, 90))
        assertNull(applyVideoRotation(1920, -1, 0))
    }

    @Test
    fun videoFitFillGeometry() {
        // 1080p video FIT into a 1920-wide, 1026-tall room: height
        // constrains (1920*1026/1080 = 1824, floored Long math).
        assertEquals(
            SceneSizePx(1824, 1026),
            SceneImageLayer.measuredSize(1920, 1080, 1920, 1026)
        )
        // Portrait phone video (1080x1920) FIT into the same room.
        assertEquals(
            SceneSizePx(577, 1026),
            SceneImageLayer.measuredSize(1080, 1920, 1920, 1026)
        )
        // FILL always takes the frame; crop keeps the frame aspect
        // (cropH = 1080*1026/1920 = 577; top = (1920-577)/2 = 671).
        assertEquals(
            SceneSizePx(1920, 1026),
            SceneImageLayer.fillSize(1920, 1026, 1920, 1026)
        )
        assertEquals(
            SceneRectPx(0, 671, 1080, 1248),
            SceneImageLayer.fillCrop(1080, 1920, 1920, 1026)
        )
    }

    // ---- Planner + placement with VIDEO ----

    @Test
    fun plannerTreatsVideoScenesLikeAnyScene() {
        val video = SceneConfigCodec.encodeScene(StreamScene("s", "S", listOf(videoLayer())))
        val other = SceneConfigCodec.encodeScene(
            StreamScene("s", "S2", listOf(videoLayer(id = "v2")))
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCENE,
            SceneSwitchPlanner.resolve(SwitchCurrent.Screen, video)
        )
        assertEquals(
            SwitchAction.NOOP,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(video), video)
        )
        assertEquals(
            SwitchAction.IN_PLACE,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(video), other)
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(video), null)
        )
    }

    @Test
    fun videoResolvesInBoundsBothOrientations() {
        for (canvas in listOf(SceneCanvas(1920, 1080), SceneCanvas(1080, 1920))) {
            for (anchor in SceneOverlayAnchor.values()) {
                val spec = videoLayer(anchor = anchor).toOverlaySpec()!!
                val size = SceneImageLayer.measuredSize(
                    1920, 1080, spec.maxWidthPx, canvas.heightPx - spec.marginPx * 2
                )!!
                val entries = SceneOverlayStack.resolve(
                    canvas, listOf(SceneOverlayLayerInput(spec, size))
                )!!
                val rect = entries.single().rectPx
                assertTrue("anchor $anchor canvas $canvas", rect.left >= 0 && rect.top >= 0)
                assertTrue("anchor $anchor canvas $canvas", rect.right <= canvas.widthPx)
                assertTrue("anchor $anchor canvas $canvas", rect.bottom <= canvas.heightPx)
            }
        }
    }
}
