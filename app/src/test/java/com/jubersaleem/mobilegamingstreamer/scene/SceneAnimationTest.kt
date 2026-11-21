package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * J3 animation tests: config validation, codec round-trip + legacy
 * back-compat, time-driven clock, easings, preset geometry, NDC
 * mapping parity with `ndcQuad`, both orientations, and invalid
 * input collapse. Float checks use epsilon (never 2-arg float
 * assertEquals, which real JUnit rejects without a delta).
 */
class SceneAnimationTest {

    private fun assertNear(message: String, expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun anim(
        preset: SceneAnimPreset = SceneAnimPreset.FADE_IN,
        durationMs: Int = 1000,
        delayMs: Int = 0,
        repeat: SceneAnimRepeat = SceneAnimRepeat.ONCE,
        easing: SceneAnimEasing = SceneAnimEasing.LINEAR
    ): SceneLayerAnimation = SceneLayerAnimation.of(preset, durationMs, delayMs, repeat, easing)!!

    private val rect = SceneRectPx(27, 27, 500, 200)

    // ---- Config validation + codec ----

    @Test
    fun ofRejectsGarbage() {
        assertNull(SceneLayerAnimation.of(SceneAnimPreset.NONE, 1000, 0, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR))
        assertNull(SceneLayerAnimation.of(SceneAnimPreset.FADE_IN, 99, 0, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR))
        assertNull(SceneLayerAnimation.of(SceneAnimPreset.FADE_IN, 10001, 0, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR))
        assertNull(SceneLayerAnimation.of(SceneAnimPreset.FADE_IN, 1000, -1, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR))
        assertNull(SceneLayerAnimation.of(SceneAnimPreset.FADE_IN, 1000, 10001, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR))
        assertTrue(SceneLayerAnimation.of(SceneAnimPreset.SPIN, 100, 0, SceneAnimRepeat.LOOP, SceneAnimEasing.LINEAR) != null)
        assertTrue(SceneLayerAnimation.of(SceneAnimPreset.ZOOM, 10000, 10000, SceneAnimRepeat.ONCE, SceneAnimEasing.EASE_IN_OUT) != null)
    }

    @Test
    fun parseRoundTripsAndRejectsGarbage() {
        val a = anim(SceneAnimPreset.SLIDE_IN_LEFT, 750, 250, SceneAnimRepeat.LOOP, SceneAnimEasing.EASE_OUT)
        assertEquals("SLIDE_IN_LEFT,750,250,LOOP,EASE_OUT", a.encode())
        assertEquals(a, SceneLayerAnimation.parse(a.encode()))
        for (bad in listOf(
            "", "FADE_IN", "FADE_IN,1000,0,ONCE", "NONE,1000,0,ONCE,LINEAR",
            "FADE_IN,99,0,ONCE,LINEAR", "FADE_IN,1000,0,FOREVER,LINEAR",
            "FADE_IN,1000,0,ONCE,SMOOTH", "NOPE,1000,0,ONCE,LINEAR",
            "FADE_IN,abc,0,ONCE,LINEAR", "FADE_IN,1000,0,ONCE,LINEAR,EXTRA"
        )) {
            assertTrue("token <$bad>", SceneLayerAnimation.parse(bad) == null)
        }
    }

    @Test
    fun codecV6RoundTripsAnimation() {
        val layer = StreamSceneLayer(
            "a", "Hi", SceneOverlayAnchor.TOP_LEFT, 1, 8, 1920, 1f, true,
            animation = anim(SceneAnimPreset.FADE_IN, 500, 100)
        )
        val scene = StreamScene("s", "S", listOf(layer))
        val encoded = SceneConfigCodec.encodeScene(scene)
        assertTrue(encoded.startsWith("v6\n"))
        assertTrue(encoded.endsWith("\tFADE_IN,500,100,ONCE,LINEAR"))
        assertEquals(scene, SceneConfigCodec.decodeScene(encoded))
    }

    @Test
    fun legacyVersionsDecodeStatic() {
        val v1 = SceneConfigCodec.decodeScene("v1\nscene:s\tS\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue")!!
        assertNull(v1.layers.single().animation)
        val v5line = "layer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tTEXT\t\t" +
            "FFFFFFFF\t\t0\tFF000000\t0\t0\tFF000000\tFIT"
        val v5 = SceneConfigCodec.decodeScene("v5\nscene:s\tS\tLANDSCAPE\n" + v5line)!!
        assertNull(v5.layers.single().animation)
        // Static layers encode with an empty anim field.
        assertTrue(SceneConfigCodec.encodeScene(v5).endsWith("\tFIT\t"))
    }

    @Test
    fun corruptAnimTokenDropsLayerKeepsScene() {
        val good = "layer:good\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue\tTEXT\t\t" +
            "FFFFFFFF\t\t0\tFF000000\t0\t0\tFF000000\tFIT\tFADE_IN,500,0,ONCE,LINEAR"
        val bad = good.replace("good", "bad").replace("FADE_IN,500,0,ONCE,LINEAR", "WOBBLE,500,0,ONCE,LINEAR")
        val scene = SceneConfigCodec.decodeScene("v6\nscene:s\tS\tLANDSCAPE\n" + good + "\n" + bad)!!
        assertEquals(listOf("good"), scene.layers.map { it.id })
        assertEquals(anim(SceneAnimPreset.FADE_IN, 500, 0), scene.layers.single().animation)
    }

    @Test
    fun animationReachesOverlaySpec() {
        val a = anim(SceneAnimPreset.PULSE, 2000, 0, SceneAnimRepeat.LOOP)
        val layer = StreamSceneLayer(
            "v", "", SceneOverlayAnchor.CENTER, 1, 8, 1920, 1f, true,
            SceneLayerKind.VIDEO, "content://m", animation = a
        )
        assertEquals(a, layer.toOverlaySpec()!!.animation)
    }

    // ---- Clock ----

    @Test
    fun onceRunsThenRests() {
        val a = anim(durationMs = 1000, delayMs = 200)
        // In delay: start phase, still running.
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(a, 0L))
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(a, 199L))
        // Mid-run.
        val mid = SceneAnimation.phaseAt(a, 700L)!!
        assertNear("mid phase", 0.5f, mid.phase01)
        assertTrue(mid.active)
        // End rests inactive at phase 1.
        assertEquals(SceneAnimPhase(1f, false), SceneAnimation.phaseAt(a, 1200L))
        assertEquals(SceneAnimPhase(1f, false), SceneAnimation.phaseAt(a, 999999L))
        // Garbage time collapses safely.
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(a, -50L))
    }

    @Test
    fun loopWrapsForever() {
        val a = anim(durationMs = 1000, repeat = SceneAnimRepeat.LOOP)
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(a, 0L))
        val mid = SceneAnimation.phaseAt(a, 500L)!!
        assertNear("loop mid", 0.5f, mid.phase01)
        assertTrue(mid.active)
        // Wrap: t=1000 → phase 0, still active.
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(a, 1000L))
        val wrapped = SceneAnimation.phaseAt(a, 2500L)!!
        assertNear("loop wrapped", 0.5f, wrapped.phase01)
        assertTrue(wrapped.active)
    }

    @Test
    fun noneHasNoPhase() {
        val none = SceneLayerAnimation(SceneAnimPreset.NONE, 1000, 0, SceneAnimRepeat.ONCE, SceneAnimEasing.LINEAR)
        assertNull(SceneAnimation.phaseAt(none, 500L))
    }

    @Test
    fun independentClocksPerLayer() {
        val fast = anim(durationMs = 500)
        val slow = anim(durationMs = 2000, delayMs = 1000)
        // Same wall time, different phases — no shared clock state.
        assertNear("fast", 0.5f, SceneAnimation.phaseAt(fast, 250L)!!.phase01)
        assertEquals(SceneAnimPhase(0f, true), SceneAnimation.phaseAt(slow, 250L))
        assertEquals(SceneAnimPhase(1f, false), SceneAnimation.phaseAt(fast, 600L))
        assertTrue(SceneAnimation.phaseAt(slow, 1500L)!!.active)
    }

    // ---- Easing ----

    @Test
    fun easingsPinBoundariesAndMidpoints() {
        for (e in SceneAnimEasing.values()) {
            assertNear("$e at 0", 0f, SceneAnimation.ease(e, 0f), 0f)
            assertNear("$e at 1", 1f, SceneAnimation.ease(e, 1f), 0f)
        }
        assertNear("linear", 0.5f, SceneAnimation.ease(SceneAnimEasing.LINEAR, 0.5f), 0f)
        assertNear("in", 0.25f, SceneAnimation.ease(SceneAnimEasing.EASE_IN, 0.5f), 0f)
        assertNear("out", 0.75f, SceneAnimation.ease(SceneAnimEasing.EASE_OUT, 0.5f), 0f)
        assertNear("inout", 0.5f, SceneAnimation.ease(SceneAnimEasing.EASE_IN_OUT, 0.5f), 0f)
        // Clamped, monotonic spot-checks.
        assertNear("clamp", 0f, SceneAnimation.ease(SceneAnimEasing.EASE_IN, -2f), 0f)
        assertNear("clamp", 1f, SceneAnimation.ease(SceneAnimEasing.EASE_OUT, 9f), 0f)
        assertTrue(SceneAnimation.ease(SceneAnimEasing.EASE_IN, 0.25f) < 0.25f)
        assertTrue(SceneAnimation.ease(SceneAnimEasing.EASE_OUT, 0.25f) > 0.25f)
    }

    // ---- Preset geometry ----

    @Test
    fun fadesMoveAlphaOnly() {
        val i0 = SceneAnimation.transformFor(SceneAnimPreset.FADE_IN, 0f, rect, 1920, 1080)
        assertNear("in start alpha", 0f, i0.alphaMul, 0f)
        assertNear("in start dx", 0f, i0.dxPx, 0f)
        val i1 = SceneAnimation.transformFor(SceneAnimPreset.FADE_IN, 1f, rect, 1920, 1080)
        assertTrue(i1.isIdentity())
        val o1 = SceneAnimation.transformFor(SceneAnimPreset.FADE_OUT, 1f, rect, 1920, 1080)
        assertNear("out end alpha", 0f, o1.alphaMul, 0f)
        assertTrue(!o1.isIdentity())
    }

    @Test
    fun slidesTravelFullyOffCanvas() {
        // LEFT: start fully out (right edge at x<=0), end placed.
        val l0 = SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_LEFT, 0f, rect, 1920, 1080)
        assertNear("in-left start", -500f, l0.dxPx, 0f)
        assertTrue(rect.right + l0.dxPx <= 0f)
        assertTrue(SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_LEFT, 1f, rect, 1920, 1080).isIdentity())
        // RIGHT OUT: end fully out (left edge at x>=canvasW).
        val r1 = SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_RIGHT, 1f, rect, 1920, 1080)
        assertNear("out-right end", 1920f - 27f, r1.dxPx, 0f)
        assertTrue(rect.left + r1.dxPx >= 1920f)
        // TOP IN / BOTTOM OUT verticals.
        val t0 = SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_TOP, 0f, rect, 1920, 1080)
        assertTrue(rect.bottom + t0.dyPx <= 0f)
        val b1 = SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_BOTTOM, 1f, rect, 1920, 1080)
        assertTrue(rect.top + b1.dyPx >= 1080f)
        // Slides never touch scale/alpha/rotation.
        for (p in listOf(SceneAnimPreset.SLIDE_IN_TOP, SceneAnimPreset.SLIDE_OUT_LEFT)) {
            val t = SceneAnimation.transformFor(p, 0.5f, rect, 1920, 1080)
            assertNear("slide scale", 1f, t.scale, 0f)
            assertNear("slide alpha", 1f, t.alphaMul, 0f)
            assertNear("slide rot", 0f, t.rotationDeg, 0f)
        }
    }

    @Test
    fun slidesTravelFullyOffCanvasPortrait() {
        // 1080x1920 portrait: every slide preset parks the rect fully
        // outside the canvas at its out-end (edges may touch).
        val w = 1080
        val h = 1920
        val r = SceneRectPx(27, 1300, 900, 1600)
        val outs = mapOf(
            SceneAnimPreset.SLIDE_IN_LEFT to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_LEFT, 0f, r, w, h),
            SceneAnimPreset.SLIDE_IN_RIGHT to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_RIGHT, 0f, r, w, h),
            SceneAnimPreset.SLIDE_IN_TOP to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_TOP, 0f, r, w, h),
            SceneAnimPreset.SLIDE_IN_BOTTOM to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_BOTTOM, 0f, r, w, h),
            SceneAnimPreset.SLIDE_OUT_LEFT to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_LEFT, 1f, r, w, h),
            SceneAnimPreset.SLIDE_OUT_RIGHT to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_RIGHT, 1f, r, w, h),
            SceneAnimPreset.SLIDE_OUT_TOP to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_TOP, 1f, r, w, h),
            SceneAnimPreset.SLIDE_OUT_BOTTOM to
                SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_BOTTOM, 1f, r, w, h)
        )
        assertTrue(r.right + outs.getValue(SceneAnimPreset.SLIDE_IN_LEFT).dxPx <= 0f)
        assertTrue(r.left + outs.getValue(SceneAnimPreset.SLIDE_IN_RIGHT).dxPx >= w.toFloat())
        assertTrue(r.bottom + outs.getValue(SceneAnimPreset.SLIDE_IN_TOP).dyPx <= 0f)
        assertTrue(r.top + outs.getValue(SceneAnimPreset.SLIDE_IN_BOTTOM).dyPx >= h.toFloat())
        assertTrue(r.right + outs.getValue(SceneAnimPreset.SLIDE_OUT_LEFT).dxPx <= 0f)
        assertTrue(r.left + outs.getValue(SceneAnimPreset.SLIDE_OUT_RIGHT).dxPx >= w.toFloat())
        assertTrue(r.bottom + outs.getValue(SceneAnimPreset.SLIDE_OUT_TOP).dyPx <= 0f)
        assertTrue(r.top + outs.getValue(SceneAnimPreset.SLIDE_OUT_BOTTOM).dyPx >= h.toFloat())
    }

    @Test
    fun onceEndStatesRest() {
        // What the stream holds after a ONCE run finishes: outs rest
        // hidden, ins rest placed. The ticker disarms; the base quad
        // is NOT redrawn over the end state mid-stream... rather the
        // sampler keeps serving the phase-1 transform (active=false).
        val fadeOut = SceneAnimation.transformFor(SceneAnimPreset.FADE_OUT, 1f, rect, 1920, 1080)
        assertNear("fade-out rests invisible", 0f, fadeOut.alphaMul, 0f)
        val scaleOut = SceneAnimation.transformFor(SceneAnimPreset.SCALE_OUT, 1f, rect, 1920, 1080)
        assertNear("scale-out rests zero size", 0f, scaleOut.scale, 0f)
        assertNear("scale-out rests invisible", 0f, scaleOut.alphaMul, 0f)
        val slideOut = SceneAnimation.transformFor(SceneAnimPreset.SLIDE_OUT_LEFT, 1f, rect, 1920, 1080)
        assertTrue(rect.right + slideOut.dxPx <= 0f)
        assertTrue(SceneAnimation.transformFor(SceneAnimPreset.FADE_IN, 1f, rect, 1920, 1080).isIdentity())
        assertTrue(SceneAnimation.transformFor(SceneAnimPreset.SLIDE_IN_RIGHT, 1f, rect, 1920, 1080).isIdentity())
    }

    @Test
    fun scalesPulseZoomSpin() {
        val si = SceneAnimation.transformFor(SceneAnimPreset.SCALE_IN, 0.5f, rect, 1920, 1080)
        assertNear("scale-in s", 0.5f, si.scale, 0f)
        assertNear("scale-in a", 0.5f, si.alphaMul, 0f)
        val so = SceneAnimation.transformFor(SceneAnimPreset.SCALE_OUT, 0.5f, rect, 1920, 1080)
        assertNear("scale-out s", 0.5f, so.scale, 0f)
        // Pulse rests at 1.0 at both ends (seamless loop), peaks mid.
        assertNear("pulse 0", 1f, SceneAnimation.transformFor(SceneAnimPreset.PULSE, 0f, rect, 1920, 1080).scale, 0f)
        assertNear("pulse 1", 1f, SceneAnimation.transformFor(SceneAnimPreset.PULSE, 1f, rect, 1920, 1080).scale)
        assertNear("pulse peak", 1.08f, SceneAnimation.transformFor(SceneAnimPreset.PULSE, 0.25f, rect, 1920, 1080).scale)
        assertNear("zoom end", 1.15f, SceneAnimation.transformFor(SceneAnimPreset.ZOOM, 1f, rect, 1920, 1080).scale)
        assertNear("spin end", 360f, SceneAnimation.transformFor(SceneAnimPreset.SPIN, 1f, rect, 1920, 1080).rotationDeg)
    }

    @Test
    fun driftsAreDeterministicFromCanvas() {
        // min(1920,1080)/12 = 90px.
        val d = SceneAnimation.transformFor(SceneAnimPreset.DRIFT_RIGHT, 1f, rect, 1920, 1080)
        assertNear("drift right", 90f, d.dxPx, 0f)
        val l = SceneAnimation.transformFor(SceneAnimPreset.DRIFT_LEFT, 1f, rect, 1920, 1080)
        assertNear("drift left", -90f, l.dxPx, 0f)
        // Portrait canvas: min(1080,1920)/12 = 90px too; square differs.
        val s = SceneAnimation.transformFor(SceneAnimPreset.DRIFT_DOWN, 1f, rect, 600, 600)
        assertNear("drift square", 50f, s.dyPx, 0f)
    }

    // ---- NDC mapping ----

    @Test
    fun identityReproducesNdcQuadExactly() {
        for (canvas in listOf(SceneCanvas(1920, 1080), SceneCanvas(1080, 1920))) {
            val expected = SceneTextLayerLayout.ndcQuad(rect, canvas.widthPx, canvas.heightPx)!!
            val out = FloatArray(8)
            SceneAnimation.writeTransformedNdc(
                rect, SceneAnimTransform.IDENTITY, canvas.widthPx, canvas.heightPx, out
            )
            assertEquals(expected.toList(), out.toList())
        }
    }

    @Test
    fun translationShiftsNdc() {
        // +960px on a 1920 canvas = +1.0 NDC on every x vertex.
        val out = FloatArray(8)
        SceneAnimation.writeTransformedNdc(
            rect, SceneAnimTransform(960f, 0f, 1f, 1f, 0f), 1920, 1080, out
        )
        val base = SceneTextLayerLayout.ndcQuad(rect, 1920, 1080)!!
        for (i in listOf(0, 2, 4, 6)) assertNear("x[$i]", base[i] + 1f, out[i])
        for (i in listOf(1, 3, 5, 7)) assertNear("y[$i]", base[i], out[i])
    }

    @Test
    fun rotation90PermutesSquareCorners() {
        // 100x100 box on a 1000x1000 canvas, 90° about center.
        val box = SceneRectPx(100, 100, 200, 200)
        val out = FloatArray(8)
        SceneAnimation.writeTransformedNdc(
            box, SceneAnimTransform(0f, 0f, 1f, 1f, 90f), 1000, 1000, out
        )
        // Corner set preserved (permuted), center fixed: average is center NDC.
        var sx = 0f
        var sy = 0f
        for (i in 0..3) {
            sx += out[i * 2]
            sy += out[i * 2 + 1]
        }
        assertNear("cx", (150f / 1000f) * 2f - 1f, sx / 4f, 1e-4f)
        assertNear("cy", 1f - (150f / 1000f) * 2f, sy / 4f, 1e-4f)
    }

    @Test
    fun invalidInputCollapsesSafely() {
        val out = FloatArray(8) { 7f }
        SceneAnimation.writeTransformedNdc(rect, SceneAnimTransform.IDENTITY, 0, 1080, out)
        assertEquals(List(8) { 0f }, out.toList())
        val short = FloatArray(4) { 7f }
        SceneAnimation.writeTransformedNdc(rect, SceneAnimTransform.IDENTITY, 1920, 1080, short)
        assertEquals(List(4) { 0f }, short.toList())
        // NaN transform collapses, never propagates.
        val nan = FloatArray(8)
        SceneAnimation.writeTransformedNdc(
            rect, SceneAnimTransform(Float.NaN, 0f, 1f, 1f, 0f), 1920, 1080, nan
        )
        for (f in nan) assertTrue(f.isFinite())
    }
}
