package com.jubersaleem.mobilegamingstreamer.scene

/**
 * J5 pure motion-scheduling policy: the tick-arm decisions behind the
 * GL animation ticker, extracted total and unit-tested. Pure Kotlin:
 * no Android, threads, or I/O. The GL shell
 * ([SceneCompositorGL]'s arm/tick sites) executes Handler posts only;
 * every scheduling DECISION lives here.
 *
 * Policy (identical to the J3/J4 inline logic this replaces):
 * - The ~60 Hz tick runs iff some animated slot still samples active
 *   OR an unfinished blend is live ([tickNeeded]).
 * - A static scene (no animations, no blend) never arms the tick:
 *   zero scheduling overhead by construction.
 * - Every predicate is TOTAL: garbage inputs (negative clocks, corrupt
 *   configs) collapse to the safe default (inactive/done) instead of
 *   throwing — a wedged clock reading can never wedge the ticker, and
 *   a wedged blend reading ends the blend instead of spinning forever.
 */
internal object SceneMotionPolicy {
    /**
     * True while [anim] (started at [startElapsedMs]) still samples
     * active at [nowElapsedMs]. False for NONE, rested ONCE, and any
     * garbage/exceptional input.
     */
    fun animActiveAt(
        anim: SceneLayerAnimation,
        startElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        return try {
            SceneAnimation.phaseAt(anim, nowElapsedMs - startElapsedMs)?.active == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True while [config] (started at [startElapsedMs]) is still
     * blending at [nowElapsedMs]. False when finished and on any
     * garbage/exceptional input (a wedged reading disarms the tick).
     */
    fun transActiveAt(
        config: SceneTransition,
        startElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        return try {
            !SceneTransitions.isDoneAt(config, nowElapsedMs - startElapsedMs)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True once [config] (started at [startElapsedMs]) has finished at
     * [nowElapsedMs]. TRUE on any garbage/exceptional input (a wedged
     * reading ends the blend instead of spinning it forever).
     */
    fun transDoneAt(
        config: SceneTransition,
        startElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        return try {
            SceneTransitions.isDoneAt(config, nowElapsedMs - startElapsedMs)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * The tick-arm truth table: motion needs the ticker while an
     * animation is active or a blend is unfinished — otherwise the
     * ticker stays off (static scenes: zero overhead).
     */
    fun tickNeeded(anyActiveAnim: Boolean, transActive: Boolean): Boolean =
        anyActiveAnim || transActive
}
