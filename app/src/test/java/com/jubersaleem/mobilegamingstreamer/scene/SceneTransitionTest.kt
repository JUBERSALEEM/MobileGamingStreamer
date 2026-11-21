package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * J4 transition tests: config validation, token round-trip, the
 * elapsed-time clock, easing reuse through the shared J3 function,
 * per-effect curves (crossfade, fade-through-black, wipe scissors in
 * GL Y-up coords, zoom, J3-shared slide offsets) in both
 * orientations, the snapshot budget gate, service/prefs fallback,
 * and v6 codec regression. Float checks use epsilon (never 2-arg
 * float assertEquals, which real JUnit rejects without a delta).
 */
class SceneTransitionTest {

    private fun assertNear(message: String, expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun transition(
        type: SceneTransitionType = SceneTransitionType.CROSSFADE,
        durationMs: Int = 500,
        easing: SceneAnimEasing = SceneAnimEasing.LINEAR
    ): SceneTransition = SceneTransition.of(type, durationMs, easing)!!

    // ---- Config validation + token codec ----

    @Test
    fun defaultIsCut() {
        assertEquals(SceneTransitionType.CUT, SceneTransition.DEFAULT.type)
        assertTrue(SceneTransition.DEFAULT.isInstant())
    }

    @Test
    fun durationBoundsPinned() {
        assertNull(SceneTransition.of(SceneTransitionType.CROSSFADE, 199, SceneAnimEasing.LINEAR))
        assertTrue(SceneTransition.of(SceneTransitionType.CROSSFADE, 200, SceneAnimEasing.LINEAR) != null)
        assertTrue(SceneTransition.of(SceneTransitionType.CROSSFADE, 2000, SceneAnimEasing.LINEAR) != null)
        assertNull(SceneTransition.of(SceneTransitionType.CROSSFADE, 2001, SceneAnimEasing.LINEAR))
        assertNull(SceneTransition.of(SceneTransitionType.CROSSFADE, -50, SceneAnimEasing.LINEAR))
    }

    @Test
    fun tokenRoundTripsAndRejectsGarbage() {
        val t = transition(
            SceneTransitionType.WIPE_LEFT, 750, SceneAnimEasing.EASE_IN_OUT
        )
        assertEquals("WIPE_LEFT,750,EASE_IN_OUT", t.encode())
        assertEquals(t, SceneTransition.parse(t.encode()))
        assertEquals(
            SceneTransition(SceneTransitionType.CUT, 500, SceneAnimEasing.EASE_IN_OUT),
            SceneTransition.parse("CUT,500,EASE_IN_OUT")
        )
        for (bad in listOf(
            "", "CUT", "CUT,500", "CUT,500,EASE_IN_OUT,EXTRA",
            "NOPE,500,LINEAR", "CUT,abc,LINEAR", "CUT,500,NOPE",
            "CUT,199,LINEAR", "CUT,2001,LINEAR", "cut,500,LINEAR"
        )) {
            assertTrue("token must reject: $bad", SceneTransition.parse(bad) == null)
        }
    }

    @Test
    fun parseExtraFallsBackToCut() {
        assertEquals(SceneTransition.DEFAULT, SceneTransitions.parseExtra(null))
        assertEquals(SceneTransition.DEFAULT, SceneTransitions.parseExtra(""))
        assertEquals(SceneTransition.DEFAULT, SceneTransitions.parseExtra("garbage"))
        assertEquals(SceneTransition.DEFAULT, SceneTransitions.parseExtra("CUT,50,LINEAR"))
        assertEquals(
            transition(SceneTransitionType.ZOOM_IN, 600, SceneAnimEasing.EASE_OUT),
            SceneTransitions.parseExtra("ZOOM_IN,600,EASE_OUT")
        )
    }

    // ---- Elapsed-time clock ----

    @Test
    fun progressClampsAndCompletes() {
        val t = transition(durationMs = 500)
        assertNear("start", 0f, SceneTransitions.progressAt(t, 0L), 0f)
        assertNear("negative", 0f, SceneTransitions.progressAt(t, -100L), 0f)
        assertNear("mid", 0.5f, SceneTransitions.progressAt(t, 250L))
        assertNear("end", 1f, SceneTransitions.progressAt(t, 500L), 0f)
        assertNear("over", 1f, SceneTransitions.progressAt(t, 99999L), 0f)
        assertTrue(!SceneTransitions.isDoneAt(t, 499L))
        assertTrue(SceneTransitions.isDoneAt(t, 500L))
        // CUT never runs the clock (instantly done, defensively).
        assertNear("cut", 1f, SceneTransitions.progressAt(SceneTransition.DEFAULT, 0L), 0f)
        assertTrue(SceneTransitions.isDoneAt(SceneTransition.DEFAULT, 0L))
    }

    @Test
    fun easingReusedThroughSharedFunction() {
        // Boundaries hold for every easing; midpoints prove the J3
        // curve is actually applied (not a reimplementation).
        for (easing in SceneAnimEasing.values()) {
            val t = transition(durationMs = 1000, easing = easing)
            assertNear("$easing start", 0f, SceneTransitions.easedProgress(t, 0L), 0f)
            assertNear("$easing end", 1f, SceneTransitions.easedProgress(t, 1000L), 0f)
            assertNear(
                "$easing mid",
                SceneAnimation.ease(easing, 0.5f),
                SceneTransitions.easedProgress(t, 500L), 0f
            )
        }
        val out = transition(durationMs = 1000, easing = SceneAnimEasing.EASE_OUT)
        assertTrue(SceneTransitions.easedProgress(out, 500L) > 0.5f)
        val into = transition(durationMs = 1000, easing = SceneAnimEasing.EASE_IN)
        assertTrue(SceneTransitions.easedProgress(into, 500L) < 0.5f)
    }

    @Test
    fun progressClockIsIndependentPerSwitch() {
        // A rapid re-switch is a new clock: progress restarts at 0
        // while the old clock reads done — no shared timing state.
        val old = TransitionProgress(transition(durationMs = 500), startElapsedMs = 1000L)
        val sample = old.sampleAt(1600L)
        assertTrue(sample.done)
        assertNear("old done", 1f, sample.eased01, 0f)
        val fresh = TransitionProgress(transition(durationMs = 500), startElapsedMs = 1600L)
        val restarted = fresh.sampleAt(1600L)
        assertTrue(!restarted.done)
        assertNear("fresh restarts", 0f, restarted.eased01, 0f)
        // J3 anim phase at the same instant is unaffected (separate
        // clocks sharing only the wall reading).
        val anim = SceneLayerAnimation.of(
            SceneAnimPreset.FADE_IN, 1000, 0, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR
        )!!
        assertNear("anim independent", 0.6f, SceneAnimation.phaseAt(anim, 600L)!!.phase01)
    }

    // ---- Effect curves ----

    @Test
    fun crossfadeAlphaFallsLinearly() {
        assertNear("start", 1f, SceneTransitions.crossfadeAlpha(0f), 0f)
        assertNear("mid", 0.5f, SceneTransitions.crossfadeAlpha(0.5f), 0f)
        assertNear("end", 0f, SceneTransitions.crossfadeAlpha(1f), 0f)
        assertNear("clamped", 1f, SceneTransitions.crossfadeAlpha(-2f), 0f)
        assertNear("clamped", 0f, SceneTransitions.crossfadeAlpha(2f), 0f)
    }

    @Test
    fun fadeThroughBlackSplitsAtMidpoint() {
        val start = SceneTransitions.fadeThroughBlack(0f)
        assertTrue(start.outgoingPhase)
        assertNear("snapshot full", 1f, start.topAlpha, 0f)
        val quarter = SceneTransitions.fadeThroughBlack(0.25f)
        assertTrue(quarter.outgoingPhase)
        assertNear("snapshot half", 0.5f, quarter.topAlpha, 0f)
        // Midpoint is black from both sides (continuous handoff).
        val midIn = SceneTransitions.fadeThroughBlack(0.4999f)
        assertTrue(midIn.outgoingPhase)
        assertTrue(midIn.topAlpha < 0.01f)
        val midOut = SceneTransitions.fadeThroughBlack(0.5f)
        assertTrue(!midOut.outgoingPhase)
        assertNear("black full", 1f, midOut.topAlpha, 0f)
        val threeQuarter = SceneTransitions.fadeThroughBlack(0.75f)
        assertTrue(!threeQuarter.outgoingPhase)
        assertNear("black half", 0.5f, threeQuarter.topAlpha, 0f)
        val end = SceneTransitions.fadeThroughBlack(1f)
        assertTrue(!end.outgoingPhase)
        assertNear("incoming full", 0f, end.topAlpha, 0f)
    }

    @Test
    fun wipeScissorsLandscape() {
        val w = 1920
        val h = 1080
        // Start: nothing revealed. End: everything revealed.
        assertEquals(WipeScissor(1920, 0, 0, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_LEFT, 0f, w, h))
        assertEquals(WipeScissor(0, 0, 1920, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_LEFT, 1f, w, h))
        assertEquals(WipeScissor(0, 0, 0, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_RIGHT, 0f, w, h))
        assertEquals(WipeScissor(0, 0, 1920, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_RIGHT, 1f, w, h))
        // Y-up: WIPE_UP reveals the bottom half at midpoint (y=0 is
        // the framebuffer bottom); WIPE_DOWN reveals the top half.
        assertEquals(WipeScissor(0, 0, 1920, 540),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_UP, 0.5f, w, h))
        assertEquals(WipeScissor(0, 540, 1920, 540),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_DOWN, 0.5f, w, h))
        assertEquals(WipeScissor(960, 0, 960, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_LEFT, 0.5f, w, h))
        assertEquals(WipeScissor(0, 0, 960, 1080),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_RIGHT, 0.5f, w, h))
        // Non-wipe types have no scissor.
        assertNull(SceneTransitions.wipeScissor(SceneTransitionType.CROSSFADE, 0.5f, w, h))
        assertNull(SceneTransitions.wipeScissor(SceneTransitionType.CUT, 0.5f, w, h))
    }

    @Test
    fun wipeScissorsPortraitAndClamped() {
        val w = 1080
        val h = 1920
        assertEquals(WipeScissor(540, 0, 540, 1920),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_LEFT, 0.5f, w, h))
        assertEquals(WipeScissor(0, 960, 1080, 960),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_DOWN, 0.5f, w, h))
        // Garbage progress can never produce a negative or overflowing
        // rect (glScissor would misbehave).
        for (e in listOf(-1f, 0f, 0.33f, 1f, 2f, Float.NaN)) {
            for (type in listOf(
                SceneTransitionType.WIPE_LEFT, SceneTransitionType.WIPE_RIGHT,
                SceneTransitionType.WIPE_UP, SceneTransitionType.WIPE_DOWN
            )) {
                val r = SceneTransitions.wipeScissor(type, e, w, h)!!
                assertTrue("$type@$e in bounds: $r",
                    r.x in 0..w && r.y in 0..h && r.w in 0..w && r.h in 0..h &&
                        r.x + r.w <= w && r.y + r.h <= h)
            }
        }
        // Invalid canvas collapses to the empty rect.
        assertEquals(WipeScissor(0, 0, 0, 0),
            SceneTransitions.wipeScissor(SceneTransitionType.WIPE_UP, 0.5f, 0, 1080))
    }

    @Test
    fun wipeScissorTopDownMirrorsYUp() {
        val w = 1920
        val h = 1080
        // Same visual regions as the Y-up pins, in top-down pixels
        // (Y-up bottom-half = top-down rows 540..1080).
        assertEquals(WipeScissor(0, 540, 1920, 540),
            SceneTransitions.wipeScissorTopDown(SceneTransitionType.WIPE_UP, 0.5f, w, h))
        assertEquals(WipeScissor(0, 0, 1920, 540),
            SceneTransitions.wipeScissorTopDown(SceneTransitionType.WIPE_DOWN, 0.5f, w, h))
        assertEquals(WipeScissor(960, 0, 960, 1080),
            SceneTransitions.wipeScissorTopDown(SceneTransitionType.WIPE_LEFT, 0.5f, w, h))
        assertEquals(WipeScissor(0, 0, 1920, 1080),
            SceneTransitions.wipeScissorTopDown(SceneTransitionType.WIPE_RIGHT, 1f, w, h))
        assertNull(SceneTransitions.wipeScissorTopDown(
            SceneTransitionType.CROSSFADE, 0.5f, w, h))
    }

    @Test
    fun zoomCurvesBothDirections() {
        val inStart = SceneTransitions.zoomFor(SceneTransitionType.ZOOM_IN, 0f)!!
        assertNear("in start scale", 1f, inStart.scale, 0f)
        assertNear("in start alpha", 1f, inStart.alpha, 0f)
        val inEnd = SceneTransitions.zoomFor(SceneTransitionType.ZOOM_IN, 1f)!!
        assertNear("in end scale", 1.2f, inEnd.scale, 0f)
        assertNear("in end alpha", 0f, inEnd.alpha, 0f)
        val outEnd = SceneTransitions.zoomFor(SceneTransitionType.ZOOM_OUT, 1f)!!
        assertNear("out end scale", 0.8f, outEnd.scale, 0f)
        assertNear("out end alpha", 0f, outEnd.alpha, 0f)
        // Scale stays positive everywhere (never degenerate/inverted).
        for (e in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(SceneTransitions.zoomFor(SceneTransitionType.ZOOM_IN, e)!!.scale > 0f)
            assertTrue(SceneTransitions.zoomFor(SceneTransitionType.ZOOM_OUT, e)!!.scale > 0f)
        }
        assertNull(SceneTransitions.zoomFor(SceneTransitionType.SLIDE_LEFT, 0.5f))
    }

    @Test
    fun slideOffsetsReuseJ3FullyOffBothOrientations() {
        // Landscape: LEFT exits toward -x (right edge at x<=0 at e=1).
        val land = SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_LEFT, 1f, 1920, 1080)!!
        assertNear("land off", -1920f, land.dxPx, 0f)
        assertTrue(1920f + land.dxPx <= 0f)
        val landRight = SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_RIGHT, 1f, 1920, 1080)!!
        assertTrue(0f + landRight.dxPx >= 1920f)
        // Portrait: verticals travel the full height.
        val portUp = SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_UP, 1f, 1080, 1920)!!
        assertTrue(1920f + portUp.dyPx <= 0f)
        val portDown = SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_DOWN, 1f, 1080, 1920)!!
        assertTrue(0f + portDown.dyPx >= 1920f)
        // Start is identity; non-slides have no transform.
        assertTrue(SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_LEFT, 0f, 1920, 1080)!!.isIdentity())
        assertNull(SceneTransitions.slideTransform(
            SceneTransitionType.CROSSFADE, 0.5f, 1920, 1080))
        assertNull(SceneTransitions.slideTransform(
            SceneTransitionType.SLIDE_LEFT, 0.5f, 0, 1080))
    }

    // ---- Budget gate ----

    @Test
    fun snapshotBudgetGate() {
        assertEquals(1920L * 1080L * 4L, SceneTransitions.snapshotBytesFor(1920, 1080))
        assertEquals(1080L * 1920L * 4L, SceneTransitions.snapshotBytesFor(1080, 1920))
        assertEquals(0L, SceneTransitions.snapshotBytesFor(0, 1080))
        assertEquals(0L, SceneTransitions.snapshotBytesFor(-4, 1080))
        // 1080p and 1440p outputs fit; absurd outputs degrade to CUT.
        assertTrue(SceneTransitions.allowsSnapshot(1920, 1080))
        assertTrue(SceneTransitions.allowsSnapshot(1080, 1920))
        assertTrue(SceneTransitions.allowsSnapshot(2560, 1440))
        assertTrue(!SceneTransitions.allowsSnapshot(7680, 4320))
        assertTrue(!SceneTransitions.allowsSnapshot(0, 1080))
        // Hostile dims cannot overflow into a small allow (Long math).
        assertTrue(!SceneTransitions.allowsSnapshot(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    // ---- Regression: scene codec untouched ----

    @Test
    fun sceneCodecV6RoundTripIntact() {
        val layer = StreamSceneLayer(
            id = "a", text = "Hello", anchor = SceneOverlayAnchor.TOP_LEFT,
            zOrder = 1, marginPx = 8, maxWidthPx = 400, alpha = 1f,
            kind = SceneLayerKind.TEXT,
            animation = SceneLayerAnimation.of(
                SceneAnimPreset.FADE_IN, 1000, 0,
                SceneAnimRepeat.ONCE, SceneAnimEasing.EASE_OUT
            )
        )
        val scene = StreamScene(
            id = "s", name = "N", layers = listOf(layer),
            orientation = SceneDesignOrientation.LANDSCAPE
        )
        val encoded = SceneConfigCodec.encodeScene(scene)
        assertTrue(encoded.startsWith("v6\n"))
        val decoded = SceneConfigCodec.decodeScene(encoded)!!
        assertEquals(scene, decoded)
    }

    @Test
    fun transitionConfigNeverEntersSceneEncoding() {
        // The transition is app-global (prefs + intent), never
        // per-scene: encodings are byte-identical with or without J4.
        val scene = StreamScene(
            id = "s", name = "N",
            layers = listOf(
                StreamSceneLayer(
                    id = "a", text = "Hi", anchor = SceneOverlayAnchor.CENTER,
                    zOrder = 1, marginPx = 0, maxWidthPx = 200, alpha = 0.5f
                )
            ),
            orientation = SceneDesignOrientation.PORTRAIT
        )
        val first = SceneConfigCodec.encodeScene(scene)
        val second = SceneConfigCodec.encodeScene(scene.copy())
        assertEquals(first, second)
        assertTrue(!first.contains("CROSSFADE"))
    }
}
