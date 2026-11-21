package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Routing decision for an [onNewBitrate] callback that survived the guards.
 * The service executes the matching effect; only [Positive] carries data.
 */
sealed interface BitrateRouting {
    /** Stale, stopping, or inside a reconnect episode: leave the session untouched. */
    data object None : BitrateRouting

    /** Connected but no outgoing video yet: publish the waiting line. */
    data object Zero : BitrateRouting

    /** Positive bitrate: confirm the session; [firstConfirmation] arms one-time setup. */
    data class Positive(val firstConfirmation: Boolean, val bitrate: Long) : BitrateRouting
}

/**
 * Testable routing seam for the service's asynchronous stream callbacks.
 *
 * Owns the [StreamSessionGate] and shares the service's
 * [StreamRecoveryDriver], and makes every routing decision each callback
 * needs: session-ownership guard first, stopping guard second, then the
 * driver call and the effect selection. The service passes its captured
 * session token plus a snapshot of the inputs each decision reads, and
 * executes the returned effect with its Android resources (handler,
 * notification, stream, projection). Callbacks that already carry their own
 * identity (the fps listener's stream reference) or are posted on the
 * service's own handler and removed at every boundary (watchdog, startup,
 * heartbeat, retry timer) are not routed here.
 *
 * Guard order is load-bearing and uniform: a stale token returns the silent
 * effect before the stopping flag is read and before the driver is touched,
 * so a delayed post from a previous session can never schedule a retry,
 * mutate the episode, or select a teardown/stop/publish effect for the
 * current session. The two exceptions match the service behaviour exactly:
 * [onConnectionFailed] passes `stopping` through to the driver instead of
 * short-circuiting (the driver decides), and [onAuthError] takes no
 * `stopping` flag because the service stop it requests is idempotent.
 *
 * Every stale-token rejection increments [staleDropped] (observational only;
 * same-session stopping guards never count). The terminal diagnostic report
 * reads it as evidence of cross-session isolation in production.
 *
 * Pure Kotlin with no Android dependencies; covered by JVM unit tests with
 * deterministic fakes and fixed clocks.
 */
class StreamCallbackRouter(private val driver: StreamRecoveryDriver) {
    private val gate = StreamSessionGate()

    /** Stale-token rejections since the current session began. */
    var staleDropped = 0
        private set

    /**
     * Starts a new session, invalidating every previously issued token.
     * Returns the token the new session's callbacks must capture. Must run
     * before any previous-session resource is released.
     */
    fun beginSession(): Long {
        staleDropped = 0
        return gate.beginSession()
    }

    /** Terminal invalidation (service destruction): no outstanding token stays valid. */
    fun invalidate() {
        gate.invalidate()
    }

    /**
     * Route `onConnectionStarted`. Returns true when the service must
     * publish the connecting line.
     */
    fun onConnectionStarted(token: Long, stopping: Boolean): Boolean {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return false
        }
        if (stopping) return false
        return true
    }

    /**
     * Route `onConnectionSuccess`. Returns the driver outcome for the
     * service to execute, or null when guarded (stale token or stopping):
     * the driver is left untouched, so no episode is opened, closed, or
     * expired and no pending backoff is cancelled.
     */
    fun onConnectionSuccess(token: Long, stopping: Boolean, nowMs: Long): SuccessOutcome? {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return null
        }
        if (stopping) return null
        return driver.onConnectionSuccess(nowMs)
    }

    /**
     * Route `onConnectionFailed`. Returns the driver effect for the service
     * to execute, or null for a stale token (driver untouched: no retry is
     * scheduled and no episode opens). `stopping` passes through to the
     * driver, which decides; it never short-circuits here.
     */
    fun onConnectionFailed(
        token: Long,
        reason: String,
        stopping: Boolean,
        hasStream: Boolean,
        hasProjection: Boolean,
        nowMs: Long
    ): RecoveryEffect? {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return null
        }
        return driver.onConnectionFailed(reason, stopping, hasStream, hasProjection, nowMs)
    }

    /**
     * Route `onDisconnect`. Returns true when the service must run its
     * teardown body (releasing the stream and projection, publishing the
     * terminal state).
     */
    fun onDisconnect(token: Long, stopping: Boolean): Boolean {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return false
        }
        if (stopping) return false
        return true
    }

    /**
     * Route `onAuthError`. Returns true when the service must stop for an
     * authentication failure. Takes no stopping flag: the requested stop is
     * idempotent, so a current-session callback always routes to it.
     */
    fun onAuthError(token: Long): Boolean {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return false
        }
        return true
    }

    /**
     * Route `onAuthSuccess`. Returns true when the service must publish the
     * key-accepted line.
     */
    fun onAuthSuccess(token: Long, stopping: Boolean): Boolean {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return false
        }
        if (stopping) return false
        return true
    }

    /**
     * Route `onNewBitrate`. Records positive bitrate on the driver outside
     * reconnect episodes (only a connection success may end an episode) and
     * returns the effect for the service to execute.
     */
    fun onNewBitrate(
        token: Long,
        bitrate: Long,
        stopping: Boolean,
        streamingConfirmed: Boolean
    ): BitrateRouting {
        if (!gate.isCurrent(token)) {
            staleDropped++
            return BitrateRouting.None
        }
        if (stopping) return BitrateRouting.None
        if (driver.state.phase == StreamPhase.RECONNECTING) return BitrateRouting.None
        if (bitrate <= 0L) return BitrateRouting.Zero
        driver.onPositiveBitrate()
        return BitrateRouting.Positive(firstConfirmation = !streamingConfirmed, bitrate = bitrate)
    }

    /**
     * Route a queued projection `onStop`. Returns true only when the posting
     * session is still current and still holds a display.
     */
    fun onProjectionStop(token: Long, hasDisplay: Boolean): Boolean {
        if (!hasDisplay) return false
        if (!gate.isCurrent(token)) {
            staleDropped++
            return false
        }
        return true
    }
}
