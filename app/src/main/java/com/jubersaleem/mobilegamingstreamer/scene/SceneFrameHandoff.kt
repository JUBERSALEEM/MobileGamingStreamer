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
 * - [SceneFrameWindow]: bounded in-flight slots with drop-newest backpressure,
 *   exact accepted/dropped accounting, and single-use completion handles.
 * - [SceneFrameWindow.Completion]: sealed completion-handle type whose only
 *   implementation is private to the window — unforgeable by any caller.
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

/**
 * Bounded in-flight frame slots with deterministic drop-newest backpressure.
 *
 * Policy, pinned by tests:
 * - [offer] returns a [Completion] for an accepted frame, null for a dropped
 *   one. Dropped offers never receive handles.
 * - An invalid frame is dropped and counted without consuming a slot:
 *   corrupt or undated frames never occupy handoff capacity.
 * - A valid frame with a free slot is accepted (slot consumed, handle minted).
 * - A valid frame with no free slot is dropped and counted (drop-newest:
 *   the in-flight frames the consumer may be reading are never evicted).
 * - [complete] releases exactly one slot and returns true only for a live
 *   (unconsumed, owner-minted) handle. Null, foreign, forged, or
 *   already-consumed handles return false with no state change — duplicate,
 *   unknown, or invalid completions can never release another frame's slot,
 *   and rejections never pollute the accepted/dropped counts.
 *
 * Structural invariant: each live handle owns exactly one in-flight slot
 * (mint and slot-consumption are paired in [offer]; consumption and
 * slot-release are paired in [complete]), so the counter can neither exceed
 * capacity nor drop below zero through this API — no defensive guard needed.
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

    /**
     * Single-use completion handle for one accepted frame. Each accepted offer
     * mints exactly one handle; each handle releases its slot at most once.
     *
     * Compile-time mint boundary (no weaker claim is made): this sealed
     * interface has exactly one implementation, [CompletionImpl], which is
     * private to the window — so only [SceneFrameWindow.offer] can name the
     * type and call its constructor. The sealed rule permits inheritors in
     * the same package and module only, so no caller outside the main
     * module — not even same-package test code — can declare a [Completion]
     * subtype at all (attempting it fails compilation). Ordinary same-module
     * callers can hold and pass handles, but [complete] only accepts the
     * private implementation minted by the owning window with an unconsumed
     * flag — the foreign-handle rejection tests prove a non-owner handle is
     * inert. The boundary is further pinned by reflection tests (empty
     * interface surface, single private implementation, [offer] the sole
     * minter), so a future refactor that weakens it fails the suite instead
     * of silently opening it. Reflection-based attacks are out of scope, as
     * with all JVM encapsulation.
     *
     * Accidental reuse is prevented structurally, not by convention:
     * - Unforgeable: see the mint boundary above; dropped offers receive null.
     * - Owner-bound: the implementation is inner, so the outer reference IS
     *   the owner (referential check — a foreign handle is inert, and the
     *   binding cannot be wrong).
     * - Single-use: a consumed flag makes double completion return false
     *   without touching capacity, so completing one frame can never release
     *   another's.
     * - No integer ids: there is no id space to wrap, collide, or ABA-reuse,
     *   and no window-side per-frame registry — a handle's one Boolean lives
     *   exactly as long as the caller-held handle itself, so no state lingers
     *   after completion and nothing is retained per frame by the window.
     */
    sealed interface Completion

    /**
     * The sole [Completion] implementation. Private to the window: no other
     * caller can name this type, so the internal constructor and [tryConsume]
     * are callable from [SceneFrameWindow] only. (Kotlin private members of a
     * nested class are NOT visible to the outer class, so true `private`
     * here would lock the window out too — privacy of the TYPE is what seals
     * the boundary, and the reflection tests pin exactly that.)
     */
    private inner class CompletionImpl internal constructor() : Completion {
        private var consumed = false

        /**
         * Releases this handle's slot iff [window] is the minting owner and
         * this handle is unconsumed. Marks consumed on success. Never throws.
         */
        internal fun tryConsume(window: SceneFrameWindow): Boolean {
            if (window !== this@SceneFrameWindow || consumed) return false
            consumed = true
            return true
        }
    }

    var inFlight: Int = 0
        private set

    var acceptedCount: Long = 0L
        private set

    var droppedCount: Long = 0L
        private set

    /**
     * Offers one frame. Returns a completion handle when accepted, null when
     * dropped (invalid frame or window full); every drop is counted. This is
     * the sole minter of [Completion] (pinned by test).
     */
    fun offer(frame: SceneFrameMetadata): Completion? {
        if (!frame.isValid) {
            droppedCount += 1
            return null
        }
        if (inFlight >= capacity) {
            droppedCount += 1
            return null
        }
        inFlight += 1
        acceptedCount += 1
        return CompletionImpl()
    }

    /**
     * Releases the slot owned by [handle]. True on release; false (no state
     * change, counts untouched) for null, foreign, forged, or
     * already-consumed handles.
     */
    fun complete(handle: Completion?): Boolean {
        if (handle == null) return false
        val impl = handle as? CompletionImpl ?: return false
        if (!impl.tryConsume(this)) return false
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
