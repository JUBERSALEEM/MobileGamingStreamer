package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Phase D watchdog/health diagnostic layer.
 * Every test drives the real production [diagnoseWatchdogTick] (and
 * [shouldLogDiagnosis]) with consistent inputs — the passed action is always
 * the one [StreamConnectionPolicy.decideWatchdog] returns for the same
 * session context, mirroring the single per-tick evaluation in the service.
 * No Android dependencies.
 */
class WatchdogHealthDiagnosisTest {
    private companion object {
        const val NOW = 1_000_000L
    }

    private val live = StreamRecoveryState(phase = StreamPhase.LIVE)
    private val starting = StreamRecoveryState(phase = StreamPhase.STARTING)
    private val episodeOpen = StreamRecoveryState(
        phase = StreamPhase.RECONNECTING,
        attemptsUsed = 2,
        retryPending = false,
        episodeStartMs = NOW - 60_000L
    )
    private val episodeExpired = StreamRecoveryState(
        phase = StreamPhase.RECONNECTING,
        attemptsUsed = 5,
        retryPending = false,
        episodeStartMs = NOW - 120_000L
    )

    private fun diagnose(
        health: StreamHealth,
        recoveryState: StreamRecoveryState,
        streamingConfirmed: Boolean,
        action: WatchdogAction,
        transmission: Boolean = true,
        sender: Boolean = true,
        encoder: Boolean = true
    ): WatchdogDiagnosis = diagnoseWatchdogTick(
        health = health,
        recoveryState = recoveryState,
        streamingConfirmed = streamingConfirmed,
        action = action,
        transmissionProgressing = transmission,
        senderProgressing = sender,
        encoderProgressing = encoder,
        nowMs = NOW
    )

    @Test
    fun healthyNoneIsAgreement() {
        val d = diagnose(StreamHealth.HEALTHY, live, true, WatchdogAction.NONE)
        assertEquals("healthy_progress_continues", d.id)
        assertEquals(HealthAgreement.AGREE, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
        assertTrue(d.transmissionProgressing)
        assertEquals(0, d.attemptsUsed)
        assertEquals(0L, d.episodeElapsedMs)
    }

    @Test
    fun degradedNoneIsFlowingNotFailure() {
        val d = diagnose(StreamHealth.DEGRADED, live, true, WatchdogAction.NONE)
        assertEquals("degraded_but_flowing", d.id)
        assertEquals(HealthAgreement.AGREE, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
    }

    @Test
    fun unknownStartupHasNoStanding() {
        val d = diagnose(StreamHealth.UNKNOWN, starting, false, WatchdogAction.NONE)
        assertEquals("unknown_has_no_standing", d.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
    }

    @Test
    fun unknownReconnectingNoneHasNoStanding() {
        // UNKNOWN inside an open episode must never read as episode
        // vocabulary: no standing, and the NONE action echoes unchanged.
        val d = diagnose(StreamHealth.UNKNOWN, episodeOpen, true, WatchdogAction.NONE)
        assertEquals("unknown_has_no_standing", d.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
    }

    @Test
    fun stalledStopIsCorroborated() {
        val d = diagnose(
            StreamHealth.STALLED, live, true, WatchdogAction.STOP_STALLED,
            transmission = false, sender = false, encoder = false
        )
        assertEquals("stall_corroborated", d.id)
        assertEquals(HealthAgreement.AGREE, d.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, d.action)
        assertFalse(d.encoderProgressing)
        assertFalse(d.senderProgressing)
        assertFalse(d.transmissionProgressing)
    }

    @Test
    fun stalledStopKeepsEncoderTransmissionSplit() {
        // Encoder alive but nothing reaching the socket: the corroborated
        // stop still fires, with the split preserved in the details.
        val d = diagnose(
            StreamHealth.STALLED, live, true, WatchdogAction.STOP_STALLED,
            transmission = false, sender = true, encoder = true
        )
        assertEquals("stall_corroborated", d.id)
        assertEquals(WatchdogAction.STOP_STALLED, d.action)
        assertTrue(d.encoderProgressing)
        assertTrue(d.senderProgressing)
        assertFalse(d.transmissionProgressing)
    }

    @Test
    fun stalledWhileSafeguardsHoldIsNoted() {
        val unconfirmed = diagnose(StreamHealth.STALLED, starting, false, WatchdogAction.NONE)
        assertEquals("stall_noted_safeguard_holds", unconfirmed.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, unconfirmed.agreement)
        assertEquals(WatchdogAction.NONE, unconfirmed.action)
        val reconnecting = diagnose(StreamHealth.STALLED, episodeOpen, true, WatchdogAction.NONE)
        assertEquals("stall_noted_safeguard_holds", reconnecting.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, reconnecting.agreement)
        assertEquals(WatchdogAction.NONE, reconnecting.action)
    }

    @Test
    fun stalledHoldPendingConfirmation() {
        // Fresh evidence arrived but the two-tick hold has not cleared: no
        // stop, and the lagging verdict is flagged as disagreement, not agreement.
        val d = diagnose(
            StreamHealth.STALLED, live, true, WatchdogAction.NONE,
            transmission = true, sender = true, encoder = true
        )
        assertEquals("stall_hold_pending_confirmation", d.id)
        assertEquals(HealthAgreement.DISAGREE, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
        assertTrue(d.transmissionProgressing)
    }

    @Test
    fun recoveringMirrorsDriver() {
        val d = diagnose(StreamHealth.RECOVERING, episodeOpen, true, WatchdogAction.NONE)
        assertEquals("recovering_mirrors_driver", d.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(2, d.attemptsUsed)
        assertEquals(60_000L, d.episodeElapsedMs)
    }

    @Test
    fun expiredRecoveryStopsRegardlessOfHealth() {
        // A late healthy sample can never revive the episode: the echoed
        // action stays STOP and the diagnosis says the deadline wins.
        val flowing = diagnose(StreamHealth.HEALTHY, episodeExpired, true, WatchdogAction.STOP_RECONNECT_TIMEOUT)
        assertEquals("deadline_authoritative_over_flowing", flowing.id)
        assertEquals(HealthAgreement.DISAGREE, flowing.agreement)
        assertEquals(WatchdogAction.STOP_RECONNECT_TIMEOUT, flowing.action)
        assertEquals(5, flowing.attemptsUsed)
        assertEquals(120_000L, flowing.episodeElapsedMs)
        val mirror = diagnose(StreamHealth.RECOVERING, episodeExpired, true, WatchdogAction.STOP_RECONNECT_TIMEOUT)
        assertEquals("deadline_expired", mirror.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, mirror.agreement)
        assertEquals(WatchdogAction.STOP_RECONNECT_TIMEOUT, mirror.action)
    }

    @Test
    fun healthyStallDisagreementKeepsTheStop() {
        // Bitrate-silence rule fires despite flowing health: a sensor signal
        // for device debugging, never a veto — the stop stands.
        val d = diagnose(StreamHealth.HEALTHY, live, true, WatchdogAction.STOP_STALLED)
        assertEquals("healthy_but_stall_rule_fired", d.id)
        assertEquals(HealthAgreement.DISAGREE, d.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, d.action)
    }

    @Test
    fun degradedStallDisagreementKeepsTheStop() {
        val d = diagnose(StreamHealth.DEGRADED, live, true, WatchdogAction.STOP_STALLED)
        assertEquals("degraded_but_stall_rule_fired", d.id)
        assertEquals(HealthAgreement.DISAGREE, d.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, d.action)
    }

    @Test
    fun unknownNeverGeneratesNorVetoes() {
        val quiet = diagnose(StreamHealth.UNKNOWN, live, true, WatchdogAction.NONE)
        assertEquals("unknown_has_no_standing", quiet.id)
        assertEquals(WatchdogAction.NONE, quiet.action)
        // A stop decided on authoritative inputs stands: UNKNOWN is not a veto.
        val stall = diagnose(StreamHealth.UNKNOWN, live, true, WatchdogAction.STOP_STALLED)
        assertEquals("unknown_has_no_standing", stall.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, stall.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, stall.action)
        val timeout = diagnose(StreamHealth.UNKNOWN, episodeExpired, true, WatchdogAction.STOP_RECONNECT_TIMEOUT)
        assertEquals("deadline_expired", timeout.id)
        assertEquals(WatchdogAction.STOP_RECONNECT_TIMEOUT, timeout.action)
    }

    @Test
    fun diagnosisNeverChangesTheAuthoritativeAction() {
        val liveStates = listOf(
            Triple(StreamHealth.HEALTHY, true, live),
            Triple(StreamHealth.DEGRADED, true, live),
            Triple(StreamHealth.UNKNOWN, true, live),
            Triple(StreamHealth.STALLED, true, live),
            Triple(StreamHealth.RECOVERING, true, live)
        )
        for ((health, confirmed, state) in liveStates) {
            for (action in WatchdogAction.values()) {
                val d = diagnose(health, state, confirmed, action)
                assertEquals("$health + $action", action, d.action)
            }
        }
        val episodeStates = listOf(
            Triple(StreamHealth.HEALTHY, true, episodeOpen),
            Triple(StreamHealth.DEGRADED, true, episodeOpen),
            Triple(StreamHealth.UNKNOWN, true, episodeOpen),
            Triple(StreamHealth.STALLED, true, episodeOpen),
            Triple(StreamHealth.RECOVERING, true, episodeOpen),
            Triple(StreamHealth.HEALTHY, true, episodeExpired),
            Triple(StreamHealth.RECOVERING, true, episodeExpired)
        )
        for ((health, confirmed, state) in episodeStates) {
            for (action in WatchdogAction.values()) {
                val d = diagnose(health, state, confirmed, action)
                assertEquals("$health + $action", action, d.action)
            }
        }
    }

    @Test
    fun logGateSuppressesRepeats() {
        val disagree = diagnose(StreamHealth.HEALTHY, live, true, WatchdogAction.STOP_STALLED)
        val sameDisagree = diagnose(StreamHealth.HEALTHY, live, true, WatchdogAction.STOP_STALLED)
        val otherDisagree = diagnose(StreamHealth.DEGRADED, live, true, WatchdogAction.STOP_STALLED)
        val quiet = diagnose(StreamHealth.HEALTHY, live, true, WatchdogAction.NONE)
        // Stops always log, even with an unchanged id: they are terminal.
        assertTrue(shouldLogDiagnosis(disagree.id, sameDisagree))
        assertTrue(shouldLogDiagnosis(null, disagree))
        // Disagreements on NONE log once per id, then stay silent.
        val hold = diagnose(StreamHealth.STALLED, live, true, WatchdogAction.NONE)
        assertTrue(shouldLogDiagnosis(null, hold))
        assertTrue(shouldLogDiagnosis("healthy_progress_continues", hold))
        assertFalse(shouldLogDiagnosis(hold.id, hold))
        // Steady ticks never log.
        assertFalse(shouldLogDiagnosis(null, quiet))
        assertFalse(shouldLogDiagnosis(quiet.id, quiet))
        assertFalse(shouldLogDiagnosis(otherDisagree.id, quiet))
        val mirror = diagnose(StreamHealth.RECOVERING, episodeOpen, true, WatchdogAction.NONE)
        assertFalse(shouldLogDiagnosis(null, mirror))
    }

    @Test
    fun repeatedTicksArePureAndSideEffectFree() {
        // The diagnostic takes values and returns data: repeated identical
        // ticks produce equal outcomes and, by construction, can schedule no
        // retry, stop nothing and touch no socket or timer.
        val first = diagnose(StreamHealth.RECOVERING, episodeOpen, true, WatchdogAction.NONE)
        val second = diagnose(StreamHealth.RECOVERING, episodeOpen, true, WatchdogAction.NONE)
        val third = diagnose(StreamHealth.RECOVERING, episodeOpen, true, WatchdogAction.NONE)
        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals(WatchdogAction.NONE, third.action)
    }

    @Test
    fun retainedTerminalVerdictDiagnoses() {
        // The terminal tick's own pair: retained STALLED plus the stop the
        // authoritative rule fired. Terminal retention itself stays covered
        // by StreamHealthMonitorTest.terminalVerdictSurvivesHaltAndDestruction.
        val d = diagnose(
            StreamHealth.STALLED, live, true, WatchdogAction.STOP_STALLED,
            transmission = false, sender = false, encoder = false
        )
        assertEquals("stall_corroborated", d.id)
        assertEquals(HealthAgreement.AGREE, d.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, d.action)
    }

    @Test
    fun freshSessionUnknownHasNoStanding() {
        // Health sourced from a real fresh production monitor, not invented.
        val monitor = StreamHealthMonitor()
        monitor.startSession(NOW)
        val d = diagnose(monitor.state, starting, false, WatchdogAction.NONE)
        assertEquals(StreamHealth.UNKNOWN, monitor.state)
        assertEquals("unknown_has_no_standing", d.id)
        assertEquals(WatchdogAction.NONE, d.action)
    }

    @Test
    fun deadlineApproachingOnStaleMirror() {
        // Open episode inside the deadline with health that is not mirroring
        // it (one-tick sampler lag): the running clock is diagnosed without
        // inventing any new threshold.
        val d = diagnose(StreamHealth.HEALTHY, episodeOpen, true, WatchdogAction.NONE)
        assertEquals("deadline_approaching", d.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, d.agreement)
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(60_000L, d.episodeElapsedMs)
    }

    @Test
    fun staleRecoveringMirror() {
        // Sampler lagged the tick the episode ended: health still claims
        // RECOVERING while the driver moved on.
        val quiet = diagnose(StreamHealth.RECOVERING, live, true, WatchdogAction.NONE)
        assertEquals("recovering_mirror_stale", quiet.id)
        assertEquals(HealthAgreement.NO_EXPECTATION, quiet.agreement)
        assertEquals(WatchdogAction.NONE, quiet.action)
        // The same stale mirror against a real stall stop: flagged, not obeyed.
        val stop = diagnose(StreamHealth.RECOVERING, live, true, WatchdogAction.STOP_STALLED)
        assertEquals("recovering_but_stall_rule_fired", stop.id)
        assertEquals(HealthAgreement.DISAGREE, stop.agreement)
        assertEquals(WatchdogAction.STOP_STALLED, stop.action)
    }
}
