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
     * failures and malformed endpoints are terminal: retrying them can never
     * succeed and only burns battery. This mirrors RootEncoder's own
     * `RtmpClient.shouldRetry` exclusion for malformed endpoints.
     */
    fun isRetryableReason(reason: String): Boolean {
        val lower = reason.lowercase()
        if ("endpoint malformed" in lower) return false
        if ("auth" in lower) return false
        return true
    }

    /** Status line published while a reconnect attempt is pending. */
    fun reconnectMessage(attempt: Int): String =
        "Reconnecting to streaming server… attempt $attempt of $MAX_ATTEMPTS"

    /** Status line published when the episode exhausts its attempts. */
    fun exhaustedMessage(): String =
        "Reconnection failed after $MAX_ATTEMPTS attempts. Check your network, then tap GO LIVE for a fresh session."
}
