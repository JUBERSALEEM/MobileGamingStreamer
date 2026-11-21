package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H1 frame-handoff primitives: the verifiable core of the future scene-source
 * frame path, established without touching the live RootEncoder pipeline.
 *
 * The H1 investigation (see docs/H1_SCENE_OWNERSHIP.md) shows a future source
 * must produce frames into the library-owned SurfaceTexture on the library's
 * terms: bounded in-flight work, explicit drop accounting, and exactly-once
 * teardown. These primitives fix those semantics in unit-tested code now, so
 * the H2 Android/GL adapter implements policy instead of inventing it:
 * - [SceneFrameMetadata]: frame identity (sequence + capture timestamp).
 * - [SceneFrameWindow]: bounded in-flight slots with drop-newest backpressure
 *   and exact accepted/dropped accounting.
 * - [SceneReleaseGuard]: exactly-once teardown execution.
 *
 * Deliberately NOT here: frame payloads (pixels/textures/buffers need Android
 * or GL types), typed producer/consumer interfaces (still marker-only without
 * a payload type — deferred until the EGL handoff is device-validated), a
 * renderer, threads, or any wiring into GenericStream. The live pipeline
 * remains the RootEncoder ScreenSource path, byte-identical in behavior.
 */

/**
 * Future frame identity, metadata only. [sequence] is monotonically increasing
 * per source: a gap in accepted sequences is the H2 consumer's drop signal.
 * [timestampNs] is capture-clock nanoseconds; the exact clock (SurfaceTexture
 * timestamp vs elapsed-realtime) is chosen by the H2 adapter and documented
 * there — H1 only requires non-negativity. Both bounds are inclusive of zero.
 */
internal data class SceneFrameMetadata(val sequence: Long, val timestampNs: Long) {
    val isValid: Boolean get() = sequence >= 0L && timestampNs >= 0L
}

/** Outcome of offering one frame to a [SceneFrameWindow]. */
internal enum class SceneFrameOfferResult {
    ACCEPTED,
    DROPPED
}

/**
 * Bounded in-flight frame slots with deterministic drop-newest backpressure.
 *
 * Policy, pinned by tests:
 * - An invalid frame is DROPPED and counted without consuming a slot:
 *   corrupt or undated frames never occupy handoff capacity.
 * - A valid frame with a free slot is ACCEPTED (slot consumed).
 * - A valid frame with no free slot is DROPPED and counted (drop-newest:
 *   the in-flight frames the consumer may be reading are never evicted).
 * - [complete] releases one slot; completing with zero in-flight is a caller
 *   bug (returns false, no state change).
 *
 * Drop-oldest is deliberately NOT offered: evicting an in-flight frame needs
 * consumer cooperation (eviction signaling), which does not exist at H1. If
 * H2 ever needs it, the signaling contract comes first.
 *
 * Counters are Long and never reset; wrapping needs 2^63 frames and is
 * documented as unreachable rather than guarded with untestable code.
 * Threading: plain fields, confined to a single thread, like the H0 machine.
 */
internal class SceneFrameWindow(capacity: Int) {
    val capacity: Int

    init {
        require(capacity >= 1) { "capacity must hold at least one frame" }
        this.capacity = capacity
    }

    var inFlight: Int = 0
        private set

    var acceptedCount: Long = 0L
        private set

    var droppedCount: Long = 0L
        private set

    fun offer(frame: SceneFrameMetadata): SceneFrameOfferResult {
        if (!frame.isValid) {
            droppedCount += 1
            return SceneFrameOfferResult.DROPPED
        }
        if (inFlight >= capacity) {
            droppedCount += 1
            return SceneFrameOfferResult.DROPPED
        }
        inFlight += 1
        acceptedCount += 1
        return SceneFrameOfferResult.ACCEPTED
    }

    /**
     * Releases one in-flight slot. True on release; false (no state change)
     * when nothing is in flight.
     */
    fun complete(): Boolean {
        if (inFlight <= 0) return false
        inFlight -= 1
        return true
    }
}

/**
 * Exactly-once teardown execution for future H2 resource adapters
 * (VirtualDisplay/Surface/EGL must each be released exactly once).
 *
 * The first [release] call marks released, runs [action], and returns true;
 * later calls return false without running [action]. The mark happens BEFORE
 * [action] runs: a throwing teardown still counts as released, so a failing
 * release can never execute twice. Fatal errors are never caught.
 */
internal class SceneReleaseGuard {
    var isReleased: Boolean = false
        private set

    fun release(action: () -> Unit): Boolean {
        if (isReleased) return false
        isReleased = true
        action()
        return true
    }
}
