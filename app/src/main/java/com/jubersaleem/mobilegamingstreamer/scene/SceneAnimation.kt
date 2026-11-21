package com.jubersaleem.mobilegamingstreamer.scene

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * J3 pure animation core: preset model, time-driven clock, easing,
 * and pixel-space geometry. Pure Kotlin: no Android, threads, or
 * I/O. The GL renderer and the editor preview both evaluate through
 * here, so live motion and preview motion share one tested math.
 *
 * All presets are TIMED transforms (phase 0→1 over delay+duration),
 * driven by elapsed milliseconds — never frame counts — so motion
 * is identical at any device frame rate. There are no
 * removal-triggered exits: OUT presets start placed and leave on
 * their own clock.
 */

/** Duration bounds: 0.1 s (perceptible) to 10 s (bounded tick span). */
internal const val MIN_ANIM_DURATION_MS = 100
internal const val MAX_ANIM_DURATION_MS = 10_000

/** Delay bound: 0 to 10 s. */
internal const val MAX_ANIM_DELAY_MS = 10_000

/** J3 animation presets. NONE means "no animation" (stored as null). */
enum class SceneAnimPreset {
    NONE,
    FADE_IN,
    FADE_OUT,
    SLIDE_IN_LEFT,
    SLIDE_IN_RIGHT,
    SLIDE_IN_TOP,
    SLIDE_IN_BOTTOM,
    SLIDE_OUT_LEFT,
    SLIDE_OUT_RIGHT,
    SLIDE_OUT_TOP,
    SLIDE_OUT_BOTTOM,
    SCALE_IN,
    SCALE_OUT,
    PULSE,
    ZOOM,
    DRIFT_LEFT,
    DRIFT_RIGHT,
    DRIFT_UP,
    DRIFT_DOWN,
    SPIN
}

/** ONCE rests at the end state; LOOP restarts the phase (sawtooth). */
enum class SceneAnimRepeat {
    ONCE,
    LOOP
}

/** Quadratic easings ( boundaries + midpoint pinned by tests). */
enum class SceneAnimEasing {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT
}

/**
 * One layer's animation configuration. Public: crosses editor
 * composable boundaries like the rest of the layer model. [of]
 * validates (null on any garbage); NONE is not storable (use null).
 */
data class SceneLayerAnimation(
    val preset: SceneAnimPreset,
    val durationMs: Int,
    val delayMs: Int,
    val repeat: SceneAnimRepeat,
    val easing: SceneAnimEasing
) {
    companion object {
        fun of(
            preset: SceneAnimPreset,
            durationMs: Int,
            delayMs: Int,
            repeat: SceneAnimRepeat,
            easing: SceneAnimEasing
        ): SceneLayerAnimation? {
            if (preset == SceneAnimPreset.NONE) return null
            if (durationMs < MIN_ANIM_DURATION_MS || durationMs > MAX_ANIM_DURATION_MS) return null
            if (delayMs < 0 || delayMs > MAX_ANIM_DELAY_MS) return null
            return SceneLayerAnimation(preset, durationMs, delayMs, repeat, easing)
        }

        /**
         * Strict codec parse of [encode]'s
         * `"PRESET,duration,delay,REPEAT,EASING"` shape. Null on any
         * garbage (the codec drops the layer, like kind/scale).
         */
        fun parse(token: String): SceneLayerAnimation? {
            val parts = token.split(",")
            if (parts.size != 5) return null
            val preset = try {
                SceneAnimPreset.valueOf(parts[0])
            } catch (_: IllegalArgumentException) {
                return null
            }
            val durationMs = parts[1].toIntOrNull() ?: return null
            val delayMs = parts[2].toIntOrNull() ?: return null
            val repeat = try {
                SceneAnimRepeat.valueOf(parts[3])
            } catch (_: IllegalArgumentException) {
                return null
            }
            val easing = try {
                SceneAnimEasing.valueOf(parts[4])
            } catch (_: IllegalArgumentException) {
                return null
            }
            return of(preset, durationMs, delayMs, repeat, easing)
        }
    }

    fun encode(): String =
        "${preset.name},$durationMs,$delayMs,${repeat.name},${easing.name}"
}

/** Clock sample: normalized phase + whether time still runs. */
internal data class SceneAnimPhase(val phase01: Float, val active: Boolean)

/**
 * Evaluated transform in canvas pixels / unitless multipliers. Scale
 * and rotation apply about the rect center; translation after.
 */
internal data class SceneAnimTransform(
    val dxPx: Float,
    val dyPx: Float,
    val scale: Float,
    val alphaMul: Float,
    val rotationDeg: Float
) {
    fun isIdentity(): Boolean =
        dxPx == 0f && dyPx == 0f && scale == 1f && alphaMul == 1f && rotationDeg == 0f

    companion object {
        val IDENTITY = SceneAnimTransform(0f, 0f, 1f, 1f, 0f)
    }
}

internal object SceneAnimation {
    /**
     * Clock sample for [anim] at [elapsedMs] since the slot start.
     * Null only for NONE. During the delay the start state shows and
     * time still runs (active). ONCE deactivates past the end (the
     * end state rests); LOOP stays active forever. Defensive on
     * garbage time (negative → start; huge → end).
     */
    fun phaseAt(anim: SceneLayerAnimation, elapsedMs: Long): SceneAnimPhase? {
        if (anim.preset == SceneAnimPreset.NONE) return null
        val duration = anim.durationMs.toLong().coerceAtLeast(1L)
        val t = elapsedMs - anim.delayMs
        if (t < 0L) return SceneAnimPhase(0f, true)
        if (anim.repeat == SceneAnimRepeat.LOOP) {
            return SceneAnimPhase((t % duration).toFloat() / duration, true)
        }
        if (t >= duration) return SceneAnimPhase(1f, false)
        return SceneAnimPhase(t.toFloat() / duration, true)
    }

    /** Easing curve on a 0..1 phase (input clamped). Pure. */
    fun ease(easing: SceneAnimEasing, phase01: Float): Float {
        val p = phase01.coerceIn(0f, 1f)
        return when (easing) {
            SceneAnimEasing.LINEAR -> p
            SceneAnimEasing.EASE_IN -> p * p
            SceneAnimEasing.EASE_OUT -> 1f - (1f - p) * (1f - p)
            SceneAnimEasing.EASE_IN_OUT ->
                if (p < 0.5f) 2f * p * p else 1f - 2f * (1f - p) * (1f - p)
        }
    }

    /**
     * Transform for [preset] at eased phase [p01] over [rectPx] on a
     * [canvasW]x[canvasH] canvas. Slide presets travel fully
     * off-canvas (from rect+canvas edges); drift travels
     * min(canvas)/12. Pure; finite output for any finite input.
     */
    fun transformFor(
        preset: SceneAnimPreset,
        p01: Float,
        rectPx: SceneRectPx,
        canvasW: Int,
        canvasH: Int
    ): SceneAnimTransform {
        val p = p01.coerceIn(0f, 1f)
        val q = 1f - p
        return when (preset) {
            SceneAnimPreset.NONE -> SceneAnimTransform.IDENTITY
            SceneAnimPreset.FADE_IN -> SceneAnimTransform(0f, 0f, 1f, p, 0f)
            SceneAnimPreset.FADE_OUT -> SceneAnimTransform(0f, 0f, 1f, q, 0f)
            SceneAnimPreset.SLIDE_IN_LEFT ->
                SceneAnimTransform(-rectPx.right.toFloat() * q, 0f, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_IN_RIGHT ->
                SceneAnimTransform((canvasW - rectPx.left).toFloat() * q, 0f, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_IN_TOP ->
                SceneAnimTransform(0f, -rectPx.bottom.toFloat() * q, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_IN_BOTTOM ->
                SceneAnimTransform(0f, (canvasH - rectPx.top).toFloat() * q, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_OUT_LEFT ->
                SceneAnimTransform(-rectPx.right.toFloat() * p, 0f, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_OUT_RIGHT ->
                SceneAnimTransform((canvasW - rectPx.left).toFloat() * p, 0f, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_OUT_TOP ->
                SceneAnimTransform(0f, -rectPx.bottom.toFloat() * p, 1f, 1f, 0f)
            SceneAnimPreset.SLIDE_OUT_BOTTOM ->
                SceneAnimTransform(0f, (canvasH - rectPx.top).toFloat() * p, 1f, 1f, 0f)
            SceneAnimPreset.SCALE_IN -> SceneAnimTransform(0f, 0f, p, p, 0f)
            SceneAnimPreset.SCALE_OUT -> SceneAnimTransform(0f, 0f, q, q, 0f)
            SceneAnimPreset.PULSE ->
                SceneAnimTransform(0f, 0f, 1f + 0.08f * sin(p * 2f * PI).toFloat(), 1f, 0f)
            SceneAnimPreset.ZOOM -> SceneAnimTransform(0f, 0f, 1f + 0.15f * p, 1f, 0f)
            SceneAnimPreset.DRIFT_LEFT ->
                SceneAnimTransform(-driftPx(canvasW, canvasH) * p, 0f, 1f, 1f, 0f)
            SceneAnimPreset.DRIFT_RIGHT ->
                SceneAnimTransform(driftPx(canvasW, canvasH) * p, 0f, 1f, 1f, 0f)
            SceneAnimPreset.DRIFT_UP ->
                SceneAnimTransform(0f, -driftPx(canvasW, canvasH) * p, 1f, 1f, 0f)
            SceneAnimPreset.DRIFT_DOWN ->
                SceneAnimTransform(0f, driftPx(canvasW, canvasH) * p, 1f, 1f, 0f)
            SceneAnimPreset.SPIN -> SceneAnimTransform(0f, 0f, 1f, 1f, 360f * p)
        }
    }

    private fun driftPx(canvasW: Int, canvasH: Int): Float =
        minOf(canvasW, canvasH).coerceAtLeast(0).toFloat() / 12f

    /**
     * Writes the triangle-strip NDC quad (8 floats, same vertex order
     * as [SceneTextLayerLayout.ndcQuad]) for [rectPx] under [t] into
     * [out]. Transform runs in pixel space (translate after
     * scale/rotate about the rect center), then the exact `ndcQuad`
     * mapping — so an identity transform reproduces `ndcQuad`
     * bit-for-bit (fast path below, pinned by test). Invalid canvas
     * or a short [out] writes zeros (degenerate, safely invisible).
     * No allocation; NaN/infinite inputs collapse to zeros.
     */
    fun writeTransformedNdc(
        rectPx: SceneRectPx,
        t: SceneAnimTransform,
        canvasW: Int,
        canvasH: Int,
        out: FloatArray
    ) {
        if (out.size < 8 || canvasW <= 0 || canvasH <= 0) {
            for (i in out.indices) out[i] = 0f
            return
        }
        fun nx(px: Float): Float = (px / canvasW) * 2f - 1f
        fun ny(py: Float): Float = 1f - (py / canvasH) * 2f
        if (t.isIdentity()) {
            // Fast path: exact ndcQuad reproduction, no float drift.
            out[0] = nx(rectPx.left.toFloat()); out[1] = ny(rectPx.bottom.toFloat())
            out[2] = nx(rectPx.right.toFloat()); out[3] = ny(rectPx.bottom.toFloat())
            out[4] = nx(rectPx.left.toFloat()); out[5] = ny(rectPx.top.toFloat())
            out[6] = nx(rectPx.right.toFloat()); out[7] = ny(rectPx.top.toFloat())
            return
        }
        val cx = (rectPx.left + rectPx.right).toFloat() / 2f
        val cy = (rectPx.top + rectPx.bottom).toFloat() / 2f
        val rad = (t.rotationDeg.toDouble() / 180.0 * PI).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        // Strip order: BL, BR, TL, TR (pixel space, Y down). Unrolled:
        // no allocation on the frame path.
        putCorner(rectPx.left, rectPx.bottom, cx, cy, c, s, t, canvasW, canvasH, out, 0)
        putCorner(rectPx.right, rectPx.bottom, cx, cy, c, s, t, canvasW, canvasH, out, 2)
        putCorner(rectPx.left, rectPx.top, cx, cy, c, s, t, canvasW, canvasH, out, 4)
        putCorner(rectPx.right, rectPx.top, cx, cy, c, s, t, canvasW, canvasH, out, 6)
        for (i in 0..7) {
            if (!out[i].isFinite()) out[i] = 0f
        }
    }

    private fun putCorner(
        px: Int,
        py: Int,
        cx: Float,
        cy: Float,
        c: Float,
        s: Float,
        t: SceneAnimTransform,
        canvasW: Int,
        canvasH: Int,
        out: FloatArray,
        at: Int
    ) {
        val lx = (px.toFloat() - cx) * t.scale
        val ly = (py.toFloat() - cy) * t.scale
        val rx = lx * c - ly * s + cx + t.dxPx
        val ry = lx * s + ly * c + cy + t.dyPx
        out[at] = (rx / canvasW) * 2f - 1f
        out[at + 1] = 1f - (ry / canvasH) * 2f
    }
}
