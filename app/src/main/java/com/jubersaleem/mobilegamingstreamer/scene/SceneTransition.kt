package com.jubersaleem.mobilegamingstreamer.scene

import kotlin.math.roundToInt

/**
 * J4 pure transition core: switch-effect model, elapsed-time clock,
 * and post-composite geometry. Pure Kotlin: no Android, threads, or
 * I/O. The GL renderer and the editor demo both evaluate through
 * here, so live blends and demo motion share one tested math.
 *
 * Transitions are TIMED blends (progress 0→1 over duration), driven
 * by elapsed milliseconds — never frame counts — so a 500 ms
 * crossfade lasts 500 ms at any device frame rate. Slide/wipe
 * direction = direction of on-screen motion. Retaining the outgoing
 * scene costs exactly one RGBA snapshot texture (see
 * [SceneTransitions.allowsSnapshot]); at most one scene's slots ever
 * exist, so the J2 decoder budget proof is unchanged.
 */

/** Duration bounds: 0.2 s (perceptible) to 2 s (bounded blend). */
internal const val MIN_TRANSITION_DURATION_MS = 200
internal const val MAX_TRANSITION_DURATION_MS = 2000

/**
 * Snapshot budget: 16 MiB RGBA. Covers 1440p output (14.7 MiB);
 * beyond this a switch degrades to CUT (never a broken blend).
 */
internal const val MAX_TRANSITION_SNAPSHOT_BYTES = 16_777_216L

/** J4 switch effects. CUT means "instant" (stored, rendered as-is). */
enum class SceneTransitionType {
    CUT,
    CROSSFADE,
    FADE_THROUGH_BLACK,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    SLIDE_UP,
    SLIDE_DOWN,
    WIPE_LEFT,
    WIPE_RIGHT,
    WIPE_UP,
    WIPE_DOWN,
    ZOOM_IN,
    ZOOM_OUT
}

/**
 * One scene-switch effect configuration. Public: crosses editor
 * composable + activity boundaries like the layer model. [of]
 * validates (null on any garbage); CUT ignores duration/easing at
 * render time but still stores a valid triple.
 */
data class SceneTransition(
    val type: SceneTransitionType,
    val durationMs: Int,
    val easing: SceneAnimEasing
) {
    companion object {
        /** Prefs/intent fallback: today's behavior (instant switch). */
        val DEFAULT = SceneTransition(
            SceneTransitionType.CUT, 500, SceneAnimEasing.EASE_IN_OUT
        )

        fun of(
            type: SceneTransitionType,
            durationMs: Int,
            easing: SceneAnimEasing
        ): SceneTransition? {
            if (durationMs < MIN_TRANSITION_DURATION_MS ||
                durationMs > MAX_TRANSITION_DURATION_MS
            ) return null
            return SceneTransition(type, durationMs, easing)
        }

        /**
         * Strict codec parse of [encode]'s
         * `"TYPE,duration,EASING"` shape. Null on any garbage (the
         * caller falls back to [DEFAULT], never a half-config).
         */
        fun parse(token: String): SceneTransition? {
            val parts = token.split(",")
            if (parts.size != 3) return null
            val type = try {
                SceneTransitionType.valueOf(parts[0])
            } catch (_: IllegalArgumentException) {
                return null
            }
            val durationMs = parts[1].toIntOrNull() ?: return null
            val easing = try {
                SceneAnimEasing.valueOf(parts[2])
            } catch (_: IllegalArgumentException) {
                return null
            }
            return of(type, durationMs, easing)
        }
    }

    fun encode(): String = "${type.name},$durationMs,${easing.name}"

    /** True for CUT: renders instantly, never samples the clock. */
    fun isInstant(): Boolean = type == SceneTransitionType.CUT
}

/**
 * Fade-through-black sample: [outgoingPhase] draws black + snapshot
 * at [topAlpha]; the incoming phase draws incoming + black overlay
 * at [topAlpha]. Continuous at the midpoint (black both sides).
 */
internal data class FadeThroughBlack(val outgoingPhase: Boolean, val topAlpha: Float)

/**
 * Wipe reveal rect in GL Y-up framebuffer pixels (row 0 = bottom):
 * the incoming scene draws inside, the snapshot shows outside.
 */
internal data class WipeScissor(val x: Int, val y: Int, val w: Int, val h: Int)

/** Zoom sample: uniform snapshot scale about center + fade. */
internal data class ZoomSample(val scale: Float, val alpha: Float)

internal object SceneTransitions {
    /**
     * Eased-progress sanitizer: finite values clamp to 0..1, NaN and
     * infinities collapse to the start state (safely invisible /
     * un-revealed — never a NaN quad or scissor).
     */
    private fun saneEased(eased01: Float): Float =
        if (eased01.isFinite()) eased01.coerceIn(0f, 1f) else 0f

    /**
     * Raw progress 0..1 at [elapsedMs] since the switch. CUT is
     * instantly done (GL never samples it — defensive only).
     * Defensive on garbage time (negative → 0; huge → 1).
     */
    fun progressAt(config: SceneTransition, elapsedMs: Long): Float {
        if (config.isInstant()) return 1f
        val duration = config.durationMs.toLong().coerceAtLeast(1L)
        if (elapsedMs <= 0L) return 0f
        if (elapsedMs >= duration) return 1f
        return (elapsedMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    /**
     * Eased progress: the J3 easing curve applied to [progressAt]
     * (easing is reused, never duplicated — boundaries pinned via
     * these tests through the shared function).
     */
    fun easedProgress(config: SceneTransition, elapsedMs: Long): Float =
        SceneAnimation.ease(config.easing, progressAt(config, elapsedMs))

    /** True once the blend has run its full duration. */
    fun isDoneAt(config: SceneTransition, elapsedMs: Long): Boolean =
        progressAt(config, elapsedMs) >= 1f

    /** Crossfade snapshot alpha over live incoming (1→0). */
    fun crossfadeAlpha(eased01: Float): Float =
        1f - saneEased(eased01)

    /**
     * Fade-through-black split at the eased midpoint. Phase A shows
     * the frozen outgoing fading to black; phase B lifts black off
     * the live incoming. Single rebuild at switch time (uniform
     * lifecycle — no mid-transition rebuild).
     */
    fun fadeThroughBlack(eased01: Float): FadeThroughBlack {
        val e = saneEased(eased01)
        return if (e < 0.5f) {
            FadeThroughBlack(true, (1f - 2f * e).coerceIn(0f, 1f))
        } else {
            FadeThroughBlack(false, (2f - 2f * e).coerceIn(0f, 1f))
        }
    }

    /**
     * Wipe reveal rect for [type] at [eased01] on a [canvasW]×[canvasH]
     * output, in GL Y-up coords. The edge travels toward the named
     * direction; incoming shows where the edge has passed. Null for
     * non-wipe types. Invalid canvas → empty rect (safely invisible).
     */
    fun wipeScissor(
        type: SceneTransitionType,
        eased01: Float,
        canvasW: Int,
        canvasH: Int
    ): WipeScissor? {
        val e = saneEased(eased01)
        if (canvasW <= 0 || canvasH <= 0) return WipeScissor(0, 0, 0, 0)
        return when (type) {
            SceneTransitionType.WIPE_LEFT -> {
                val x = (canvasW * (1f - e)).roundToInt().coerceIn(0, canvasW)
                WipeScissor(x, 0, canvasW - x, canvasH)
            }
            SceneTransitionType.WIPE_RIGHT -> {
                val w = (canvasW * e).roundToInt().coerceIn(0, canvasW)
                WipeScissor(0, 0, w, canvasH)
            }
            SceneTransitionType.WIPE_UP -> {
                val h = (canvasH * e).roundToInt().coerceIn(0, canvasH)
                WipeScissor(0, 0, canvasW, h)
            }
            SceneTransitionType.WIPE_DOWN -> {
                val y = (canvasH * (1f - e)).roundToInt().coerceIn(0, canvasH)
                WipeScissor(0, y, canvasW, canvasH - y)
            }
            else -> null
        }
    }

    /**
     * Y-down (UI-canvas) mirror of [wipeScissor] for the editor demo:
     * same visual region in top-down pixels. Tested alongside.
     */
    fun wipeScissorTopDown(
        type: SceneTransitionType,
        eased01: Float,
        canvasW: Int,
        canvasH: Int
    ): WipeScissor? {
        val r = wipeScissor(type, eased01, canvasW, canvasH) ?: return null
        if (canvasH <= 0) return r
        return WipeScissor(r.x, canvasH - r.y - r.h, r.w, r.h)
    }

    /**
     * Zoom sample for [type] at [eased01]. ZOOM_IN dives through the
     * outgoing (1→1.2) into the incoming; ZOOM_OUT shrinks the
     * outgoing away (1→0.8). Both fade so completion never pops.
     * Null for non-zoom types. Scale is always positive.
     */
    fun zoomFor(type: SceneTransitionType, eased01: Float): ZoomSample? {
        val e = saneEased(eased01)
        return when (type) {
            SceneTransitionType.ZOOM_IN -> ZoomSample(1f + 0.2f * e, 1f - e)
            SceneTransitionType.ZOOM_OUT -> ZoomSample(1f - 0.2f * e, 1f - e)
            else -> null
        }
    }

    /**
     * Slide offset for [type] at [eased01], reusing the J3 timed
     * SLIDE_OUT preset on the fullscreen rect (the outgoing exits
     * toward the named direction; fully off-canvas at e=1, both
     * orientations — pinned through the shared function). Null for
     * non-slide types or an invalid canvas.
     */
    fun slideTransform(
        type: SceneTransitionType,
        eased01: Float,
        canvasW: Int,
        canvasH: Int
    ): SceneAnimTransform? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val preset = when (type) {
            SceneTransitionType.SLIDE_LEFT -> SceneAnimPreset.SLIDE_OUT_LEFT
            SceneTransitionType.SLIDE_RIGHT -> SceneAnimPreset.SLIDE_OUT_RIGHT
            SceneTransitionType.SLIDE_UP -> SceneAnimPreset.SLIDE_OUT_TOP
            SceneTransitionType.SLIDE_DOWN -> SceneAnimPreset.SLIDE_OUT_BOTTOM
            else -> return null
        }
        return SceneAnimation.transformFor(
            preset, saneEased(eased01),
            SceneRectPx(0, 0, canvasW, canvasH), canvasW, canvasH
        )
    }

    /**
     * Snapshot footprint in bytes (RGBA8888). Long math: hostile dims
     * cannot overflow into a small allow.
     */
    fun snapshotBytesFor(canvasW: Int, canvasH: Int): Long {
        if (canvasW <= 0 || canvasH <= 0) return 0L
        return canvasW.toLong() * canvasH.toLong() * 4L
    }

    /**
     * Budget gate: the switch may snapshot only within
     * [MAX_TRANSITION_SNAPSHOT_BYTES]. Beyond → CUT fallback.
     */
    fun allowsSnapshot(canvasW: Int, canvasH: Int): Boolean =
        snapshotBytesFor(canvasW, canvasH) in 1L..MAX_TRANSITION_SNAPSHOT_BYTES

    /**
     * Service/intent parse: null or garbage → [SceneTransition.DEFAULT]
     * (CUT — today's behavior). Never throws, never half-config.
     */
    fun parseExtra(extra: String?): SceneTransition {
        if (extra == null) return SceneTransition.DEFAULT
        return try {
            SceneTransition.parse(extra) ?: SceneTransition.DEFAULT
        } catch (_: Exception) {
            SceneTransition.DEFAULT
        }
    }
}

/**
 * One switch's clock: [config] + the GL-side start instant. GL and
 * the editor demo both sample through here — same elapsed ⇒ same
 * progress by construction. A rapid re-switch is a new instance
 * (progress restarts at 0, deterministically).
 */
internal data class TransitionProgress(
    val config: SceneTransition,
    val startElapsedMs: Long
) {
    /** Eased progress + completion at the sample instant. */
    data class Sample(val eased01: Float, val done: Boolean)

    fun sampleAt(nowElapsedMs: Long): TransitionProgress.Sample {
        val elapsed = nowElapsedMs - startElapsedMs
        return TransitionProgress.Sample(
            SceneTransitions.easedProgress(config, elapsed),
            SceneTransitions.isDoneAt(config, elapsed)
        )
    }
}
