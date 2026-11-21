package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H2 scene-compositor decision core: pure Kotlin orchestration of lifecycle,
 * frame handoff, and teardown behind the Android `SceneSource` shell.
 *
 * Why this exists (not ceremony): every integration decision — may we start?
 * may this frame draw? in what order do resources die? was that release
 * already done? — lives here, unit-tested, instead of hiding untestably in
 * Android/GL code. The shell executes platform calls only; it never decides.
 * (Same precedent as `StreamCallbackRouter`/`StreamSessionGate`: pure
 * decision cores behind Android shells.)
 *
 * Responsibilities, each pinned by tests:
 * - Lifecycle gating via [SceneSourceLifecycle]: start/stop/release/failure/
 *   revocation intents; frames accepted only in STARTED.
 * - Frame accounting via [SceneFrameWindow] (capacity
 *   [SCENE_COMPOSITOR_WINDOW_CAPACITY]): accepted/dropped counts; lifecycle
 *   rejections counted separately as [rejectedFrames] so backpressure drops
 *   and post-stop rejects are never conflated.
 * - Teardown ordering via [SceneTeardownLedger]: fixed order
 *   (VD → output → input → EGL → callback → GL thread), each step
 *   exactly-once; the ledger refreshes on every successful start so
 *   stop→start→stop releases the new resources instead of no-op'ing on
 *   spent guards.
 *
 * This controller is NOT recovery: on failure it records FAILED and stops
 * accepting frames; teardown and stream fate stay with the existing
 * service/library authority (a frozen source surfaces as a video stall).
 * Threading: plain fields, caller-confined. The GL thread reads
 * [isAcceptingFrames] across threads; the race analysis in
 * docs/H2_SCENE_ARCHITECTURE.md shows worst case is one wasted frame
 * attempt, caught and correctly classified — never corruption.
 */

/** In-flight headroom for the synchronous offer→draw→complete frame path. */
internal const val SCENE_COMPOSITOR_WINDOW_CAPACITY = 2

/**
 * Six teardown actions supplied by the Android shell. Each must be null-safe
 * and exception-isolated (service-teardown style): the controller guarantees
 * order and exactly-once, the shell guarantees no-throw.
 */
internal class SceneTeardownSteps(
    val releaseVirtualDisplay: () -> Unit,
    val releaseOutputSurface: () -> Unit,
    val releaseInputSurface: () -> Unit,
    val releaseEgl: () -> Unit,
    val releaseProjectionCallback: () -> Unit,
    val quitGlThread: () -> Unit
)

/**
 * Exactly-once ledger for one start attempt's resources. Runs the six steps
 * in the fixed teardown order; a second run is a complete no-op.
 */
internal class SceneTeardownLedger {
    private val virtualDisplay = SceneReleaseGuard()
    private val outputSurface = SceneReleaseGuard()
    private val inputSurface = SceneReleaseGuard()
    private val egl = SceneReleaseGuard()
    private val projectionCallback = SceneReleaseGuard()
    private val glThread = SceneReleaseGuard()

    fun runAll(steps: SceneTeardownSteps) {
        // Fixed order: stop the producer first; input (holds compositor-GL
        // objects) dies before context destruction; the GL thread quits last
        // because the EGL step runs on it.
        virtualDisplay.release(steps.releaseVirtualDisplay)
        outputSurface.release(steps.releaseOutputSurface)
        inputSurface.release(steps.releaseInputSurface)
        egl.release(steps.releaseEgl)
        projectionCallback.release(steps.releaseProjectionCallback)
        glThread.release(steps.quitGlThread)
    }

    val isComplete: Boolean
        get() = virtualDisplay.isReleased && outputSurface.isReleased &&
            inputSurface.isReleased && egl.isReleased &&
            projectionCallback.isReleased && glThread.isReleased
}

internal class SceneCompositorController {
    private val lifecycle = SceneSourceLifecycle()
    private val window = SceneFrameWindow(SCENE_COMPOSITOR_WINDOW_CAPACITY)
    private var teardown = SceneTeardownLedger()

    val state: SceneSourceState get() = lifecycle.state

    /** Frames flow only in STARTED; every other state rejects. */
    val isAcceptingFrames: Boolean get() = state == SceneSourceState.STARTED

    val inFlightFrames: Int get() = window.inFlight
    val acceptedFrames: Long get() = window.acceptedCount
    val droppedFrames: Long get() = window.droppedCount

    /**
     * Frames rejected by lifecycle state (never started / stopped / failed /
     * revoked / released), counted separately from window drops so GL
     * saturation and lifecycle rejects are distinguishable in diagnostics.
     */
    var rejectedFrames: Long = 0L
        private set

    val isFullyReleased: Boolean get() = teardown.isComplete

    /**
     * Start intent. True moves CREATED/STOPPED → STARTED and refreshes the
     * teardown ledger for the new attempt; false (already started, failed,
     * revoked, or released) changes nothing.
     */
    fun requestStart(): Boolean {
        if (!lifecycle.start()) return false
        teardown = SceneTeardownLedger()
        return true
    }

    /** Stop intent: STARTED → STOPPED, idempotent on STOPPED. */
    fun requestStop(): Boolean = lifecycle.stop()

    /** Release intent: total from every state, idempotent. */
    fun requestRelease(): Boolean = lifecycle.release()

    /** Startup-failure report: STARTED → FAILED. */
    fun onStartFailure(): Boolean = lifecycle.onStartFailure()

    /**
     * Mid-stream renderer-failure report. Aliases the machine's failure
     * transition (STARTED → FAILED): the shell calls this (not
     * [onStartFailure]) when GL work fails after a healthy start, so call
     * sites read correctly. Recording FAILED is all it does — recovery
     * authority stays with the existing watchdog/stall path.
     */
    fun onRenderFailure(): Boolean = lifecycle.onStartFailure()

    /** Projection-revocation event: CREATED/STARTED/STOPPED → REVOKED. */
    fun onProjectionRevoked(): Boolean = lifecycle.onProjectionRevoked()

    /**
     * Frame arrival. Returns a completion handle when the frame is accepted,
     * null when lifecycle-rejected (counted in [rejectedFrames], window
     * untouched) or window-dropped (invalid metadata or full window, counted
     * in [droppedFrames]).
     */
    fun onFrameArrival(metadata: SceneFrameMetadata): SceneFrameWindow.Completion? {
        if (!isAcceptingFrames) {
            rejectedFrames += 1
            return null
        }
        return window.offer(metadata)
    }

    /**
     * Releases one frame slot. True only for a live handle minted by this
     * controller's window; false (no change) otherwise. Every accepted
     * handle must complete exactly once, including on draw failure.
     */
    fun onFrameComplete(handle: SceneFrameWindow.Completion?): Boolean =
        window.complete(handle)

    /**
     * Runs teardown in the fixed order, each step exactly-once for the
     * current start attempt. Safe from any state, including before start
     * (shell actions must be null-safe) and after release (no-op).
     */
    fun performTeardown(steps: SceneTeardownSteps) {
        teardown.runAll(steps)
    }
}
