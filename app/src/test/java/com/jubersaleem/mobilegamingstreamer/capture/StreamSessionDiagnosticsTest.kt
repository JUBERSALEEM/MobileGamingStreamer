package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Phase F session diagnostics. Every test drives
 * the real production [StreamSessionDiagnostics] (and [sanitizeDiagnosticText])
 * with fixed session tokens and fixed clocks: event ordering, session
 * association, bounded history, terminal-report accuracy, stale isolation
 * and credential redaction. No Android dependencies, no sleeps. Each test
 * uses a local diagnostics instance.
 */
class StreamSessionDiagnosticsTest {
    private companion object {
        const val TOKEN_A = 11L
        const val TOKEN_B = 12L
        const val START = 1_000_000L
    }

    private class FakeTimer : RecoveryTimer {
        val scheduled = mutableListOf<Long>()
        override fun schedule(delayMs: Long) {
            scheduled.add(delayMs)
        }
        override fun cancel() { }
    }

    private class FakeRetrier : SocketRetrier {
        override fun reTry(reason: String): Boolean = true
    }

    private class Harness {
        val timer = FakeTimer()
        val driver = StreamRecoveryDriver(timer, FakeRetrier())
        val router = StreamCallbackRouter(driver)

        fun startSession(): Long {
            val token = router.beginSession()
            driver.startSession()
            return token
        }
    }

    private class FatalBoom : Error()

    @Test
    fun recordBeforeSessionDropped() {
        val log = StreamSessionDiagnostics()
        assertFalse(log.hasSession)
        assertFalse(log.record(TOKEN_A, DiagnosticEventId.SESSION_STARTED, "x", START))
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun eventsOrderedOldestFirst() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        assertTrue(log.hasSession)
        assertTrue(log.record(TOKEN_A, DiagnosticEventId.SESSION_STARTED, "1080p", START))
        assertTrue(log.record(TOKEN_A, DiagnosticEventId.STREAM_CONFIRMED, "frames", START + 3_000L))
        assertTrue(log.record(TOKEN_A, DiagnosticEventId.STREAM_STOPPED, "done", START + 9_000L))
        val snapshot = log.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(DiagnosticEventId.SESSION_STARTED, snapshot[0].id)
        assertEquals(DiagnosticEventId.STREAM_CONFIRMED, snapshot[1].id)
        assertEquals(DiagnosticEventId.STREAM_STOPPED, snapshot[2].id)
        assertEquals("frames", snapshot[1].detail)
    }

    @Test
    fun newSessionClearsPreviousEvents() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.STREAM_CONFIRMED, "frames", START)
        log.startSession(TOKEN_B, START + 60_000L)
        assertTrue(log.snapshot().isEmpty())
        assertTrue(log.record(TOKEN_B, DiagnosticEventId.SESSION_STARTED, "720p", START + 60_000L))
        assertEquals(1, log.snapshot().size)
    }

    /** Stale posts can never contaminate the active session's diagnostics. */
    @Test
    fun staleTokenEventsDropped() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.STREAM_CONFIRMED, "A frames", START)
        log.startSession(TOKEN_B, START + 60_000L)
        assertFalse(log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "A late failure", START + 61_000L))
        assertFalse(log.record(TOKEN_A, DiagnosticEventId.STREAM_STOPPED, "A late stop", START + 62_000L))
        assertTrue(log.record(TOKEN_B, DiagnosticEventId.SESSION_STARTED, "B started", START + 60_000L))
        val snapshot = log.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("B started", snapshot[0].detail)
    }

    @Test
    fun recoveryLifecycleReporting() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "No response from server", START + 40_000L)
        log.record(TOKEN_A, DiagnosticEventId.RETRY_SCHEDULED, "attempt 1 of 5 in 2000ms", START + 40_000L)
        log.record(TOKEN_A, DiagnosticEventId.RETRY_FIRED, "attempt 1 of 5", START + 42_000L)
        log.record(TOKEN_A, DiagnosticEventId.RECOVERY_SUCCEEDED, "socket re-established within the deadline", START + 43_000L)
        val ids = log.snapshot().map { it.id }
        assertEquals(
            listOf(
                DiagnosticEventId.CONNECTION_FAILED,
                DiagnosticEventId.RETRY_SCHEDULED,
                DiagnosticEventId.RETRY_FIRED,
                DiagnosticEventId.RECOVERY_SUCCEEDED
            ),
            ids
        )
    }

    @Test
    fun expiryReporting() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "No response from server", START + 40_000L)
        log.record(TOKEN_A, DiagnosticEventId.RECOVERY_EXPIRED, "success arrived after the deadline", START + 160_000L)
        val report = log.terminalReport(
            reason = "Unable to restore the connection after 5 attempts.",
            endMs = START + 161_000L,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.STALLED,
            attemptsUsed = 5,
            staleDropped = 0
        )
        assertTrue(report.contains(DiagnosticEventId.RECOVERY_EXPIRED))
        assertTrue(report.contains("Attempts used: 5"))
        assertTrue(report.contains("Unable to restore the connection after 5 attempts."))
    }

    @Test
    fun terminalReportAccuracy() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.SESSION_STARTED, "1080p30", START)
        log.record(TOKEN_A, DiagnosticEventId.STREAM_CONFIRMED, "first frames", START + 3_000L)
        log.record(TOKEN_A, DiagnosticEventId.HEALTH_TRANSITION, "UNKNOWN -> HEALTHY", START + 15_000L)
        log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "boom", START + 40_000L)
        log.record(TOKEN_A, DiagnosticEventId.RETRY_SCHEDULED, "attempt 1 of 5 in 2000ms", START + 40_000L)
        log.record(TOKEN_A, DiagnosticEventId.RETRY_FIRED, "attempt 1 of 5", START + 42_000L)
        log.record(TOKEN_A, DiagnosticEventId.RECOVERY_SUCCEEDED, "re-established", START + 43_000L)
        log.record(TOKEN_A, DiagnosticEventId.HEALTH_TRANSITION, "HEALTHY -> DEGRADED", START + 100_000L)
        log.record(TOKEN_A, DiagnosticEventId.STREAM_STOPPED, "Broadcast stopped", START + 142_000L)
        val report = log.terminalReport(
            reason = "Broadcast stopped",
            endMs = START + 142_000L,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.DEGRADED,
            attemptsUsed = 1,
            staleDropped = 2
        )
        val lines = report.lines()
        assertEquals("Session 11 • 142s • STOPPING/DEGRADED", lines[0])
        assertTrue(report.contains("Reason: Broadcast stopped"))
        assertTrue(report.contains("Attempts used: 1 • stale posts rejected: 2"))
        assertTrue(report.contains("Health: UNKNOWN -> HEALTHY > HEALTHY -> DEGRADED"))
        assertTrue(report.contains("+3s ${DiagnosticEventId.STREAM_CONFIRMED} first frames"))
        assertTrue(report.contains("+142s ${DiagnosticEventId.STREAM_STOPPED} Broadcast stopped"))
    }

    @Test
    fun boundedHistoryEvictsOldest() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        repeat(StreamSessionDiagnostics.MAX_EVENTS + 10) { index ->
            assertTrue(log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "e$index", START + index))
        }
        val snapshot = log.snapshot()
        assertEquals(StreamSessionDiagnostics.MAX_EVENTS, snapshot.size)
        assertEquals("e10", snapshot.first().detail)
        assertEquals("e${StreamSessionDiagnostics.MAX_EVENTS + 9}", snapshot.last().detail)
    }

    @Test
    fun sanitizeRedactsRtmpUrl() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED,
            "connect to rtmp://live.example.com/app/secretKey123 failed", START)
        val detail = log.snapshot().single().detail
        assertTrue(detail.contains("<url-redacted>"))
        assertFalse(detail.contains("secretKey123"))
        assertFalse(detail.contains("live.example.com"))
    }

    @Test
    fun sanitizeRedactsSecretAssignments() {
        assertEquals("auth failed key=<redacted>", sanitizeDiagnosticText("auth failed key=abc123"))
        assertEquals("login password=<redacted> denied", sanitizeDiagnosticText("login password=hunter2 denied"))
        assertEquals("bad TOKEN=<redacted>", sanitizeDiagnosticText("bad TOKEN : xyz"))
    }

    @Test
    fun sanitizeLeavesOrdinaryTextUntouched() {
        assertEquals("No response from server", sanitizeDiagnosticText("No response from server"))
        assertEquals("attempt 1 of 5 in 2000ms", sanitizeDiagnosticText("attempt 1 of 5 in 2000ms"))
        assertEquals("UNKNOWN -> HEALTHY", sanitizeDiagnosticText("UNKNOWN -> HEALTHY"))
    }

    @Test
    fun reportSanitizesReason() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        val report = log.terminalReport(
            reason = "Unable to start: rtmp://x/y/key999",
            endMs = START,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.UNKNOWN,
            attemptsUsed = 0,
            staleDropped = 0
        )
        assertTrue(report.contains("<url-redacted>"))
        assertFalse(report.contains("key999"))
    }

    /** Reporting degrades gracefully: empty logs, bare sessions and odd clocks still render. */
    @Test
    fun reportHandlesBareSession() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        val report = log.terminalReport(
            reason = "Broadcast stopped",
            endMs = START,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.UNKNOWN,
            attemptsUsed = 0,
            staleDropped = 0
        )
        assertTrue(report.contains("Session 11 • 0s • STOPPING/UNKNOWN"))
        assertTrue(report.contains("Health: none observed"))
        assertTrue(report.contains("Events: none recorded"))
    }

    /** An ordinary recording failure is contained; control flow continues. */
    @Test
    fun guardSwallowsOrdinaryException() {
        var continued = false
        guardDiagnosticAction { throw IllegalStateException("diag boom") }
        continued = true
        assertTrue(continued)
    }

    /** Fatal JVM errors are never caught by the diagnostic guard. */
    @Test
    fun guardDoesNotCatchFatalErrors() {
        var propagated = false
        try {
            guardDiagnosticAction { throw FatalBoom() }
        } catch (_: FatalBoom) {
            propagated = true
        }
        assertTrue(propagated)
    }

    /**
     * Mirrors the service EXPIRED branch through the real router: the guarded
     * recording throws, yet the authoritative EXPIRED outcome still reaches
     * the stop path with the driver state to match.
     */
    @Test
    fun recordingFailureCannotSuppressRecoveryStop() {
        val h = Harness()
        val token = h.startSession()
        val failed = h.router.onConnectionFailed(token, "boom", stopping = false,
            hasStream = true, hasProjection = true, nowMs = 10_000L)
        assertTrue(failed is RecoveryEffect.Publish)
        var stopExecuted = false
        guardDiagnosticAction { throw IllegalStateException("diag boom") }
        val outcome = h.router.onConnectionSuccess(token, stopping = false, nowMs = 130_000L)
        if (outcome == SuccessOutcome.EXPIRED) {
            h.driver.onStop()
            stopExecuted = true
        }
        assertEquals(SuccessOutcome.EXPIRED, outcome)
        assertTrue(stopExecuted)
        assertEquals(StreamPhase.STOPPING, h.driver.state.phase)
    }

    /**
     * Mirrors the service failure branch through the real router: the guarded
     * recording throws, yet the Publish effect is still produced, scheduled
     * and observable exactly as without the failure.
     */
    @Test
    fun recordingFailureCannotSuppressPublishHandling() {
        val h = Harness()
        val token = h.startSession()
        guardDiagnosticAction { throw IllegalStateException("diag boom") }
        val effect = h.router.onConnectionFailed(token, "boom", stopping = false,
            hasStream = true, hasProjection = true, nowMs = START)
        assertTrue(effect is RecoveryEffect.Publish)
        assertEquals(listOf(2_000L), h.timer.scheduled)
        assertEquals(StreamPhase.RECONNECTING, h.driver.state.phase)
    }

    /**
     * Mirrors the watchdog tick through the real policy: the guarded health
     * recording throws, yet the authoritative stall verdict is unchanged.
     */
    @Test
    fun recordingFailureCannotSuppressWatchdogStop() {
        val state = StreamRecoveryState(phase = StreamPhase.LIVE)
        guardDiagnosticAction { throw IllegalStateException("diag boom") }
        val action = StreamConnectionPolicy.decideWatchdog(
            state, streamingConfirmed = true, lastPositiveAtMs = 1_000L, nowMs = 30_000L
        )
        assertEquals(WatchdogAction.STOP_STALLED, action)
    }

    /** Stop ownership still lands after a recording failure on the stop path. */
    @Test
    fun recordingFailureCannotSuppressStopOwnership() {
        val h = Harness()
        h.startSession()
        guardDiagnosticAction { throw IllegalStateException("diag boom") }
        h.driver.onStop()
        assertEquals(StreamPhase.STOPPING, h.driver.state.phase)
    }

    @Test
    fun additionalCredentialFormatsRedacted() {
        assertEquals("stream_key=<redacted>", sanitizeDiagnosticText("stream_key=abc123"))
        assertEquals("streamKey=<redacted>", sanitizeDiagnosticText("streamKey=abc123"))
        assertEquals("key=<redacted>", sanitizeDiagnosticText("key=abc123"))
        assertEquals("password=<redacted>", sanitizeDiagnosticText("password=hunter2"))
        assertEquals("token=<redacted>", sanitizeDiagnosticText("token: xyz"))
        assertEquals("{\"key=<redacted>}", sanitizeDiagnosticText("{\"key\": \"abc123\"}"))
        assertEquals("'password=<redacted>", sanitizeDiagnosticText("'password': 'my secret value'"))
        assertEquals("\"stream_key=<redacted>", sanitizeDiagnosticText("\"stream_key\": \"abc\""))
        assertEquals("failed <url-redacted> sig",
            sanitizeDiagnosticText("failed rtmp://user:pass@host/app/key sig"))
        assertEquals("<url-redacted>", sanitizeDiagnosticText("RTMPS://H/A/K"))
    }

    /** Ordinary diagnostics — including tricky near-misses — stay readable. */
    @Test
    fun ordinaryMessagesPreserved() {
        assertEquals("No response from server", sanitizeDiagnosticText("No response from server"))
        assertEquals("keyboard=x", sanitizeDiagnosticText("keyboard=x"))
        assertEquals("authmod=llnw", sanitizeDiagnosticText("authmod=llnw"))
        assertEquals("token ring network ok", sanitizeDiagnosticText("token ring network ok"))
    }

    @Test
    fun storedDetailLengthBounded() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        val long = "e".repeat(2000)
        assertTrue(log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, long, START))
        val stored = log.snapshot().single().detail
        assertEquals(StreamSessionDiagnostics.MAX_DETAIL_LENGTH + "…[truncated]".length, stored.length)
        assertTrue(stored.endsWith("…[truncated]"))
        assertTrue(stored.startsWith("e".repeat(StreamSessionDiagnostics.MAX_DETAIL_LENGTH)))
    }

    @Test
    fun terminalReportSizeBounded() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        repeat(StreamSessionDiagnostics.MAX_EVENTS) { index ->
            log.record(TOKEN_A, DiagnosticEventId.CONNECTION_FAILED, "d".repeat(600), START + index)
        }
        val report = log.terminalReport(
            reason = "r".repeat(2000),
            endMs = START + 60_000L,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.STALLED,
            attemptsUsed = 5,
            staleDropped = 1
        )
        assertTrue(report.contains("…[truncated]"))
        assertEquals(StreamSessionDiagnostics.MAX_EVENTS + 5, report.lines().size)
        assertTrue("report must stay bounded, was ${report.length}", report.length < 45_000)
    }

    @Test
    fun reportClampsNegativeOffsets() {
        val log = StreamSessionDiagnostics()
        log.startSession(TOKEN_A, START)
        log.record(TOKEN_A, DiagnosticEventId.SESSION_STARTED, "late clock", START - 5_000L)
        val report = log.terminalReport(
            reason = "x",
            endMs = START - 9_000L,
            phase = StreamPhase.STOPPING,
            health = StreamHealth.UNKNOWN,
            attemptsUsed = 0,
            staleDropped = 0
        )
        assertTrue(report.contains("Session 11 • 0s •"))
        assertTrue(report.contains("+0s ${DiagnosticEventId.SESSION_STARTED} late clock"))
    }
}
