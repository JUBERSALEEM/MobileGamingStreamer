package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Relationship between the Phase C health verdict and the authoritative
 * Phase B watchdog action on one tick. The action always wins; this only
 * classifies whether health corroborates it, contradicts it, or has no
 * standing to judge it.
 */
enum class HealthAgreement {
    /** Health evidence corroborates the authoritative action. */
    AGREE,

    /** Health evidence contradicts the authoritative action (sensor signal, never a veto). */
    DISAGREE,

    /** Health has no standing here (startup, mirror states, held safeguards). */
    NO_EXPECTATION
}

/**
 * Diagnostic outcome for one watchdog tick. Observational only: [action]
 * echoes the already-computed authoritative decision verbatim and must be
 * executed unchanged. [id] is a stable token so device logs and bug reports
 * stay comparable across versions; the remaining fields carry the structured
 * evidence behind it.
 */
data class WatchdogDiagnosis(
    val id: String,
    val action: WatchdogAction,
    val agreement: HealthAgreement,
    val encoderProgressing: Boolean,
    val senderProgressing: Boolean,
    val transmissionProgressing: Boolean,
    val attemptsUsed: Int,
    val episodeElapsedMs: Long
)

/**
 * Diagnose one watchdog tick from the health verdict, the session/recovery
 * context and the already-computed authoritative action.
 *
 * Pure function with no Android dependencies and no side effects: it never
 * re-evaluates the watchdog decision, never touches timers or sockets, and
 * cannot schedule retries or stops. The caller must pass the action that
 * [StreamConnectionPolicy.decideWatchdog] returned for the same inputs on
 * this tick; the returned [WatchdogDiagnosis.action] echoes it unchanged.
 *
 * Diagnostic matrix (action column always equals the authoritative input):
 *
 * - HEALTHY + NONE → `healthy_progress_continues` (AGREE)
 * - DEGRADED + NONE → `degraded_but_flowing` (AGREE: pressure, but flowing,
 *   explicitly not a failure)
 * - UNKNOWN + anything → `unknown_has_no_standing` (NO_EXPECTATION: UNKNOWN
 *   neither generates nor vetoes a stop)
 * - STALLED + STOP_STALLED → `stall_corroborated` (AGREE, with the
 *   encoder/sender/transmission split in the details)
 * - STALLED + NONE while unconfirmed or reconnecting inside the deadline →
 *   `stall_noted_safeguard_holds` (NO_EXPECTATION: safeguards hold, no bypass)
 * - STALLED + NONE while confirmed and non-reconnecting →
 *   `stall_hold_pending_confirmation` (DISAGREE: fresh evidence arrived but
 *   the two-tick hold has not cleared yet)
 * - RECOVERING + NONE while reconnecting → `recovering_mirrors_driver`
 *   (NO_EXPECTATION: the mirror carries no independent prediction)
 * - RECOVERING + NONE while not reconnecting → `recovering_mirror_stale`
 *   (NO_EXPECTATION: one-tick sampler lag after the episode ended)
 * - RECOVERING + STOP_STALLED → `recovering_but_stall_rule_fired`
 *   (DISAGREE: stale mirror, the stall rule fires on fresh inputs)
 * - Open unexpired episode + NONE + stale non-mirror health →
 *   `deadline_approaching` (NO_EXPECTATION: the episode clock is running;
 *   "approaching" reuses the existing episode bound, no new threshold)
 * - STOP_RECONNECT_TIMEOUT + flowing health (HEALTHY/DEGRADED) →
 *   `deadline_authoritative_over_flowing` (DISAGREE: the deadline wins, a
 *   late healthy sample can never revive the episode)
 * - STOP_RECONNECT_TIMEOUT + non-flowing health → `deadline_expired`
 *   (NO_EXPECTATION: the timeout owns the stop)
 * - HEALTHY/DEGRADED + STOP_STALLED → `healthy_but_stall_rule_fired` /
 *   `degraded_but_stall_rule_fired` (DISAGREE: bitrate-silence rule fires
 *   despite flowing health — a sensor signal for device debugging)
 *
 * No new thresholds are introduced: every condition reuses existing inputs
 * (health state, recovery phase, confirmation flag, authoritative action).
 */
fun diagnoseWatchdogTick(
    health: StreamHealth,
    recoveryState: StreamRecoveryState,
    streamingConfirmed: Boolean,
    action: WatchdogAction,
    transmissionProgressing: Boolean,
    senderProgressing: Boolean,
    encoderProgressing: Boolean,
    nowMs: Long
): WatchdogDiagnosis {
    val episodeElapsedMs = if (recoveryState.episodeStartMs > 0L) {
        (nowMs - recoveryState.episodeStartMs).coerceAtLeast(0L)
    } else {
        0L
    }
    fun details(id: String, agreement: HealthAgreement) = WatchdogDiagnosis(
        id = id,
        action = action,
        agreement = agreement,
        encoderProgressing = encoderProgressing,
        senderProgressing = senderProgressing,
        transmissionProgressing = transmissionProgressing,
        attemptsUsed = recoveryState.attemptsUsed,
        episodeElapsedMs = episodeElapsedMs
    )

    if (action == WatchdogAction.STOP_RECONNECT_TIMEOUT) {
        // The deadline owns every expired stop, even against flowing health.
        return if (health == StreamHealth.HEALTHY || health == StreamHealth.DEGRADED) {
            details("deadline_authoritative_over_flowing", HealthAgreement.DISAGREE)
        } else {
            details("deadline_expired", HealthAgreement.NO_EXPECTATION)
        }
    }
    if (action == WatchdogAction.STOP_STALLED) {
        return when (health) {
            StreamHealth.STALLED ->
                details("stall_corroborated", HealthAgreement.AGREE)
            StreamHealth.HEALTHY ->
                details("healthy_but_stall_rule_fired", HealthAgreement.DISAGREE)
            StreamHealth.DEGRADED ->
                details("degraded_but_stall_rule_fired", HealthAgreement.DISAGREE)
            StreamHealth.RECOVERING ->
                details("recovering_but_stall_rule_fired", HealthAgreement.DISAGREE)
            StreamHealth.UNKNOWN ->
                details("unknown_has_no_standing", HealthAgreement.NO_EXPECTATION)
        }
    }
    // No authoritative stop on this tick.
    if (health == StreamHealth.STALLED) {
        return if (!streamingConfirmed || recoveryState.phase == StreamPhase.RECONNECTING) {
            details("stall_noted_safeguard_holds", HealthAgreement.NO_EXPECTATION)
        } else {
            details("stall_hold_pending_confirmation", HealthAgreement.DISAGREE)
        }
    }
    if (health == StreamHealth.RECOVERING) {
        return if (recoveryState.phase == StreamPhase.RECONNECTING) {
            details("recovering_mirrors_driver", HealthAgreement.NO_EXPECTATION)
        } else {
            details("recovering_mirror_stale", HealthAgreement.NO_EXPECTATION)
        }
    }
    if (recoveryState.phase == StreamPhase.RECONNECTING) {
        // Open episode inside the deadline (an expired one would have stopped
        // above) with health that is not mirroring it: the sampler lagged a
        // tick while the episode clock kept running.
        return details("deadline_approaching", HealthAgreement.NO_EXPECTATION)
    }
    return when (health) {
        StreamHealth.HEALTHY ->
            details("healthy_progress_continues", HealthAgreement.AGREE)
        StreamHealth.DEGRADED ->
            details("degraded_but_flowing", HealthAgreement.AGREE)
        StreamHealth.UNKNOWN ->
            details("unknown_has_no_standing", HealthAgreement.NO_EXPECTATION)
        // STALLED and RECOVERING returned above; unreachable here.
        else ->
            details("unknown_has_no_standing", HealthAgreement.NO_EXPECTATION)
    }
}

/**
 * Pure log gate for watchdog diagnoses. Every authoritative stop is logged
 * (stops are terminal, so they cannot repeat); disagreements are logged only
 * when the diagnostic id changed since the previous tick, so a persisting
 * condition logs once instead of every five seconds. Steady NONE ticks stay
 * silent. [previousId] is the id diagnosed on the previous tick, or null
 * when there was none (fresh session).
 */
fun shouldLogDiagnosis(previousId: String?, diagnosis: WatchdogDiagnosis): Boolean {
    if (diagnosis.action != WatchdogAction.NONE) return true
    return diagnosis.agreement == HealthAgreement.DISAGREE && diagnosis.id != previousId
}
