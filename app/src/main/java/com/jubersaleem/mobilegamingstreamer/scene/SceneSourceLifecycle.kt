package com.jubersaleem.mobilegamingstreamer.scene

/**
 * Lifecycle states for a future scene source (H1 custom VideoSource).
 * CREATED: constructed with its projection session, never started. STARTED:
 * producing (or attempting to produce). STOPPED: cleanly stopped, restartable.
 * FAILED: startup failed; release and recreate — never restart. REVOKED: the
 * projection session died (revocation); the instance is bound to a dead token,
 * so it is terminal except for release — never restartable. RELEASED:
 * terminal; the source is gone.
 *
 * CREATED-is-bound is established by the inspected architecture, not assumed:
 * the VideoSource seam binds the MediaProjection at construction
 * (ScreenSource's constructor parameter, stored into the static holder in its
 * init block — exact RootEncoder 2.8.1 source), and the service builds the
 * projection before constructing the source. A revoked-but-never-started
 * instance therefore holds a dead token and must never start.
 */
internal enum class SceneSourceState {
    CREATED,
    STARTED,
    STOPPED,
    FAILED,
    REVOKED,
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
 * | event                | CREATED        | STARTED        | STOPPED        | FAILED         | REVOKED        | RELEASED |
 * |----------------------|----------------|----------------|----------------|----------------|----------------|----------|
 * | start()              | true, STARTED  | false, STARTED | true, STARTED  | false, FAILED  | false, REVOKED | false    |
 * | stop()               | false, CREATED | true, STOPPED  | true, STOPPED  | false, FAILED  | false, REVOKED | false    |
 * | release()            | true, RELEASED | true, RELEASED | true, RELEASED | true, RELEASED | true, RELEASED | true (*) |
 * | onStartFailure()     | false, CREATED | true, FAILED   | false, STOPPED | false, FAILED  | false, REVOKED | false    |
 * | onProjectionRevoked()| true, REVOKED  | true, REVOKED  | true, REVOKED  | false, FAILED  | true, REVOKED  | false    |
 *
 * (*) release() from RELEASED stays RELEASED and returns true (idempotent).
 *
 * Deliberate decisions, pinned by tests:
 * - Restart from STOPPED is allowed (clean stop leaves nothing poisoned).
 * - Revocation is NOT an ordinary stop: it moves CREATED, STARTED and STOPPED
 *   to the distinct REVOKED state. A revoked instance is bound to a dead
 *   projection token, so start() from REVOKED is rejected — recovery requires
 *   release and a fresh instance with a fresh projection (matching the
 *   service, which tears down and rebuilds the whole session on revocation,
 *   even if revocation arrives before anything started).
 * - A CREATED instance is revoked too, not left startable: construction binds
 *   the projection (see the enum KDoc), so a revoked CREATED holds a dead
 *   token and start() would acquire against it.
 * - A STOPPED instance is revoked too, not left STOPPED: leaving it STOPPED
 *   would allow start() to re-acquire with the dead token.
 * - Repeated revocation is idempotent (true, stays REVOKED): re-delivery of
 *   an external event is harmless, like stop on STOPPED.
 * - stop() from REVOKED is rejected (like FAILED): a revoked source is not
 *   started, so stopping is a caller bug and release is the only path.
 *   This keeps revocation crisply distinguishable from ordinary stop.
 * - Restart from FAILED is rejected: a failed source may hold poisoned
 *   H1 resources (VirtualDisplay/Surface/EGL), so the only path is release
 *   and recreate.
 * - stop() from CREATED, FAILED or RELEASED is rejected: stopping something
 *   that never started, failed, or is gone is a caller bug. In particular,
 *   stop-after-release is invalid so H1 tests catch use-after-release.
 * - onStartFailure() is accepted only from STARTED: H1 models startup as the
 *   CREATED/STOPPED -> STARTED intent, and a later async failure report moves
 *   STARTED -> FAILED. A failure with no matching start is rejected.
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
 * Unresolved H2 mappings (documented, not solved here): which real cleanup
 * each transition triggers (VirtualDisplay/Surface/EGL teardown order),
 * which EGL/producer failures map to onStartFailure(), and how revocation
 * during an in-flight start frame is serialized. See docs/H1_SCENE_OWNERSHIP.md.
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
            SceneSourceState.REVOKED,
            SceneSourceState.RELEASED -> false
        }
    }

    /**
     * STARTED -> STOPPED; idempotent on STOPPED. False (no change) from
     * CREATED, FAILED, REVOKED or RELEASED.
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
            SceneSourceState.REVOKED,
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
            SceneSourceState.REVOKED,
            SceneSourceState.RELEASED -> false
        }
    }

    /**
     * External projection-revocation event: CREATED/STARTED/STOPPED -> REVOKED.
     * The instance is bound to a dead projection token and can never start;
     * release and recreate with a fresh projection. Idempotent on REVOKED;
     * false (no change) from FAILED or RELEASED.
     */
    fun onProjectionRevoked(): Boolean {
        return when (state) {
            SceneSourceState.CREATED, SceneSourceState.STARTED, SceneSourceState.STOPPED -> {
                state = SceneSourceState.REVOKED
                true
            }
            SceneSourceState.REVOKED -> true
            SceneSourceState.FAILED,
            SceneSourceState.RELEASED -> false
        }
    }
}
