package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Main-thread timer seam for retry backoff. The production implementation
 * posts on the service handler; tests use a deterministic fake.
 */
interface RecoveryTimer {
    fun schedule(delayMs: Long)
    fun cancel()
}

/**
 * Socket-only retry seam. The production implementation calls RootEncoder's
 * `getStreamClient().reTry(0, reason)` and returns its result, so a refused
 * retry (counter exhausted) or a thrown exception both surface here.
 */
fun interface SocketRetrier {
    fun reTry(reason: String): Boolean
}

/** Service effects requested by the driver; the service executes them. */
sealed interface RecoveryEffect {
    /** Publish [message] as the current session status. */
    data class Publish(val message: String) : RecoveryEffect

    /** End the session with [message]; no further retry is allowed. */
    data class Stop(val message: String) : RecoveryEffect

    /** Leave the session untouched. */
    data object None : RecoveryEffect
}

/**
 * Framework-free orchestrator for socket-only RTMP recovery.
 *
 * Owns the [StreamRecoveryState], the pending backoff and the pending reason,
 * and translates connection callbacks, timer fires and stop requests into
 * [RecoveryEffect] values the service executes. All policy calls stay inside
 * [StreamConnectionPolicy], so this class only sequences decisions, the timer
 * and the single socket-retry call.
 *
 * Main-thread confined, like the service handler and RootEncoder's
 * main-thread connection callbacks; no locking is required.
 *
 * Pure Kotlin with no Android dependencies; covered by JVM unit tests with
 * deterministic fake [RecoveryTimer] / [SocketRetrier] implementations.
 */
class StreamRecoveryDriver(
    private val timer: RecoveryTimer,
    private val retrier: SocketRetrier
) {
    var state: StreamRecoveryState = StreamRecoveryState()
        private set
    private var pendingReason: String = ""

    /** Reset for a fresh session; cancels any backoff left by a previous one. */
    fun startSession() {
        timer.cancel()
        pendingReason = ""
        state = StreamRecoveryState(phase = StreamPhase.STARTING)
    }

    /**
     * Handle an RTMP connection failure. Schedules the next bounded backoff,
     * stops terminally, or ignores late/duplicate callbacks.
     */
    fun onConnectionFailed(
        reason: String,
        stopping: Boolean,
        hasStream: Boolean,
        hasProjection: Boolean,
        nowMs: Long
    ): RecoveryEffect {
        return when (val action = StreamConnectionPolicy.decideConnectionFailed(
            state, stopping, hasStream, hasProjection, reason
        )) {
            is RecoveryAction.ScheduleRetry -> {
                state = StreamConnectionPolicy.onRetryScheduled(state, nowMs)
                pendingReason = reason
                timer.cancel()
                timer.schedule(action.delayMs)
                RecoveryEffect.Publish(StreamConnectionPolicy.reconnectMessage(action.attempt))
            }
            is RecoveryAction.Stop -> RecoveryEffect.Stop(action.message)
            RecoveryAction.Ignore -> RecoveryEffect.None
        }
    }

    /**
     * Fire a scheduled backoff. Only the RTMP socket is re-established via
     * the injected retrier; capture, encoders, mixer and MediaProjection are
     * never touched here. A stale fire (no backoff pending, e.g. after a
     * success or stop already consumed it) is ignored without touching the
     * socket, so it can never drop a live connection.
     */
    fun onRetryTimerFired(stopping: Boolean, hasStream: Boolean): RecoveryEffect {
        if (stopping || !hasStream || !state.retryPending) return RecoveryEffect.None
        val attempt = state.attemptsUsed + 1
        state = StreamConnectionPolicy.onRetryFired(state, attempt)
        val accepted = try {
            retrier.reTry(pendingReason)
        } catch (_: Exception) {
            false
        }
        return if (accepted) RecoveryEffect.None
        else RecoveryEffect.Stop(StreamConnectionPolicy.exhaustedMessage())
    }

    /**
     * Handle an RTMP connection success.
     *
     * @return true when this success ended a reconnect episode. Any pending
     * backoff is always cancelled, so a success can never be followed by a
     * stale retry against the live socket.
     */
    fun onConnectionSuccess(): Boolean {
        val wasReconnecting = state.phase == StreamPhase.RECONNECTING
        timer.cancel()
        pendingReason = ""
        state = StreamConnectionPolicy.onConnectionSuccess(state)
        return wasReconnecting
    }

    /**
     * Record positive outgoing bitrate. The first frames complete the
     * STARTING → LIVE transition, both on initial startup and after a
     * reconnect episode ended in STARTING. Ignored while RECONNECTING: only
     * a connection success may end an episode.
     */
    fun onPositiveBitrate() {
        if (state.phase == StreamPhase.STARTING) {
            state = state.copy(phase = StreamPhase.LIVE)
        }
    }

    /** Route one watchdog tick through the policy decision. */
    fun onWatchdogTick(
        streamingConfirmed: Boolean,
        lastPositiveAtMs: Long,
        nowMs: Long
    ): WatchdogAction = StreamConnectionPolicy.decideWatchdog(
        state, streamingConfirmed, lastPositiveAtMs, nowMs
    )

    /**
     * True while the startup bitrate bound still applies: a session that has
     * not confirmed frames yet and is not inside a reconnect episode (episodes
     * are governed by the overall episode bound instead).
     */
    fun isStartupBoundActive(streamingConfirmed: Boolean): Boolean =
        !streamingConfirmed && state.phase != StreamPhase.RECONNECTING

    /** Take stop ownership; cancels any pending backoff. Idempotent. */
    fun onStop() {
        timer.cancel()
        pendingReason = ""
        state = StreamConnectionPolicy.onStopped(state)
    }

    /** Terminal transition for service destruction. */
    fun onDestroyed() {
        timer.cancel()
        pendingReason = ""
        state = state.copy(phase = StreamPhase.STOPPED)
    }
}
