package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing tests for the Phase E callback seam. Every test drives the real
 * production [StreamCallbackRouter] (which owns the real [StreamSessionGate]
 * and shares a real [StreamRecoveryDriver]) with scripted session tokens,
 * fixed clocks and deterministic fakes — the same decisions the service's
 * checker and projection callbacks execute, including the guard order (stale
 * token first, stopping second, driver last). Negative assertions prove stale
 * callbacks select no effect AND leave the driver, timer and retrier
 * untouched. The thin service wiring (token capture, effect execution) stays
 * inspection- plus CI-verified. No Android dependencies, no sleeps.
 */
class StreamCallbackRouterTest {
    private class FakeTimer : RecoveryTimer {
        val scheduled = mutableListOf<Long>()
        var cancels = 0
        override fun schedule(delayMs: Long) {
            scheduled.add(delayMs)
        }
        override fun cancel() {
            cancels++
        }
    }

    private class FakeRetrier : SocketRetrier {
        val reasons = mutableListOf<String>()
        var nextResult = true
        override fun reTry(reason: String): Boolean {
            reasons.add(reason)
            return nextResult
        }
    }

    // Fresh harness per test: the runner reuses one test-class instance,
    // so shared fields would leak gate/driver/timer state between tests.
    private class Harness {
        val timer = FakeTimer()
        val retrier = FakeRetrier()
        val driver = StreamRecoveryDriver(timer, retrier)
        val router = StreamCallbackRouter(driver)

        /** Mirrors service startBroadcast order: token first, then driver reset. */
        fun startSession(): Long {
            val token = router.beginSession()
            driver.startSession()
            return token
        }
    }

    private companion object {
        const val NOW = 50_000L
        const val BITRATE = 4_000_000L
    }

    /** Scenario 1: START A → START B → delayed A onDisconnect; B remains active. */
    @Test
    fun replacementSessionSurvivesDelayedDisconnect() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertFalse("stale disconnect must not tear down B", h.router.onDisconnect(tokenA, stopping = false))
        assertTrue("current disconnect must still tear down", h.router.onDisconnect(tokenB, stopping = false))
    }

    /** Scenario 7: a stale disconnect selects no teardown and mutates nothing. */
    @Test
    fun staleDisconnectLeavesDriverAndTimerUntouched() {
        val h = Harness()
        val tokenA = h.startSession()
        h.router.onConnectionFailed(tokenA, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW)
        val schedulesBefore = h.timer.scheduled.size
        val tokenB = h.startSession()
        assertFalse(h.router.onDisconnect(tokenA, stopping = false))
        // No teardown effect means the service body behind it — releasing the
        // display/projection and publishing terminal state — never runs for B.
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertFalse(h.driver.state.retryPending)
        assertEquals(schedulesBefore, h.timer.scheduled.size)
        assertTrue(h.retrier.reasons.isEmpty())
        assertTrue(h.router.onDisconnect(tokenB, stopping = false))
    }

    /**
     * Scenario 2: START A → destroy → delayed A callbacks; every routing
     * method rejects them and the terminal driver state is unchanged.
     */
    @Test
    fun destroyedSessionRejectsEveryCallback() {
        val h = Harness()
        val tokenA = h.startSession()
        h.router.onConnectionFailed(tokenA, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
        val schedulesBefore = h.timer.scheduled.size
        h.driver.onDestroyed()
        h.router.invalidate()
        assertFalse(h.router.onConnectionStarted(tokenA, stopping = false))
        assertEquals(null, h.router.onConnectionSuccess(tokenA, stopping = false, nowMs = NOW))
        assertEquals(null, h.router.onConnectionFailed(tokenA, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW))
        assertFalse(h.router.onDisconnect(tokenA, stopping = false))
        assertFalse(h.router.onAuthError(tokenA))
        assertFalse(h.router.onAuthSuccess(tokenA, stopping = false))
        assertEquals(BitrateRouting.None, h.router.onNewBitrate(tokenA, BITRATE, stopping = false, streamingConfirmed = true))
        assertFalse(h.router.onProjectionStop(tokenA, hasDisplay = true))
        // Terminal state preserved: no schedule, no socket call, no mutation.
        assertEquals(schedulesBefore, h.timer.scheduled.size)
        assertTrue(h.retrier.reasons.isEmpty())
        assertEquals(StreamPhase.STOPPED, h.driver.state.phase)
    }

    /** Scenario 3: START A → STOP A → START B → delayed A checker callbacks. */
    @Test
    fun newSessionAfterStopRejectsOldCheckerCallbacks() {
        val h = Harness()
        val tokenA = h.startSession()
        h.driver.onStop()
        val tokenB = h.startSession()
        assertFalse(h.router.onConnectionStarted(tokenA, stopping = false))
        assertEquals(null, h.router.onConnectionSuccess(tokenA, stopping = false, nowMs = NOW))
        assertEquals(null, h.router.onConnectionFailed(tokenA, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW))
        assertFalse(h.router.onDisconnect(tokenA, stopping = false))
        assertFalse(h.router.onAuthError(tokenA))
        assertFalse(h.router.onAuthSuccess(tokenA, stopping = false))
        assertEquals(BitrateRouting.None, h.router.onNewBitrate(tokenA, BITRATE, stopping = false, streamingConfirmed = false))
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertTrue(h.timer.scheduled.isEmpty())
        // B itself is fully live.
        assertTrue(h.router.onConnectionStarted(tokenB, stopping = false))
        assertTrue(h.router.onDisconnect(tokenB, stopping = false))
        assertTrue(h.retrier.reasons.isEmpty())
    }

    /** Scenario 4: a queued old projection onStop cannot stop B. */
    @Test
    fun staleProjectionStopCannotStopNewerSession() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertFalse(h.router.onProjectionStop(tokenA, hasDisplay = true))
        assertTrue(h.router.onProjectionStop(tokenB, hasDisplay = true))
        assertFalse("torn-down session needs no stop", h.router.onProjectionStop(tokenB, hasDisplay = false))
    }

    /** Scenario 5: an old authentication failure cannot stop B. */
    @Test
    fun staleAuthErrorCannotStopNewerSession() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertFalse(h.router.onAuthError(tokenA))
        assertTrue("current auth failure must still stop", h.router.onAuthError(tokenB))
    }

    /** Scenario 6: a current-session genuine disconnect still tears down. */
    @Test
    fun currentDisconnectRequestsTeardown() {
        val h = Harness()
        val token = h.startSession()
        assertTrue(h.router.onDisconnect(token, stopping = false))
        assertFalse("second disconnect while stopping stays silent", h.router.onDisconnect(token, stopping = true))
    }

    @Test
    fun startedRouting() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertTrue(h.router.onConnectionStarted(tokenB, stopping = false))
        assertFalse(h.router.onConnectionStarted(tokenA, stopping = false))
        assertFalse(h.router.onConnectionStarted(tokenB, stopping = true))
    }

    @Test
    fun successRouting() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertEquals(null, h.router.onConnectionSuccess(tokenA, stopping = false, nowMs = NOW))
        assertEquals(null, h.router.onConnectionSuccess(tokenB, stopping = true, nowMs = NOW))
        // Guarded calls never reached the driver: still STARTING, timer idle.
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertTrue(h.timer.scheduled.isEmpty())
        assertEquals(SuccessOutcome.CONNECTED, h.router.onConnectionSuccess(tokenB, stopping = false, nowMs = NOW))
    }

    /** Strict-deadline routing is preserved through the seam. */
    @Test
    fun expiredSuccessStillStopsThroughRouter() {
        val h = Harness()
        val token = h.startSession()
        val failed = h.router.onConnectionFailed(token, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = 10_000L)
        assertTrue(failed is RecoveryEffect.Publish)
        assertEquals(SuccessOutcome.EXPIRED, h.router.onConnectionSuccess(token, stopping = false, nowMs = 130_000L))
    }

    @Test
    fun failureRouting() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertEquals(null, h.router.onConnectionFailed(tokenA, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW))
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertTrue("stale failure must schedule no retry", h.timer.scheduled.isEmpty())
        val current = h.router.onConnectionFailed(tokenB, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW)
        assertTrue(current is RecoveryEffect.Publish)
        assertEquals(listOf(2_000L), h.timer.scheduled)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
    }

    /**
     * `stopping` passes through to the driver on the failure path (it never
     * short-circuits): the driver — not the guard — decides, returning its
     * own None rather than the guard's null.
     */
    @Test
    fun failureWhileStoppingPassesThroughToDriver() {
        val h = Harness()
        val token = h.startSession()
        val effect = h.router.onConnectionFailed(token, "boom", stopping = true, hasStream = true, hasProjection = true, nowMs = NOW)
        assertEquals(RecoveryEffect.None, effect)
        assertTrue(h.timer.scheduled.isEmpty())
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun bitrateRouting() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertEquals(BitrateRouting.None, h.router.onNewBitrate(tokenA, BITRATE, stopping = false, streamingConfirmed = false))
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertEquals(BitrateRouting.None, h.router.onNewBitrate(tokenB, BITRATE, stopping = true, streamingConfirmed = false))
        assertEquals(BitrateRouting.Zero, h.router.onNewBitrate(tokenB, 0L, stopping = false, streamingConfirmed = false))
        assertEquals(StreamPhase.STARTING, h.driver.state.phase)
        assertEquals(
            BitrateRouting.Positive(firstConfirmation = true, bitrate = BITRATE),
            h.router.onNewBitrate(tokenB, BITRATE, stopping = false, streamingConfirmed = false)
        )
        assertEquals(StreamPhase.LIVE, h.driver.state.phase)
        assertEquals(
            BitrateRouting.Positive(firstConfirmation = false, bitrate = BITRATE),
            h.router.onNewBitrate(tokenB, BITRATE, stopping = false, streamingConfirmed = true)
        )
    }

    /** Episode silence rule preserved: ticks during RECONNECTING carry no signal. */
    @Test
    fun bitrateDuringReconnectEpisodeIsSilent() {
        val h = Harness()
        val token = h.startSession()
        h.router.onConnectionFailed(token, "boom", stopping = false, hasStream = true, hasProjection = true, nowMs = NOW)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
        assertEquals(BitrateRouting.None, h.router.onNewBitrate(token, BITRATE, stopping = false, streamingConfirmed = true))
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun authSuccessRouting() {
        val h = Harness()
        val tokenA = h.startSession()
        val tokenB = h.startSession()
        assertTrue(h.router.onAuthSuccess(tokenB, stopping = false))
        assertFalse(h.router.onAuthSuccess(tokenA, stopping = false))
        assertFalse(h.router.onAuthSuccess(tokenB, stopping = true))
    }
}
