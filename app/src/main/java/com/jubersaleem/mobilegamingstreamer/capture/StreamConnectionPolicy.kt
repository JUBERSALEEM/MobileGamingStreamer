package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Explicit broadcast connection phases.
 *
 * The service previously derived its state implicitly (`display` null checks,
 * `streamingConfirmed`, `stopping`). Long-session recovery needs an explicit
 * RECONNECTING phase so the watchdogs, the UI and the overlay can tell "the
 * socket dropped and we are recovering" apart from "the session is dead".
 *
 * Pure Kotlin with no Android dependencies.
 */
enum class StreamPhase { IDLE, STARTING, LIVE, RECONNECTING, STOPPING, STOPPED }

/**
 * Socket-recovery progress for one failure episode.
 *
 * @param phase explicit connection phase; RECONNECTING marks a live session
 * whose RTMP socket dropped and is being re-established.
 * @param attemptsUsed reconnect attempts already consumed in this episode.
 * @param retryPending true while a backoff is scheduled but not yet fired.
 * @param episodeStartMs monotonic timestamp of the first failure, or 0 when
 * no episode is open. The watchdog enforces the overall episode bound from it.
 */
data class StreamRecoveryState(
    val phase: StreamPhase = StreamPhase.IDLE,
    val attemptsUsed: Int = 0,
    val retryPending: Boolean = false,
    val episodeStartMs: Long = 0L
)

/** Pure decision for a connection-failure callback. */
sealed interface RecoveryAction {
    /** Schedule attempt [attempt] after [delayMs]; the socket alone is retried. */
    data class ScheduleRetry(val attempt: Int, val delayMs: Long) : RecoveryAction

    /** End the session with [message]; no further retry is allowed. */
    data class Stop(val message: String) : RecoveryAction

    /** Late or duplicate callback; leave the session untouched. */
    data object Ignore : RecoveryAction
}

/** Pure watchdog decision. */
enum class WatchdogAction { NONE, STOP_STALLED, STOP_RECONNECT_TIMEOUT }

/**
 * Bounded RTMP reconnect policy for long gaming sessions.
 *
 * Recovery reuses the live MediaProjection session and the running
 * encoders/audio pipeline and only re-establishes the RTMP socket through
 * RootEncoder's official retry API (`setReTries` + `reTry`), so a transient
 * network loss never demands fresh Android capture consent. Attempts are
 * bounded in count, per-attempt backoff and overall duration: recovery can
 * never turn into an infinite aggressive reconnect loop.
 *
 * Pure Kotlin with no Android dependencies; covered by JVM unit tests.
 */
object StreamConnectionPolicy {
    /** Maximum socket-reconnect attempts per failure episode. */
    const val MAX_ATTEMPTS = 5

    /** Delay before the first reconnect attempt. */
    const val BASE_DELAY_MS = 2_000L

    /** Cap for the exponential per-attempt backoff. */
    const val MAX_DELAY_MS = 30_000L

    /**
     * Overall bound for one reconnect episode, measured from the first
     * failure. The watchdog enforces this even if individual attempts hang
     * without reporting failure, so recovery always terminates.
     */
    const val OVERALL_TIMEOUT_MS = 120_000L

    /**
     * Delay before the given 1-based attempt: 2s, 4s, 8s, 16s, then capped
     * at 30s. Deterministic so session behaviour is reproducible and the
     * overlay can state the next step truthfully.
     */
    fun retryDelayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) { delay = (delay * 2).coerceAtMost(MAX_DELAY_MS) }
        return delay
    }

    /** Attempts still available after [attemptsUsed] have been consumed. */
    fun attemptsRemaining(attemptsUsed: Int): Int =
        (MAX_ATTEMPTS - attemptsUsed).coerceAtLeast(0)

    /**
     * True when a connection-failure reason is worth retrying. Authentication
     * failures, malformed endpoints and explicit permanent server rejections
     * (bad stream name / rejected connection) are terminal: retrying them
     * can never succeed and only burns battery. This mirrors RootEncoder's
     * own `RtmpClient.shouldRetry` exclusion for malformed endpoints.
     * Ambiguous server drops ("Closed", "Publish.Failed") stay retryable:
     * they can be transient, and the attempt/overall bounds cap the cost.
     */
    fun isRetryableReason(reason: String): Boolean {
        val lower = reason.lowercase()
        if ("endpoint malformed" in lower) return false
        if ("auth" in lower) return false
        if ("badname" in lower || "rejected" in lower) return false
        return true
    }

    /** Status line published while a reconnect attempt is pending. */
    fun reconnectMessage(attempt: Int): String =
        "Reconnecting to streaming server… attempt $attempt of $MAX_ATTEMPTS"

    /** Status line published when the episode exhausts its attempts. */
    fun exhaustedMessage(): String =
        "Unable to restore the connection after $MAX_ATTEMPTS attempts. Check your network, then tap GO LIVE for a fresh session."

    /**
     * Terminal failure line, preserved exactly from the pre-recovery
     * behaviour. Retried failures never use this text; only failures the
     * policy classifies as non-retryable do.
     */
    fun terminalFailureMessage(reason: String): String =
        "Connection failed: $reason. Open the app and tap GO LIVE to grant a fresh screen-capture session and retry."

    /** Shared "capture permission ended" line; recovery never retries without a live projection. */
    const val PROJECTION_ENDED_MESSAGE = "Screen capture permission ended"

    /** Bitrate silence that classifies a confirmed session as stalled. */
    const val STREAM_STALL_TIMEOUT_MS = 20_000L

    /**
     * True when an open reconnect episode has consumed its overall time
     * budget. The clock starts at the first failure and covers backoff waits,
     * handshake waits and callback latency alike, so a stalled episode can
     * never outlive [OVERALL_TIMEOUT_MS] once any decision point runs.
     */
    fun isEpisodeExpired(state: StreamRecoveryState, nowMs: Long): Boolean =
        state.phase == StreamPhase.RECONNECTING &&
            state.episodeStartMs > 0L &&
            nowMs - state.episodeStartMs >= OVERALL_TIMEOUT_MS

    /**
     * Pure decision for an RTMP connection-failure callback.
     *
     * @param state current recovery snapshot.
     * @param stopping true once the user/system stop path owns the session.
     * @param hasStream false when the stream object is already gone (late callback).
     * @param hasProjection false when capture consent ended (never retry without it).
     * @param nowMs monotonic clock; a failure arriving after the episode
     * deadline stops the session instead of scheduling another retry, so a
     * delayed callback can never extend the episode.
     */
    fun decideConnectionFailed(
        state: StreamRecoveryState,
        stopping: Boolean,
        hasStream: Boolean,
        hasProjection: Boolean,
        reason: String,
        nowMs: Long
    ): RecoveryAction {
        if (stopping || state.phase == StreamPhase.STOPPING || state.phase == StreamPhase.STOPPED) {
            return RecoveryAction.Ignore
        }
        if (!hasStream) return RecoveryAction.Ignore
        // Fail fast once the deadline passed, even if a backoff is still
        // pending: waiting for it to fire would only extend the episode.
        if (isEpisodeExpired(state, nowMs)) return RecoveryAction.Stop(exhaustedMessage())
        // A duplicate failure while a backoff is already in flight must not
        // consume an extra attempt or reschedule the pending one.
        if (state.retryPending) return RecoveryAction.Ignore
        if (!hasProjection) return RecoveryAction.Stop(PROJECTION_ENDED_MESSAGE)
        if (!isRetryableReason(reason)) return RecoveryAction.Stop(terminalFailureMessage(reason))
        val attempt = state.attemptsUsed + 1
        if (attempt > MAX_ATTEMPTS) return RecoveryAction.Stop(exhaustedMessage())
        return RecoveryAction.ScheduleRetry(attempt, retryDelayMs(attempt))
    }

    /** Snapshot update when a retry backoff is scheduled. */
    fun onRetryScheduled(state: StreamRecoveryState, nowMs: Long): StreamRecoveryState =
        state.copy(
            phase = StreamPhase.RECONNECTING,
            retryPending = true,
            episodeStartMs = if (state.episodeStartMs == 0L) nowMs else state.episodeStartMs
        )

    /** Snapshot update when a scheduled backoff fires and consumes its attempt. */
    fun onRetryFired(state: StreamRecoveryState, attempt: Int): StreamRecoveryState =
        state.copy(attemptsUsed = attempt, retryPending = false)

    /**
     * Snapshot update on RTMP connection success. A success that ends a
     * reconnect episode resets the episode; any other success leaves the
     * snapshot untouched.
     */
    fun onConnectionSuccess(state: StreamRecoveryState): StreamRecoveryState =
        if (state.phase == StreamPhase.RECONNECTING) StreamRecoveryState(phase = StreamPhase.STARTING)
        else state

    /** Snapshot update when the stop path takes ownership (also cancels a pending retry). */
    fun onStopped(state: StreamRecoveryState): StreamRecoveryState =
        state.copy(phase = StreamPhase.STOPPING, retryPending = false)

    /**
     * Pure watchdog decision. While reconnecting, only the overall episode
     * bound can stop the session — bitrate silence is expected and must not
     * trigger the stall stop. Otherwise the historical stall rule applies.
     */
    fun decideWatchdog(
        state: StreamRecoveryState,
        streamingConfirmed: Boolean,
        lastPositiveAtMs: Long,
        nowMs: Long
    ): WatchdogAction {
        if (state.phase == StreamPhase.RECONNECTING) {
            return if (isEpisodeExpired(state, nowMs)) WatchdogAction.STOP_RECONNECT_TIMEOUT
            else WatchdogAction.NONE
        }
        return if (streamingConfirmed && lastPositiveAtMs > 0L &&
            nowMs - lastPositiveAtMs >= STREAM_STALL_TIMEOUT_MS
        ) {
            WatchdogAction.STOP_STALLED
        } else {
            WatchdogAction.NONE
        }
    }
}
