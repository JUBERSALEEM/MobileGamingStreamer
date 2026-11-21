package com.jubersaleem.mobilegamingstreamer.scene

/**
 * Lifecycle states for a future scene source (H1 custom VideoSource).
 * CREATED: constructed, never started. STARTED: producing (or attempting to
 * produce). STOPPED: cleanly stopped, restartable. FAILED: startup failed;
 * release and recreate — never restart. RELEASED: terminal; the source is gone.
 */
internal enum class SceneSourceState {
    CREATED,
    STARTED,
    STOPPED,
    FAILED,
    RELEASED
}

/**
 * Deterministic, thread-independent lifecycle state machine for testing the
 * future H1 scene source. It owns no Android resources and performs no I/O:
 * it only records which transitions are legal, so H1 tests can drive failure
 * and revocation paths without a device.
 *
 * Transition table — each cell is (applied?, end state):
 *
 * | event                | CREATED        | STARTED        | STOPPED        | FAILED         | RELEASED |
 * |----------------------|----------------|----------------|----------------|----------------|----------|
 * | start()              | true, STARTED  | false, STARTED | true, STARTED  | false, FAILED  | false    |
 * | stop()               | false, CREATED | true, STOPPED  | true, STOPPED  | false, FAILED  | false    |
 * | release()            | true, RELEASED | true, RELEASED | true, RELEASED | true, RELEASED | true (*) |
 * | onStartFailure()     | false, CREATED | true, FAILED   | false, STOPPED | false, FAILED  | false    |
 * | onProjectionRevoked()| false, CREATED | true, STOPPED  | true, STOPPED  | false, FAILED  | false    |
 *
 * (*) release() from RELEASED stays RELEASED and returns true (idempotent).
 *
 * Deliberate decisions, pinned by tests:
 * - Restart from STOPPED is allowed (clean stop leaves nothing poisoned),
 *   including after revocation: a fresh start() re-acquires.
 * - Restart from FAILED is rejected: a failed source may hold poisoned
 *   H1 resources (VirtualDisplay/Surface/EGL), so the only path is release
 *   and recreate.
 * - stop() from CREATED, FAILED or RELEASED is rejected: stopping something
 *   that never started, failed, or is gone is a caller bug. In particular,
 *   stop-after-release is invalid so H1 tests catch use-after-release.
 * - onStartFailure() is accepted only from STARTED: H0 models startup as the
 *   CREATED/STOPPED -> STARTED intent, and a later async failure report moves
 *   STARTED -> FAILED. A failure with no matching start is rejected.
 * - onProjectionRevoked() maps STARTED -> STOPPED (external stop; the source
 *   itself is intact) and is idempotent on STOPPED.
 *
 * This machine is NOT a second stream-recovery mechanism: it reports and
 * guards source state only. Retry, watchdog and transport authority stay with
 * the existing StreamRecoveryDriver / StreamConnectionPolicy / service code,
 * which H0 does not touch.
 *
 * Threading: plain field, confined to a single thread. H0 introduces no
 * synchronization because no H0 caller is concurrent; H1 must define the
 * owning thread (or add synchronization) when real callbacks arrive.
 *
 * Unresolved H1 mappings (documented, not solved here): which real cleanup
 * each transition triggers (VirtualDisplay/Surface/EGL teardown order),
 * which EGL/producer failures map to onStartFailure(), and how revocation
 * during an in-flight start frame is serialized. See the H0 final report.
 */
internal class SceneSourceLifecycle {
    var state: SceneSourceState = SceneSourceState.CREATED
        private set

    /** CREATED/STOPPED -> STARTED. False (no change) from any other state. */
    fun start(): Boolean {
        return when (state) {
            SceneSourceState.CREATED, SceneSourceState.STOPPED -> {
                state = SceneSourceState.STARTED
                true
            }
            SceneSourceState.STARTED,
            SceneSourceState.FAILED,
            SceneSourceState.RELEASED -> false
        }
    }

    /**
     * STARTED -> STOPPED; idempotent on STOPPED. False (no change) from
     * CREATED, FAILED or RELEASED.
     */
    fun stop(): Boolean {
        return when (state) {
            SceneSourceState.STARTED -> {
                state = SceneSourceState.STOPPED
                true
            }
            SceneSourceState.STOPPED -> true
            SceneSourceState.CREATED,
            SceneSourceState.FAILED,
            SceneSourceState.RELEASED -> false
        }
    }

    /**
     * Any non-released state -> RELEASED; idempotent on RELEASED.
     * Always returns true: release() is total.
     */
    fun release(): Boolean {
        state = SceneSourceState.RELEASED
        return true
    }

    /**
     * Async startup-failure report: STARTED -> FAILED. False (no change)
     * from any other state, including CREATED (no start to fail).
     */
    fun onStartFailure(): Boolean {
        return when (state) {
            SceneSourceState.STARTED -> {
                state = SceneSourceState.FAILED
                true
            }
            SceneSourceState.CREATED,
            SceneSourceState.STOPPED,
            SceneSourceState.FAILED,
            SceneSourceState.RELEASED -> false
        }
    }

    /**
     * External projection-revocation event: STARTED -> STOPPED (the source
     * is intact; a later start() re-acquires). Idempotent on STOPPED;
     * false (no change) from CREATED, FAILED or RELEASED.
     */
    fun onProjectionRevoked(): Boolean {
        return when (state) {
            SceneSourceState.STARTED -> {
                state = SceneSourceState.STOPPED
                true
            }
            SceneSourceState.STOPPED -> true
            SceneSourceState.CREATED,
            SceneSourceState.FAILED,
            SceneSourceState.RELEASED -> false
        }
    }
}
