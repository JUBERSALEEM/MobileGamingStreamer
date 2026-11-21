package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J5 hardening contract: motion-scheduling policy, budget stress,
 * lifecycle stress, planner chains, and codec version sweep.
 * Behavioral tests over the pure cores — no source-string checks
 * here (the Android-shell regression pins live in
 * `StreamingLifecycleStructureTest` because that logic cannot
 * execute on the JVM).
 */
class SceneHardeningTest {

    private fun anim(
        preset: SceneAnimPreset = SceneAnimPreset.FADE_IN,
        durationMs: Int = 1000,
        delayMs: Int = 0,
        repeat: SceneAnimRepeat = SceneAnimRepeat.ONCE
    ): SceneLayerAnimation = SceneLayerAnimation(
        preset, durationMs, delayMs, repeat, SceneAnimEasing.LINEAR
    )

    private fun transition(
        type: SceneTransitionType = SceneTransitionType.CROSSFADE,
        durationMs: Int = 500
    ): SceneTransition = SceneTransition(type, durationMs, SceneAnimEasing.LINEAR)

    private fun videoSpec(id: String, enabled: Boolean = true): SceneOverlaySpec =
        SceneOverlaySpec(
            id = id, text = "", anchor = SceneOverlayAnchor.TOP_LEFT, zOrder = 1,
            marginPx = 8, maxWidthPx = 640, alpha = 1f, enabled = enabled,
            kind = SceneLayerKind.VIDEO, imageUri = "content://media/$id.mp4"
        )

    private fun frame(seq: Long): SceneFrameMetadata = SceneFrameMetadata(seq, seq * 1000L)

    private fun recordingSteps(log: MutableList<String>): SceneTeardownSteps =
        SceneTeardownSteps(
            releaseVirtualDisplay = { log.add("vd") },
            releaseOutputSurface = { log.add("output") },
            releaseInputSurface = { log.add("input") },
            releaseEgl = { log.add("egl") },
            releaseProjectionCallback = { log.add("callback") },
            quitGlThread = { log.add("thread") }
        )

    private val sceneA = SceneConfigCodec.encodeScene(SceneConfigCodec.defaultScene())
    private val sceneB = SceneConfigCodec.encodeScene(
        SceneConfigCodec.defaultScene().copy(
            id = "other",
            name = "Other",
            layers = listOf(
                StreamSceneLayer(
                    id = "text",
                    text = "OTHER",
                    anchor = SceneOverlayAnchor.BOTTOM_RIGHT,
                    zOrder = 1,
                    marginPx = 8,
                    maxWidthPx = 1920,
                    alpha = 1f,
                    enabled = true
                )
            )
        )
    )

    // ---- Motion policy: tick-arm truth table + zero-overhead static ----

    @Test
    fun tickNeededTruthTable() {
        assertTrue(!SceneMotionPolicy.tickNeeded(false, false))
        assertTrue(SceneMotionPolicy.tickNeeded(true, false))
        assertTrue(SceneMotionPolicy.tickNeeded(false, true))
        assertTrue(SceneMotionPolicy.tickNeeded(true, true))
    }

    @Test
    fun staticSceneNeedsNoTick() {
        // No animations, no blend: the ticker stays off (zero overhead).
        assertTrue(!SceneMotionPolicy.tickNeeded(false, false))
        assertTrue(!SceneMotionPolicy.animActiveAt(anim(SceneAnimPreset.NONE), 1000L, 2000L))
        assertTrue(!SceneMotionPolicy.transActiveAt(transition(), 1000L, 2000L))
    }

    @Test
    fun animActiveDuringDelayAndRun() {
        val a = anim(delayMs = 200)
        // Pre-start (t < 0): delay window counts as active.
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 900L))
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 1000L))
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 1500L))
    }

    @Test
    fun animRestedOnceNeedsNoTick() {
        val a = anim(durationMs = 1000, delayMs = 200)
        assertTrue(!SceneMotionPolicy.animActiveAt(a, 1000L, 2200L))
        assertTrue(!SceneMotionPolicy.animActiveAt(a, 1000L, 10_000L))
    }

    @Test
    fun loopAlwaysNeedsTick() {
        val a = anim(repeat = SceneAnimRepeat.LOOP)
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 1000L))
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 1500L))
        assertTrue(SceneMotionPolicy.animActiveAt(a, 1000L, 11_000L))
    }

    @Test
    fun nonePresetNeverNeedsTick() {
        val a = anim(SceneAnimPreset.NONE)
        assertTrue(!SceneMotionPolicy.animActiveAt(a, 1000L, 500L))
        assertTrue(!SceneMotionPolicy.animActiveAt(a, 1000L, 1000L))
        assertTrue(!SceneMotionPolicy.animActiveAt(a, 1000L, 5000L))
    }

    @Test
    fun transActiveUntilDone() {
        val t = transition(durationMs = 500)
        assertTrue(SceneMotionPolicy.transActiveAt(t, 1000L, 1000L))
        assertTrue(SceneMotionPolicy.transActiveAt(t, 1000L, 1250L))
        assertTrue(!SceneMotionPolicy.transActiveAt(t, 1000L, 1500L))
        assertTrue(!SceneMotionPolicy.transDoneAt(t, 1000L, 1250L))
        assertTrue(SceneMotionPolicy.transDoneAt(t, 1000L, 1500L))
        assertTrue(SceneMotionPolicy.transDoneAt(t, 1000L, 9000L))
    }

    @Test
    fun cutReadsDoneAndNeverArms() {
        val cut = SceneTransition.DEFAULT
        assertTrue(cut.isInstant())
        // CUT is instantly done even if consulted (GL short-circuits
        // isInstant before sampling — this is defensive depth).
        assertTrue(!SceneMotionPolicy.transActiveAt(cut, 1000L, 1000L))
        assertTrue(SceneMotionPolicy.transDoneAt(cut, 1000L, 1000L))
        assertTrue(!transition(SceneTransitionType.WIPE_LEFT).isInstant())
    }

    @Test
    fun policyNeverThrowsOnGarbage() {
        val garbageAnim = anim(durationMs = -50, delayMs = -7)
        assertTrue(!SceneMotionPolicy.animActiveAt(garbageAnim, Long.MIN_VALUE, Long.MAX_VALUE))
        val garbageTrans = transition(durationMs = -50)
        // Total: garbage time collapses to progress 0 (blend continues,
        // sampling the start state — self-corrects next tick).
        assertTrue(SceneMotionPolicy.transActiveAt(garbageTrans, 1000L, -500L))
        assertTrue(!SceneMotionPolicy.transDoneAt(garbageTrans, 1000L, -500L))
        assertTrue(SceneMotionPolicy.transDoneAt(garbageTrans, 1000L, Long.MAX_VALUE))
    }

    @Test
    fun easedProgressSurvivesFullSweep() {
        val types = listOf(
            SceneTransitionType.CROSSFADE,
            SceneTransitionType.FADE_THROUGH_BLACK,
            SceneTransitionType.WIPE_LEFT
        )
        for (type in types) {
            val config = transition(type, 500)
            var last = -1f
            var doneAt = -1L
            var elapsed = 0L
            while (elapsed <= 1000L) {
                val eased = SceneTransitions.easedProgress(config, elapsed)
                assertTrue("$type eased in range at $elapsed", eased in 0f..1f)
                assertTrue("$type monotonic at $elapsed", eased >= last)
                last = eased
                if (SceneTransitions.isDoneAt(config, elapsed) && doneAt < 0L) doneAt = elapsed
                elapsed += 1L
            }
            assertEquals("$type done exactly at duration", 500L, doneAt)
        }
    }

    @Test
    fun transitionSamplesMonotonicToDone() {
        val types = listOf(
            SceneTransitionType.SLIDE_UP,
            SceneTransitionType.ZOOM_IN,
            SceneTransitionType.FADE_THROUGH_BLACK
        )
        for (type in types) {
            val progress = TransitionProgress(transition(type, 400), 7000L)
            var last = -1f
            var elapsed = 0L
            while (elapsed <= 800L) {
                val sample = progress.sampleAt(7000L + elapsed)
                assertTrue("$type sample in range", sample.eased01 in 0f..1f)
                assertTrue("$type sample monotonic", sample.eased01 >= last)
                last = sample.eased01
                assertEquals("$type done flag", elapsed >= 400L, sample.done)
                elapsed += 7L
            }
        }
    }

    // ---- Decoder budget stress ----

    @Test
    fun decoderBudgetDeterministicAcrossRebuilds() {
        val specs = listOf(videoSpec("a"), videoSpec("b"), videoSpec("c"))
        val first = SceneVideoBudget.assign(specs)
        assertEquals(setOf("a", "b"), first)
        repeat(200) {
            assertEquals("rebuild $it identical", first, SceneVideoBudget.assign(specs))
        }
    }

    @Test
    fun decoderBudgetCapsThreeVideosAtFirstTwo() {
        val assigned = SceneVideoBudget.assign(
            listOf(videoSpec("a"), videoSpec("b"), videoSpec("c"))
        )
        assertEquals(2, assigned.size)
        assertTrue(assigned.contains("a"))
        assertTrue(assigned.contains("b"))
        assertTrue(!assigned.contains("c"))
    }

    @Test
    fun decoderBudgetHoldsUnderRapidAlternation() {
        val even = listOf(videoSpec("a"), videoSpec("b"), videoSpec("c"))
        val odd = listOf(videoSpec("x"), videoSpec("y"), videoSpec("z"))
        repeat(100) { round ->
            val assigned = SceneVideoBudget.assign(if (round % 2 == 0) even else odd)
            assertEquals(2, assigned.size)
            if (round % 2 == 0) assertEquals(setOf("a", "b"), assigned)
            else assertEquals(setOf("x", "y"), assigned)
        }
    }

    // ---- Snapshot budget stress ----

    @Test
    fun snapshotExact16MiBBoundary() {
        assertEquals(16_777_216L, SceneTransitions.snapshotBytesFor(2048, 2048))
        assertTrue(SceneTransitions.allowsSnapshot(2048, 2048))
        assertTrue(SceneTransitions.allowsSnapshot(4096, 1024))
        // One row over 16 MiB degrades to CUT.
        assertTrue(!SceneTransitions.allowsSnapshot(2048, 2049))
        assertTrue(!SceneTransitions.allowsSnapshot(4096, 1025))
    }

    @Test
    fun snapshotHostileDimsDeny() {
        assertTrue(!SceneTransitions.allowsSnapshot(1920, -1))
        assertTrue(!SceneTransitions.allowsSnapshot(-1, 1080))
        assertTrue(!SceneTransitions.allowsSnapshot(0, 0))
        assertTrue(!SceneTransitions.allowsSnapshot(Int.MAX_VALUE, 1))
        assertTrue(!SceneTransitions.allowsSnapshot(1, Int.MAX_VALUE))
        assertTrue(!SceneTransitions.allowsSnapshot(Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals(0L, SceneTransitions.snapshotBytesFor(Int.MIN_VALUE, 1080))
    }

    @Test
    fun snapshotGateDeterministic200() {
        val dims = listOf(
            1920 to 1080, 1080 to 1920, 2560 to 1440, 2048 to 2048,
            2048 to 2049, 7680 to 4320, 0 to 1080, -4 to 1080
        )
        val first = dims.map { SceneTransitions.allowsSnapshot(it.first, it.second) }
        repeat(200) {
            assertEquals(first, dims.map { d -> SceneTransitions.allowsSnapshot(d.first, d.second) })
        }
    }

    // ---- Transition chains: rapid re-switch ----

    @Test
    fun rapidReswitchRestartsProgress() {
        var start = 5000L
        repeat(50) { _ ->
            val old = TransitionProgress(transition(SceneTransitionType.WIPE_LEFT), start)
            val mid = old.sampleAt(start + 250L)
            assertTrue(mid.eased01 > 0f && mid.eased01 < 1f)
            assertTrue(!mid.done)
            // A re-switch mid-blend is a new instance: progress restarts
            // at 0 while the retired instance keeps its own age.
            val fresh = TransitionProgress(transition(SceneTransitionType.WIPE_LEFT), start + 100L)
            assertEquals(0f, fresh.sampleAt(start + 100L).eased01)
            assertTrue(
                old.sampleAt(start + 250L).eased01 > fresh.sampleAt(start + 250L).eased01
            )
            start += 100L
        }
    }

    // ---- Controller stress: cycles + window balance ----

    @Test
    fun fiftyStartStopCyclesAllTeardownComplete() {
        val controller = SceneCompositorController()
        repeat(50) { round ->
            assertTrue("round $round start", controller.requestStart())
            val handle = controller.onFrameArrival(frame(round.toLong()))
            assertTrue("round $round accepted", handle != null)
            assertTrue("round $round complete", controller.onFrameComplete(handle))
            val log = mutableListOf<String>()
            controller.performTeardown(recordingSteps(log))
            assertTrue("round $round complete", controller.isFullyReleased)
            assertEquals("round $round six steps", 6, log.size)
            assertTrue("round $round stop", controller.requestStop())
        }
        assertEquals(50L, controller.acceptedFrames)
        assertEquals(0, controller.inFlightFrames)
    }

    @Test
    fun frameWindowBalancesOver200Frames() {
        val controller = SceneCompositorController()
        assertTrue(controller.requestStart())
        repeat(200) { seq ->
            val handle = controller.onFrameArrival(frame(seq.toLong()))
            assertTrue("frame $seq accepted", handle != null)
            assertTrue("frame $seq completed", controller.onFrameComplete(handle))
        }
        assertEquals(0, controller.inFlightFrames)
        assertEquals(200L, controller.acceptedFrames)
        assertEquals(0L, controller.droppedFrames)
        assertEquals(0L, controller.rejectedFrames)
    }

    @Test
    fun rejectedFramesNeverTouchWindow() {
        val controller = SceneCompositorController()
        repeat(100) { seq ->
            assertTrue("reject $seq", controller.onFrameArrival(frame(seq.toLong())) == null)
        }
        assertEquals(100L, controller.rejectedFrames)
        assertEquals(0L, controller.acceptedFrames)
        assertEquals(0, controller.inFlightFrames)
        assertTrue(controller.requestStart())
        val handle = controller.onFrameArrival(frame(1000L))
        assertTrue(handle != null)
        assertTrue(controller.onFrameComplete(handle))
        assertEquals(1L, controller.acceptedFrames)
    }

    // ---- Playback controller stress: cycles never stuck ----

    @Test
    fun fiftyFullPlaybackCyclesNeverStuck() {
        val controller = SceneVideoPlaybackController()
        repeat(50) { round ->
            assertTrue("round $round assigned", controller.onSlotAssigned())
            assertTrue("round $round playing", controller.onPlaying())
            assertTrue("round $round paused", controller.onPaused())
            assertTrue("round $round resumed", controller.onPlaying())
            assertTrue("round $round error", controller.onError("cycle $round"))
            assertEquals("cycle $round reason", "cycle $round", controller.failureReason)
            assertTrue("round $round rebuild", controller.onRebuild())
            assertTrue("round $round reason cleared", controller.failureReason == null)
        }
        assertEquals(SceneVideoState.NOT_LOADED, controller.state)
    }

    @Test
    fun pauseResumeFlapsSettle() {
        val controller = SceneVideoPlaybackController()
        assertTrue(controller.onSlotAssigned())
        assertTrue(controller.onPlaying())
        repeat(100) { flap ->
            assertTrue("flap $flap pause", controller.onPaused())
            assertTrue("flap $flap resume", controller.onPlaying())
        }
        assertEquals(SceneVideoState.PLAYING, controller.state)
    }

    // ---- Planner chains: rapid alternation ----

    @Test
    fun rapidAlternationAlwaysInPlace() {
        var current: SwitchCurrent = SwitchCurrent.SceneConfig(sceneA)
        repeat(50) { round ->
            val next = if (round % 2 == 0) sceneB else sceneA
            val action = SceneSwitchPlanner.resolve(current, next)
            assertEquals("round $round in place", SwitchAction.IN_PLACE, action)
            current = SwitchCurrent.SceneConfig(next)
        }
    }

    @Test
    fun repeatSameConfigAlwaysNoop() {
        val current = SwitchCurrent.SceneConfig(sceneA)
        repeat(50) { round ->
            assertEquals("round $round noop", SwitchAction.NOOP, SceneSwitchPlanner.resolve(current, sceneA))
        }
    }

    // ---- Codec backward compatibility sweep: v3/v4/v5 shapes ----

    private fun styledLayer(versionFields: String): String =
        listOf(
            "t", "Hi", "TOP_LEFT", "1", "8", "640", "1.0", "true",
            "TEXT", "", "FFFFFFFF", "", "2", "FF000000", "4", "2", "FF000000"
        ).joinToString("\t") + versionFields

    @Test
    fun v3LayerDecodesWithStyleAndFitDefault() {
        val decoded = SceneConfigCodec.decodeScene(
            "v3\nscene:s\tS\nlayer:" + styledLayer("")
        )
        assertTrue(decoded != null)
        assertEquals(1, decoded!!.layers.size)
        val layer = decoded.layers[0]
        assertEquals("Hi", layer.text)
        assertEquals(2, layer.borderWidthPx)
        assertEquals(4, layer.cornerRadiusPx)
        // v1–v4 predate scale modes (FIT) and animation (static).
        assertEquals(SceneScaleMode.FIT, layer.scaleMode)
        assertTrue(layer.animation == null)
        assertEquals(SceneDesignOrientation.LANDSCAPE, decoded.orientation)
    }

    @Test
    fun v4LayerCarriesOrientation() {
        val decoded = SceneConfigCodec.decodeScene(
            "v4\nscene:s\tS\tPORTRAIT\nlayer:" + styledLayer("")
        )
        assertTrue(decoded != null)
        assertEquals(SceneDesignOrientation.PORTRAIT, decoded!!.orientation)
        assertEquals(1, decoded.layers.size)
        assertEquals(SceneScaleMode.FIT, decoded.layers[0].scaleMode)
        assertTrue(decoded.layers[0].animation == null)
    }

    @Test
    fun v5LayerDecodesScaleMode() {
        val decoded = SceneConfigCodec.decodeScene(
            "v5\nscene:s\tS\tLANDSCAPE\nlayer:" + styledLayer("\tFILL")
        )
        assertTrue(decoded != null)
        assertEquals(SceneScaleMode.FILL, decoded!!.layers[0].scaleMode)
        assertEquals(2, decoded.layers[0].borderWidthPx)
        assertTrue(decoded.layers[0].animation == null)
        // A corrupt v5 scale token drops the layer, keeps the scene.
        val corrupt = SceneConfigCodec.decodeScene(
            "v5\nscene:s\tS\tLANDSCAPE\nlayer:" + styledLayer("\tBOGUS")
        )
        assertTrue(corrupt != null)
        assertEquals(0, corrupt!!.layers.size)
    }
}
