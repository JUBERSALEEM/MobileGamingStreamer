package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic integration tests for [SceneCompositorController]: lifecycle
 * gating, frame accept/reject accounting, and teardown order/exactly-once.
 * Drives the real controller + lifecycle + window — no Android, no threads.
 */
class SceneCompositorControllerTest {
    private fun recordingSteps(log: MutableList<String>): SceneTeardownSteps =
        SceneTeardownSteps(
            releaseVirtualDisplay = { log.add("vd") },
            releaseOutputSurface = { log.add("output") },
            releaseInputSurface = { log.add("input") },
            releaseEgl = { log.add("egl") },
            releaseProjectionCallback = { log.add("callback") },
            quitGlThread = { log.add("thread") }
        )

    private fun frame(seq: Long): SceneFrameMetadata = SceneFrameMetadata(seq, seq * 1000L)

    // ---- Lifecycle gating ----

    @Test
    fun startsCreatedNotAccepting() {
        val controller = SceneCompositorController()
        assertEquals(SceneSourceState.CREATED, controller.state)
        assertFalse(controller.isAcceptingFrames)
        assertFalse(controller.isFullyReleased)
    }

    @Test
    fun requestStartFromCreated() {
        val controller = SceneCompositorController()
        assertTrue(controller.requestStart())
        assertEquals(SceneSourceState.STARTED, controller.state)
        assertTrue(controller.isAcceptingFrames)
    }

    @Test
    fun requestStartWhenStartedRejected() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertFalse(controller.requestStart())
        assertTrue(controller.isAcceptingFrames)
    }

    @Test
    fun requestStartAfterReleaseRejected() {
        val controller = SceneCompositorController()
        controller.requestRelease()
        assertFalse(controller.requestStart())
        assertFalse(controller.isAcceptingFrames)
    }

    @Test
    fun requestStopFromStarted() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertTrue(controller.requestStop())
        assertEquals(SceneSourceState.STOPPED, controller.state)
        assertFalse(controller.isAcceptingFrames)
    }

    @Test
    fun requestStopIdempotent() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertTrue(controller.requestStop())
        assertTrue(controller.requestStop())
        assertEquals(SceneSourceState.STOPPED, controller.state)
    }

    @Test
    fun restartStopStartAcceptsAgain() {
        val controller = SceneCompositorController()
        controller.requestStart()
        controller.requestStop()
        assertTrue(controller.requestStart())
        assertTrue(controller.isAcceptingFrames)
    }

    @Test
    fun requestReleaseTotalFromEveryState() {
        // CREATED.
        assertTrue(SceneCompositorController().requestRelease())
        // STARTED.
        val started = SceneCompositorController()
        started.requestStart()
        assertTrue(started.requestRelease())
        assertEquals(SceneSourceState.RELEASED, started.state)
        // STOPPED.
        val stopped = SceneCompositorController()
        stopped.requestStart()
        stopped.requestStop()
        assertTrue(stopped.requestRelease())
        // FAILED.
        val failed = SceneCompositorController()
        failed.requestStart()
        failed.onStartFailure()
        assertTrue(failed.requestRelease())
        // REVOKED via active session.
        val revokedActive = SceneCompositorController()
        revokedActive.requestStart()
        revokedActive.onProjectionRevoked()
        assertTrue(revokedActive.requestRelease())
        // REVOKED before start.
        val revokedCreated = SceneCompositorController()
        revokedCreated.onProjectionRevoked()
        assertTrue(revokedCreated.requestRelease())
        // RELEASED (idempotent).
        assertTrue(revokedCreated.requestRelease())
    }

    @Test
    fun onStartFailureFromStarted() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertTrue(controller.onStartFailure())
        assertEquals(SceneSourceState.FAILED, controller.state)
        assertFalse(controller.isAcceptingFrames)
    }

    @Test
    fun onRenderFailureAliasesFailure() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertTrue(controller.onRenderFailure())
        assertEquals(SceneSourceState.FAILED, controller.state)
        assertFalse(controller.isAcceptingFrames)
        // No-op off the STARTED path, like the machine transition.
        val stopped = SceneCompositorController()
        stopped.requestStart()
        stopped.requestStop()
        assertFalse(stopped.onRenderFailure())
    }

    @Test
    fun onProjectionRevokedBlocksStart() {
        val controller = SceneCompositorController()
        assertTrue(controller.onProjectionRevoked())
        assertEquals(SceneSourceState.REVOKED, controller.state)
        assertFalse(controller.requestStart())
        assertFalse(controller.isAcceptingFrames)
    }

    @Test
    fun revokedStopsAccepting() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertTrue(controller.onProjectionRevoked())
        assertFalse(controller.isAcceptingFrames)
        assertFalse(controller.requestStart())
    }

    // ---- Frames ----

    @Test
    fun windowCapacityIsTwo() {
        assertEquals(2, SCENE_COMPOSITOR_WINDOW_CAPACITY)
    }

    @Test
    fun arrivalWhenCreatedRejected() {
        val controller = SceneCompositorController()
        assertNull(controller.onFrameArrival(frame(1L)))
        assertEquals(1L, controller.rejectedFrames)
        assertEquals(0, controller.inFlightFrames)
        assertEquals(0L, controller.acceptedFrames)
        assertEquals(0L, controller.droppedFrames)
    }

    @Test
    fun arrivalWhenStartedAccepted() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertNotNull(controller.onFrameArrival(frame(1L)))
        assertEquals(1, controller.inFlightFrames)
        assertEquals(1L, controller.acceptedFrames)
        assertEquals(0L, controller.rejectedFrames)
    }

    @Test
    fun completeReleasesSlotOnce() {
        val controller = SceneCompositorController()
        controller.requestStart()
        val handle = controller.onFrameArrival(frame(1L))!!
        assertTrue(controller.onFrameComplete(handle))
        assertEquals(0, controller.inFlightFrames)
        assertFalse(controller.onFrameComplete(handle))
        assertEquals(0, controller.inFlightFrames)
        assertFalse(controller.onFrameComplete(null))
    }

    @Test
    fun arrivalAfterStopRejected() {
        val controller = SceneCompositorController()
        controller.requestStart()
        controller.requestStop()
        assertNull(controller.onFrameArrival(frame(1L)))
        assertEquals(1L, controller.rejectedFrames)
        assertEquals(0L, controller.droppedFrames)
    }

    @Test
    fun arrivalAfterRevokeRejected() {
        val controller = SceneCompositorController()
        controller.requestStart()
        controller.onProjectionRevoked()
        assertNull(controller.onFrameArrival(frame(9L)))
        assertEquals(1L, controller.rejectedFrames)
        assertEquals(0L, controller.droppedFrames)
    }

    @Test
    fun invalidMetadataDroppedNotRejected() {
        val controller = SceneCompositorController()
        controller.requestStart()
        assertNull(controller.onFrameArrival(SceneFrameMetadata(-1L, 10L)))
        assertEquals(0, controller.inFlightFrames)
        assertEquals(0L, controller.acceptedFrames)
        assertEquals(1L, controller.droppedFrames)
        assertEquals(0L, controller.rejectedFrames)
    }

    @Test
    fun windowFullDrops() {
        val controller = SceneCompositorController()
        controller.requestStart()
        val first = controller.onFrameArrival(frame(1L))!!
        val second = controller.onFrameArrival(frame(2L))!!
        assertNull(controller.onFrameArrival(frame(3L)))
        assertEquals(2, controller.inFlightFrames)
        assertEquals(2L, controller.acceptedFrames)
        assertEquals(1L, controller.droppedFrames)
        assertTrue(controller.onFrameComplete(first))
        assertTrue(controller.onFrameComplete(second))
        assertEquals(0, controller.inFlightFrames)
    }

    @Test
    fun foreignCompletionRejected() {
        val controller = SceneCompositorController()
        controller.requestStart()
        val other = SceneCompositorController()
        other.requestStart()
        val foreign = other.onFrameArrival(frame(1L))!!
        assertFalse(controller.onFrameComplete(foreign))
        assertEquals(0, controller.inFlightFrames)
        assertEquals(0L, controller.acceptedFrames)
        assertTrue(other.onFrameComplete(foreign))
    }

    // ---- Teardown ----

    @Test
    fun teardownOrderPinned() {
        val controller = SceneCompositorController()
        controller.requestStart()
        val log = mutableListOf<String>()
        controller.performTeardown(recordingSteps(log))
        assertEquals(listOf("vd", "output", "input", "egl", "callback", "thread"), log)
        assertTrue(controller.isFullyReleased)
    }

    @Test
    fun teardownExactlyOnce() {
        val controller = SceneCompositorController()
        controller.requestStart()
        val log = mutableListOf<String>()
        val steps = recordingSteps(log)
        controller.performTeardown(steps)
        controller.performTeardown(steps)
        controller.performTeardown(steps)
        assertEquals(6, log.size)
        assertTrue(controller.isFullyReleased)
    }

    @Test
    fun teardownBeforeStart() {
        // Null-safe shell actions run once even with nothing acquired.
        val controller = SceneCompositorController()
        val log = mutableListOf<String>()
        controller.performTeardown(recordingSteps(log))
        assertEquals(6, log.size)
        assertTrue(controller.isFullyReleased)
        controller.performTeardown(recordingSteps(log))
        assertEquals(6, log.size)
    }

    @Test
    fun ledgerRefreshesOnRestart() {
        val controller = SceneCompositorController()
        val log = mutableListOf<String>()
        val steps = recordingSteps(log)
        controller.requestStart()
        controller.performTeardown(steps)
        assertEquals(6, log.size)
        controller.requestStop()
        assertTrue(controller.requestStart())
        assertFalse(controller.isFullyReleased)
        controller.performTeardown(steps)
        assertEquals(12, log.size)
        assertTrue(controller.isFullyReleased)
    }

    @Test
    fun releaseBeforeStartThenStart() {
        val controller = SceneCompositorController()
        val log = mutableListOf<String>()
        val steps = recordingSteps(log)
        controller.performTeardown(steps)
        assertTrue(controller.requestStart())
        assertTrue(controller.isAcceptingFrames)
        assertNotNull(controller.onFrameArrival(frame(1L)))
        controller.performTeardown(steps)
        assertEquals(12, log.size)
    }

    @Test
    fun stopThenReleaseSingleTeardown() {
        // stop()'s teardown spends the ledger; release()'s is a no-op.
        val controller = SceneCompositorController()
        val log = mutableListOf<String>()
        val steps = recordingSteps(log)
        controller.requestStart()
        controller.requestStop()
        controller.performTeardown(steps)
        controller.requestRelease()
        controller.performTeardown(steps)
        assertEquals(6, log.size)
        assertTrue(controller.isFullyReleased)
    }
}
