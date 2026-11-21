package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConnectionPolicyTest {
    @Test
    fun backoffDoublesPerAttempt() {
        assertEquals(2_000L, StreamConnectionPolicy.retryDelayMs(1))
        assertEquals(4_000L, StreamConnectionPolicy.retryDelayMs(2))
        assertEquals(8_000L, StreamConnectionPolicy.retryDelayMs(3))
        assertEquals(16_000L, StreamConnectionPolicy.retryDelayMs(4))
    }

    @Test
    fun backoffIsCapped() {
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(5))
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(6))
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(99))
    }

    @Test
    fun attemptsAreBounded() {
        assertEquals(5, StreamConnectionPolicy.attemptsRemaining(0))
        assertEquals(1, StreamConnectionPolicy.attemptsRemaining(4))
        assertEquals(0, StreamConnectionPolicy.attemptsRemaining(5))
        assertEquals(0, StreamConnectionPolicy.attemptsRemaining(9))
    }

    @Test
    fun transientNetworkFailuresAreRetryable() {
        listOf(
            "Connection failed: broken pipe",
            "No response from server",
            "Handshake failed",
            "Error configure stream, connection reset",
            "Connection failed: timeout",
            "onStatus: NetConnection.Connect.Closed",
            "onStatus: NetStream.Publish.Failed"
        ).forEach { reason ->
            assertTrue(reason, StreamConnectionPolicy.isRetryableReason(reason))
        }
    }

    @Test
    fun permanentServerRejectionsAreTerminal() {
        listOf(
            "onStatus: NetStream.Publish.BadName",
            "onStatus: NetConnection.Connect.Rejected"
        ).forEach { reason ->
            assertFalse(reason, StreamConnectionPolicy.isRetryableReason(reason))
            assertEquals(
                reason,
                RecoveryAction.Stop(StreamConnectionPolicy.terminalFailureMessage(reason)),
                StreamConnectionPolicy.decideConnectionFailed(
                    StreamRecoveryState(phase = StreamPhase.LIVE),
                    stopping = false, hasStream = true, hasProjection = true, reason = reason, nowMs = 50_000L
                )
            )
        }
    }

    @Test
    fun authAndMalformedEndpointsAreTerminal() {
        listOf(
            "Endpoint malformed, should be: rtmp://ip:port/appname/streamname",
            "Stream authentication failed. Check the stream key.",
            "Auth error from server"
        ).forEach { reason ->
            assertFalse(reason, StreamConnectionPolicy.isRetryableReason(reason))
        }
    }

    @Test
    fun reconnectMessagesStateProgress() {
        assertEquals(
            "Reconnecting to streaming server… attempt 1 of 5",
            StreamConnectionPolicy.reconnectMessage(1)
        )
        assertEquals(
            "Reconnecting to streaming server… attempt 5 of 5",
            StreamConnectionPolicy.reconnectMessage(5)
        )
        assertTrue(
            StreamConnectionPolicy.exhaustedMessage().contains("after 5 attempts")
        )
    }

    @Test
    fun recoverableFailureSchedulesFirstRetry() {
        val action = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.LIVE),
            stopping = false, hasStream = true, hasProjection = true,
            reason = "Connection failed: broken pipe", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.ScheduleRetry(1, 2_000L), action)
    }

    @Test
    fun retryAttemptsIncrementWithBackoff() {
        val action = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, attemptsUsed = 2),
            stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.ScheduleRetry(3, 8_000L), action)
    }

    @Test
    fun retryExhaustionStopsSession() {
        val last = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, attemptsUsed = 4),
            stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.ScheduleRetry(5, 30_000L), last)
        val exhausted = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, attemptsUsed = 5),
            stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.Stop(StreamConnectionPolicy.exhaustedMessage()), exhausted)
    }

    @Test
    fun authAndMalformedFailuresStopWithoutRetry() {
        listOf(
            "Endpoint malformed, should be: rtmp://ip:port/appname/streamname",
            "Stream authentication failed"
        ).forEach { reason ->
            val action = StreamConnectionPolicy.decideConnectionFailed(
                StreamRecoveryState(phase = StreamPhase.STARTING),
                stopping = false, hasStream = true, hasProjection = true, reason = reason, nowMs = 50_000L
            )
            assertEquals(RecoveryAction.Stop(StreamConnectionPolicy.terminalFailureMessage(reason)), action)
        }
    }

    @Test
    fun projectionLossStopsWithoutRetry() {
        val action = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.LIVE),
            stopping = false, hasStream = true, hasProjection = false,
            reason = "Connection failed: broken pipe", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.Stop(StreamConnectionPolicy.PROJECTION_ENDED_MESSAGE), action)
    }

    @Test
    fun duplicateFailureWhileRetryPendingIsIgnored() {
        val action = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, retryPending = true),
            stopping = false, hasStream = true, hasProjection = true,
            reason = "Connection failed: broken pipe", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.Ignore, action)
    }

    @Test
    fun lateCallbacksAfterStopAreIgnored() {
        val stoppingAction = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, attemptsUsed = 1),
            stopping = true, hasStream = true, hasProjection = true,
            reason = "Connection failed: broken pipe", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.Ignore, stoppingAction)
        listOf(StreamPhase.STOPPING, StreamPhase.STOPPED).forEach { phase ->
            val action = StreamConnectionPolicy.decideConnectionFailed(
                StreamRecoveryState(phase = phase),
                stopping = false, hasStream = true, hasProjection = true,
                reason = "Connection failed: broken pipe", nowMs = 50_000L
            )
            assertEquals(phase.name, RecoveryAction.Ignore, action)
        }
        val goneAction = StreamConnectionPolicy.decideConnectionFailed(
            StreamRecoveryState(phase = StreamPhase.LIVE),
            stopping = false, hasStream = false, hasProjection = true,
            reason = "Connection failed: broken pipe", nowMs = 50_000L
        )
        assertEquals(RecoveryAction.Ignore, goneAction)
    }

    @Test
    fun successfulReconnectionResetsEpisode() {
        val reset = StreamConnectionPolicy.onConnectionSuccess(
            StreamRecoveryState(
                phase = StreamPhase.RECONNECTING, attemptsUsed = 3,
                retryPending = false, episodeStartMs = 123L
            )
        )
        assertEquals(StreamRecoveryState(phase = StreamPhase.STARTING), reset)
    }

    @Test
    fun unrelatedSuccessLeavesSnapshotUntouched() {
        val live = StreamRecoveryState(phase = StreamPhase.LIVE)
        assertEquals(live, StreamConnectionPolicy.onConnectionSuccess(live))
        val starting = StreamRecoveryState(phase = StreamPhase.STARTING)
        assertEquals(starting, StreamConnectionPolicy.onConnectionSuccess(starting))
    }

    @Test
    fun stopDuringPendingRetryCancelsIt() {
        val stopped = StreamConnectionPolicy.onStopped(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, attemptsUsed = 2, retryPending = true)
        )
        assertEquals(StreamPhase.STOPPING, stopped.phase)
        assertFalse(stopped.retryPending)
        assertEquals(
            RecoveryAction.Ignore,
            StreamConnectionPolicy.decideConnectionFailed(
                stopped, stopping = true, hasStream = true, hasProjection = true,
                reason = "Connection failed: broken pipe", nowMs = 50_000L
            )
        )
    }

    @Test
    fun retryFiredConsumesItsAttempt() {
        val fired = StreamConnectionPolicy.onRetryFired(
            StreamRecoveryState(phase = StreamPhase.RECONNECTING, retryPending = true),
            attempt = 2
        )
        assertEquals(2, fired.attemptsUsed)
        assertFalse(fired.retryPending)
        assertEquals(StreamPhase.RECONNECTING, fired.phase)
    }

    @Test
    fun episodeExpiryBoundariesAreExact() {
        val open = StreamRecoveryState(phase = StreamPhase.RECONNECTING, episodeStartMs = 10_000L)
        assertFalse(StreamConnectionPolicy.isEpisodeExpired(open, 129_999L))
        assertTrue(StreamConnectionPolicy.isEpisodeExpired(open, 130_000L))
        assertTrue(StreamConnectionPolicy.isEpisodeExpired(open, 9_999_999L))
        assertFalse(
            StreamConnectionPolicy.isEpisodeExpired(
                StreamRecoveryState(phase = StreamPhase.RECONNECTING, episodeStartMs = 0L), 9_999_999L
            )
        )
        assertFalse(
            StreamConnectionPolicy.isEpisodeExpired(
                StreamRecoveryState(phase = StreamPhase.LIVE, episodeStartMs = 10_000L), 9_999_999L
            )
        )
    }

    @Test
    fun failureAfterDeadlineStopsInsteadOfScheduling() {
        val open = StreamRecoveryState(
            phase = StreamPhase.RECONNECTING, attemptsUsed = 1, episodeStartMs = 10_000L
        )
        val before = StreamConnectionPolicy.decideConnectionFailed(
            open, stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 129_999L
        )
        assertEquals(RecoveryAction.ScheduleRetry(2, 4_000L), before)
        val at = StreamConnectionPolicy.decideConnectionFailed(
            open, stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 130_000L
        )
        assertEquals(RecoveryAction.Stop(StreamConnectionPolicy.exhaustedMessage()), at)
    }

    @Test
    fun expiredEpisodeWithPendingBackoffFailsFast() {
        val pending = StreamRecoveryState(
            phase = StreamPhase.RECONNECTING, attemptsUsed = 1,
            retryPending = true, episodeStartMs = 10_000L
        )
        val action = StreamConnectionPolicy.decideConnectionFailed(
            pending, stopping = false, hasStream = true, hasProjection = true,
            reason = "No response from server", nowMs = 200_000L
        )
        assertEquals(RecoveryAction.Stop(StreamConnectionPolicy.exhaustedMessage()), action)
    }

    @Test
    fun watchdogDuringReconnectionOnlyEnforcesOverallBound() {
        val open = StreamRecoveryState(phase = StreamPhase.RECONNECTING, episodeStartMs = 10_000L)
        assertEquals(
            WatchdogAction.NONE,
            StreamConnectionPolicy.decideWatchdog(open, streamingConfirmed = true, lastPositiveAtMs = 0L, nowMs = 60_000L)
        )
        assertEquals(
            WatchdogAction.STOP_RECONNECT_TIMEOUT,
            StreamConnectionPolicy.decideWatchdog(open, streamingConfirmed = true, lastPositiveAtMs = 0L, nowMs = 130_001L)
        )
    }

    @Test
    fun watchdogOutsideReconnectionKeepsStallRule() {
        val live = StreamRecoveryState(phase = StreamPhase.LIVE)
        assertEquals(
            WatchdogAction.STOP_STALLED,
            StreamConnectionPolicy.decideWatchdog(live, streamingConfirmed = true, lastPositiveAtMs = 1_000L, nowMs = 21_001L)
        )
        assertEquals(
            WatchdogAction.NONE,
            StreamConnectionPolicy.decideWatchdog(live, streamingConfirmed = true, lastPositiveAtMs = 10_000L, nowMs = 21_000L)
        )
        assertEquals(
            WatchdogAction.NONE,
            StreamConnectionPolicy.decideWatchdog(live, streamingConfirmed = true, lastPositiveAtMs = 0L, nowMs = 99_000_000L)
        )
        assertEquals(
            WatchdogAction.NONE,
            StreamConnectionPolicy.decideWatchdog(
                StreamRecoveryState(phase = StreamPhase.STARTING),
                streamingConfirmed = false, lastPositiveAtMs = 0L, nowMs = 99_000_000L
            )
        )
    }
}
