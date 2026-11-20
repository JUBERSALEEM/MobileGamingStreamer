package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the socket-recovery orchestration: callback
 * sequencing, timer scheduling, retry execution results and teardown, driven
 * through deterministic fakes instead of Android's Handler and RootEncoder.
 */
class StreamRecoveryDriverTest {
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
        var throwOnCall: Exception? = null
        override fun reTry(reason: String): Boolean {
            reasons.add(reason)
            throwOnCall?.let { throw it }
            return nextResult
        }
    }

    // Fresh harness per test: the runner reuses one test-class instance,
    // so shared fields would leak timer/retrier/driver state between tests.
    private class Harness {
        val timer = FakeTimer()
        val retrier = FakeRetrier()
        val driver = StreamRecoveryDriver(timer, retrier)

        fun fail(
            reason: String = "Connection failed: broken pipe",
            nowMs: Long = 1_000L
        ): RecoveryEffect = driver.onConnectionFailed(
            reason, stopping = false, hasStream = true, hasProjection = true, nowMs = nowMs
        )
    }

    @Test
    fun firstFailureSchedulesBackoffAndPublishesProgress() {
        val h = Harness()
        h.driver.startSession()
        val effect = h.fail()
        assertEquals(
            RecoveryEffect.Publish("Reconnecting to streaming server… attempt 1 of 5"),
            effect
        )
        assertEquals(listOf(2_000L), h.timer.scheduled)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
        assertTrue(h.driver.state.retryPending)
        assertEquals(1_000L, h.driver.state.episodeStartMs)
    }

    @Test
    fun timerFireRetriesSocketExactlyOnce() {
        val h = Harness()
        h.driver.startSession()
        h.fail("No response from server")
        val effect = h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        assertEquals(RecoveryEffect.None, effect)
        assertEquals(listOf("No response from server"), h.retrier.reasons)
        assertEquals(1, h.driver.state.attemptsUsed)
        assertFalse(h.driver.state.retryPending)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
    }

    @Test
    fun subsequentFailuresKeepEpisodeAndGrowBackoff() {
        val h = Harness()
        h.driver.startSession()
        h.fail(nowMs = 1_000L)
        h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        val effect = h.fail("No response from server", nowMs = 9_000L)
        assertEquals(
            RecoveryEffect.Publish("Reconnecting to streaming server… attempt 2 of 5"),
            effect
        )
        assertEquals(listOf(2_000L, 4_000L), h.timer.scheduled)
        assertEquals(1_000L, h.driver.state.episodeStartMs)
    }

    @Test
    fun fullEpisodeExhaustsAfterFiveAttempts() {
        val h = Harness()
        h.driver.startSession()
        var nowMs = 1_000L
        repeat(5) { index ->
            val effect = h.fail(nowMs = nowMs)
            assertEquals(
                RecoveryEffect.Publish("Reconnecting to streaming server… attempt ${index + 1} of 5"),
                effect
            )
            assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L))
            nowMs += 10_000L
        }
        assertEquals(5, h.retrier.reasons.size)
        val exhausted = h.fail(nowMs = nowMs)
        assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()), exhausted)
        assertEquals(5, h.retrier.reasons.size)
    }

    @Test
    fun refusedRetryStopsSession() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        h.retrier.nextResult = false
        val effect = h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()), effect)
    }

    @Test
    fun throwingRetryStopsSession() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        h.retrier.throwOnCall = RuntimeException("socket gone")
        val effect = h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()), effect)
    }

    @Test
    fun authAndMalformedFailuresStopWithoutScheduling() {
        val h = Harness()
        h.driver.startSession()
        listOf(
            "Endpoint malformed, should be: rtmp://ip:port/appname/streamname",
            "Stream authentication failed"
        ).forEach { reason ->
            val effect = h.fail(reason)
            assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.terminalFailureMessage(reason)), effect)
        }
        assertTrue(h.timer.scheduled.isEmpty())
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun permanentServerRejectionStopsButAmbiguousDropRetries() {
        val h = Harness()
        h.driver.startSession()
        listOf(
            "onStatus: NetStream.Publish.BadName",
            "onStatus: NetConnection.Connect.Rejected"
        ).forEach { reason ->
            val effect = h.fail(reason)
            assertEquals(
                reason,
                RecoveryEffect.Stop(StreamConnectionPolicy.terminalFailureMessage(reason)),
                effect
            )
        }
        assertTrue(h.timer.scheduled.isEmpty())
        val closed = h.fail("onStatus: NetConnection.Connect.Closed")
        assertTrue(closed is RecoveryEffect.Publish)
        val publishFailed = StreamRecoveryDriver(FakeTimer(), FakeRetrier()).apply { startSession() }
            .onConnectionFailed(
                "onStatus: NetStream.Publish.Failed", stopping = false,
                hasStream = true, hasProjection = true, nowMs = 1_000L
            )
        assertTrue(publishFailed is RecoveryEffect.Publish)
    }

    @Test
    fun duplicateFailureWhilePendingIsIgnored() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        val duplicate = h.fail("Connection failed: broken pipe")
        assertEquals(RecoveryEffect.None, duplicate)
        assertEquals(1, h.timer.scheduled.size)
    }

    @Test
    fun stopDuringPendingRetryCancelsAndDisarmsFire() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        val cancelsBefore = h.timer.cancels
        h.driver.onStop()
        assertEquals(cancelsBefore + 1, h.timer.cancels)
        assertEquals(StreamPhase.STOPPING, h.driver.state.phase)
        assertFalse(h.driver.state.retryPending)
        assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = true, hasStream = true, nowMs = 5_000L))
        assertTrue(h.retrier.reasons.isEmpty())
        assertEquals(
            RecoveryEffect.None,
            h.driver.onConnectionFailed(
                "Connection failed: broken pipe", stopping = true,
                hasStream = true, hasProjection = true, nowMs = 5_000L
            )
        )
    }

    @Test
    fun lateCallbacksAfterStopAreIgnored() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        h.driver.onStop()
        h.driver.onDestroyed()
        assertEquals(StreamPhase.STOPPED, h.driver.state.phase)
        assertEquals(
            RecoveryEffect.None,
            h.driver.onConnectionFailed(
                "Connection failed: broken pipe", stopping = false,
                hasStream = true, hasProjection = true, nowMs = 9_000L
            )
        )
        assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = false, hasStream = false, nowMs = 5_000L))
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun staleTimerFireWithoutPendingStateNeverTouchesSocket() {
        val h = Harness()
        h.driver.startSession()
        assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L))
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun successCancelsPendingRetryBeforeItCanFire() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        assertTrue(h.driver.onConnectionSuccess())
        // The cancelled backoff must be dead: a stale fire afterwards is
        // ignored and never calls reTry against the live socket.
        assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L))
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun successfulRecoveryThenIndependentFailureStartsFreshEpisode() {
        val h = Harness()
        h.driver.startSession()
        h.fail(nowMs = 1_000L)
        h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        h.fail("No response from server", nowMs = 9_000L)
        h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        assertTrue(h.driver.onConnectionSuccess())
        assertEquals(StreamRecoveryState(phase = StreamPhase.STARTING), h.driver.state)
        val next = h.fail("Connection failed: timeout", nowMs = 50_000L)
        assertEquals(
            RecoveryEffect.Publish("Reconnecting to streaming server… attempt 1 of 5"),
            next
        )
        assertEquals(50_000L, h.driver.state.episodeStartMs)
        assertEquals(0, h.driver.state.attemptsUsed)
    }

    @Test
    fun positiveBitrateCompletesLiveTransition() {
        val h = Harness()
        h.driver.startSession()
        h.driver.onPositiveBitrate()
        assertEquals(StreamPhase.LIVE, h.driver.state.phase)
        h.fail()
        h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        // Bitrate arriving mid-episode must not end the episode early.
        h.driver.onPositiveBitrate()
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
        assertTrue(h.driver.onConnectionSuccess())
        h.driver.onPositiveBitrate()
        assertEquals(StreamPhase.LIVE, h.driver.state.phase)
    }

    @Test
    fun watchdogTickHonoursEpisodeBound() {
        val h = Harness()
        h.driver.startSession()
        h.fail(nowMs = 10_000L)
        assertEquals(
            WatchdogAction.NONE,
            h.driver.onWatchdogTick(streamingConfirmed = true, lastPositiveAtMs = 0L, nowMs = 60_000L)
        )
        assertEquals(
            WatchdogAction.STOP_RECONNECT_TIMEOUT,
            h.driver.onWatchdogTick(streamingConfirmed = true, lastPositiveAtMs = 0L, nowMs = 130_001L)
        )
    }

    @Test
    fun startupBoundPausesDuringEpisodeAndResumesAfter() {
        val h = Harness()
        h.driver.startSession()
        assertTrue(h.driver.isStartupBoundActive(streamingConfirmed = false))
        h.fail()
        assertFalse(h.driver.isStartupBoundActive(streamingConfirmed = false))
        h.driver.onConnectionSuccess()
        assertTrue(h.driver.isStartupBoundActive(streamingConfirmed = false))
        assertFalse(h.driver.isStartupBoundActive(streamingConfirmed = true))
    }

    @Test
    fun lateFailureAfterDeadlineStopsInsteadOfScheduling() {
        val h = Harness()
        h.driver.startSession()
        h.fail(nowMs = 10_000L)
        h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 12_000L)
        val schedulesBefore = h.timer.scheduled.size
        val effect = h.fail("No response from server", nowMs = 130_000L)
        assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()), effect)
        assertEquals(schedulesBefore, h.timer.scheduled.size)
    }

    @Test
    fun lateTimerFireAfterDeadlineNeverTouchesSocket() {
        val h = Harness()
        h.driver.startSession()
        h.fail(nowMs = 10_000L)
        val effect = h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 130_001L)
        assertEquals(RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()), effect)
        assertTrue(h.retrier.reasons.isEmpty())
        assertFalse(h.driver.state.retryPending)
        assertEquals(0, h.driver.state.attemptsUsed)
    }

    @Test
    fun deadlineBoundaryScheduleAndFire() {
        val h = Harness()
        h.driver.startSession()
        val scheduled = h.fail(nowMs = 10_000L)
        assertTrue(scheduled is RecoveryEffect.Publish)
        // One millisecond before the deadline the fire still proceeds.
        assertEquals(
            RecoveryEffect.None,
            h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 129_999L)
        )
        assertEquals(1, h.retrier.reasons.size)

        val late = Harness()
        late.driver.startSession()
        late.fail(nowMs = 10_000L)
        // Exactly at the deadline the fire stops without touching the socket.
        assertEquals(
            RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()),
            late.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 130_000L)
        )
        assertTrue(late.retrier.reasons.isEmpty())
    }

    @Test
    fun secondFireWithoutRescheduleIsIgnored() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        assertEquals(
            RecoveryEffect.None,
            h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 5_000L)
        )
        assertEquals(
            RecoveryEffect.None,
            h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = 6_000L)
        )
        assertEquals(1, h.retrier.reasons.size)
        assertEquals(1, h.driver.state.attemptsUsed)
    }

    @Test
    fun successAfterStopLeavesTeardownAlone() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        h.driver.onStop()
        assertFalse(h.driver.onConnectionSuccess())
        assertEquals(StreamPhase.STOPPING, h.driver.state.phase)
        assertTrue(h.timer.scheduled.size == 1)
        assertTrue(h.retrier.reasons.isEmpty())
    }

    @Test
    fun twoConsecutiveEpisodesEachGetFiveAttempts() {
        val h = Harness()
        h.driver.startSession()
        var nowMs = 1_000L
        repeat(5) {
            h.fail(nowMs = nowMs)
            nowMs += 2_000L
            assertEquals(
                RecoveryEffect.None,
                h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = nowMs)
            )
            nowMs += 2_000L
        }
        assertEquals(5, h.retrier.reasons.size)
        assertTrue(h.driver.onConnectionSuccess())
        h.driver.onPositiveBitrate()
        assertEquals(StreamPhase.LIVE, h.driver.state.phase)
        nowMs += 50_000L
        repeat(5) { index ->
            val effect = h.fail(nowMs = nowMs)
            assertEquals(
                RecoveryEffect.Publish("Reconnecting to streaming server… attempt ${index + 1} of 5"),
                effect
            )
            nowMs += 2_000L
            assertEquals(
                RecoveryEffect.None,
                h.driver.onRetryTimerFired(stopping = false, hasStream = true, nowMs = nowMs)
            )
            nowMs += 2_000L
        }
        assertEquals(10, h.retrier.reasons.size)
        assertEquals(
            RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage()),
            h.fail(nowMs = nowMs)
        )
        assertEquals(10, h.retrier.reasons.size)
    }

    @Test
    fun teardownIsIdempotent() {
        val h = Harness()
        h.driver.startSession()
        h.fail()
        h.driver.onStop()
        h.driver.onStop()
        h.driver.onDestroyed()
        assertEquals(StreamPhase.STOPPED, h.driver.state.phase)
        assertFalse(h.driver.state.retryPending)
        assertEquals(RecoveryEffect.None, h.driver.onRetryTimerFired(stopping = true, hasStream = true, nowMs = 5_000L))
        assertTrue(h.retrier.reasons.isEmpty())
    }
}
