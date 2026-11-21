package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the H0 source lifecycle machine. Every test drives
 * the real [SceneSourceLifecycle] — no Android resources, no threads, no
 * sleeps. The [exhaustiveTransitionTable] sweep pins all 25 cells of the
 * state/event matrix documented on the machine.
 */
class SceneSourceLifecycleTest {
    @Test
    fun startsCreated() {
        assertEquals(SceneSourceState.CREATED, SceneSourceLifecycle().state)
    }

    @Test
    fun startFromCreated() {
        val lifecycle = SceneSourceLifecycle()
        assertTrue(lifecycle.start())
        assertEquals(SceneSourceState.STARTED, lifecycle.state)
    }

    @Test
    fun startWhenStartedRejected() {
        val lifecycle = SceneSourceLifecycle()
        assertTrue(lifecycle.start())
        assertFalse(lifecycle.start())
        assertEquals(SceneSourceState.STARTED, lifecycle.state)
    }

    @Test
    fun stopFromStarted() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.stop())
        assertEquals(SceneSourceState.STOPPED, lifecycle.state)
    }

    @Test
    fun repeatedStopIdempotent() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.stop())
        assertTrue(lifecycle.stop())
        assertTrue(lifecycle.stop())
        assertEquals(SceneSourceState.STOPPED, lifecycle.state)
    }

    @Test
    fun stopFromCreatedRejected() {
        val lifecycle = SceneSourceLifecycle()
        assertFalse(lifecycle.stop())
        assertEquals(SceneSourceState.CREATED, lifecycle.state)
    }

    @Test
    fun restartFromStoppedAcrossCycles() {
        val lifecycle = SceneSourceLifecycle()
        repeat(3) {
            assertTrue("cycle=$it", lifecycle.start())
            assertEquals(SceneSourceState.STARTED, lifecycle.state)
            assertTrue("cycle=$it", lifecycle.stop())
            assertEquals(SceneSourceState.STOPPED, lifecycle.state)
        }
    }

    @Test
    fun releaseFromCreated() {
        val lifecycle = SceneSourceLifecycle()
        assertTrue(lifecycle.release())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun releaseFromStarted() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.release())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun releaseFromStopped() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.stop()
        assertTrue(lifecycle.release())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun repeatedReleaseIdempotent() {
        val lifecycle = SceneSourceLifecycle()
        assertTrue(lifecycle.release())
        assertTrue(lifecycle.release())
        assertTrue(lifecycle.release())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun startAfterReleaseRejected() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.release()
        assertFalse(lifecycle.start())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun stopAfterReleaseRejected() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.release()
        assertFalse(lifecycle.stop())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun failureFromStarted() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.onStartFailure())
        assertEquals(SceneSourceState.FAILED, lifecycle.state)
    }

    @Test
    fun failureOnlyFromStarted() {
        // CREATED: no start to fail.
        assertFalse(SceneSourceLifecycle().onStartFailure())
        // STOPPED: cleanly stopped, nothing in flight.
        val stopped = SceneSourceLifecycle()
        stopped.start()
        stopped.stop()
        assertFalse(stopped.onStartFailure())
        assertEquals(SceneSourceState.STOPPED, stopped.state)
        // FAILED: already failed.
        val failed = SceneSourceLifecycle()
        failed.start()
        failed.onStartFailure()
        assertFalse(failed.onStartFailure())
        // RELEASED: gone.
        val released = SceneSourceLifecycle()
        released.release()
        assertFalse(released.onStartFailure())
    }

    @Test
    fun restartFromFailedRejected() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.onStartFailure()
        assertFalse(lifecycle.start())
        assertEquals(SceneSourceState.FAILED, lifecycle.state)
    }

    @Test
    fun stopFromFailedRejected() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.onStartFailure()
        assertFalse(lifecycle.stop())
        assertEquals(SceneSourceState.FAILED, lifecycle.state)
    }

    @Test
    fun releaseFromFailed() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.onStartFailure()
        assertTrue(lifecycle.release())
        assertEquals(SceneSourceState.RELEASED, lifecycle.state)
    }

    @Test
    fun revocationFromStartedStopsAndAllowsRestart() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        assertTrue(lifecycle.onProjectionRevoked())
        assertEquals(SceneSourceState.STOPPED, lifecycle.state)
        assertTrue(lifecycle.start())
        assertEquals(SceneSourceState.STARTED, lifecycle.state)
    }

    @Test
    fun revocationFromStoppedIdempotent() {
        val lifecycle = SceneSourceLifecycle()
        lifecycle.start()
        lifecycle.stop()
        assertTrue(lifecycle.onProjectionRevoked())
        assertTrue(lifecycle.onProjectionRevoked())
        assertEquals(SceneSourceState.STOPPED, lifecycle.state)
    }

    @Test
    fun revocationFromCreatedFailedReleasedRejected() {
        assertFalse(SceneSourceLifecycle().onProjectionRevoked())
        val failed = SceneSourceLifecycle()
        failed.start()
        failed.onStartFailure()
        assertFalse(failed.onProjectionRevoked())
        assertEquals(SceneSourceState.FAILED, failed.state)
        val released = SceneSourceLifecycle()
        released.release()
        assertFalse(released.onProjectionRevoked())
        assertEquals(SceneSourceState.RELEASED, released.state)
    }

    /**
     * Pins every cell of the documented transition table: for each state and
     * each event, the exact (applied, endState) pair.
     */
    @Test
    fun exhaustiveTransitionTable() {
        data class Cell(val applied: Boolean, val end: SceneSourceState)
        val table: Map<SceneSourceState, Map<String, Cell>> = mapOf(
            SceneSourceState.CREATED to mapOf(
                "start" to Cell(true, SceneSourceState.STARTED),
                "stop" to Cell(false, SceneSourceState.CREATED),
                "release" to Cell(true, SceneSourceState.RELEASED),
                "failure" to Cell(false, SceneSourceState.CREATED),
                "revoked" to Cell(false, SceneSourceState.CREATED)
            ),
            SceneSourceState.STARTED to mapOf(
                "start" to Cell(false, SceneSourceState.STARTED),
                "stop" to Cell(true, SceneSourceState.STOPPED),
                "release" to Cell(true, SceneSourceState.RELEASED),
                "failure" to Cell(true, SceneSourceState.FAILED),
                "revoked" to Cell(true, SceneSourceState.STOPPED)
            ),
            SceneSourceState.STOPPED to mapOf(
                "start" to Cell(true, SceneSourceState.STARTED),
                "stop" to Cell(true, SceneSourceState.STOPPED),
                "release" to Cell(true, SceneSourceState.RELEASED),
                "failure" to Cell(false, SceneSourceState.STOPPED),
                "revoked" to Cell(true, SceneSourceState.STOPPED)
            ),
            SceneSourceState.FAILED to mapOf(
                "start" to Cell(false, SceneSourceState.FAILED),
                "stop" to Cell(false, SceneSourceState.FAILED),
                "release" to Cell(true, SceneSourceState.RELEASED),
                "failure" to Cell(false, SceneSourceState.FAILED),
                "revoked" to Cell(false, SceneSourceState.FAILED)
            ),
            SceneSourceState.RELEASED to mapOf(
                "start" to Cell(false, SceneSourceState.RELEASED),
                "stop" to Cell(false, SceneSourceState.RELEASED),
                "release" to Cell(true, SceneSourceState.RELEASED),
                "failure" to Cell(false, SceneSourceState.RELEASED),
                "revoked" to Cell(false, SceneSourceState.RELEASED)
            )
        )
        for ((state, events) in table) {
            for ((event, expected) in events) {
                val lifecycle = lifecycleIn(state)
                val applied = when (event) {
                    "start" -> lifecycle.start()
                    "stop" -> lifecycle.stop()
                    "release" -> lifecycle.release()
                    "failure" -> lifecycle.onStartFailure()
                    else -> lifecycle.onProjectionRevoked()
                }
                val tag = "state=$state event=$event"
                assertEquals(tag, expected.applied, applied)
                assertEquals(tag, expected.end, lifecycle.state)
            }
        }
    }

    private fun lifecycleIn(state: SceneSourceState): SceneSourceLifecycle {
        val lifecycle = SceneSourceLifecycle()
        when (state) {
            SceneSourceState.CREATED -> Unit
            SceneSourceState.STARTED -> lifecycle.start()
            SceneSourceState.STOPPED -> {
                lifecycle.start()
                lifecycle.stop()
            }
            SceneSourceState.FAILED -> {
                lifecycle.start()
                lifecycle.onStartFailure()
            }
            SceneSourceState.RELEASED -> lifecycle.release()
        }
        assertEquals(state, lifecycle.state)
        return lifecycle
    }
}
