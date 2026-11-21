package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Session identity for asynchronous stream callbacks (Phase E).
 *
 * RootEncoder connection callbacks and the MediaProjection callback are
 * posted asynchronously and cannot be cancelled by this app: when a session
 * ends, posts from its stream may already be queued on the main thread, and
 * releasing a stream triggers a further asynchronous library onDisconnect.
 * Each session therefore captures the token returned by [beginSession] and
 * every callback validates it with [isCurrent] before touching any session
 * state. A token from a previous session — or from before [invalidate] — is
 * rejected, so a stale post can never stop, publish status for, schedule a
 * retry on, or fake LIVE progress on a newer (or destroyed) session.
 *
 * Ownership rule: the session boundary (startBroadcast / onDestroy)
 * advances the generation BEFORE releasing previous resources, because the
 * release itself produces the stale callback. Callbacks that already carry
 * their own identity (the fps listener's stream reference) or are posted on
 * this app's own handler and removed at every boundary (watchdog, startup,
 * heartbeat, retry timer) need no gate.
 *
 * All mutations run on the main thread; the generation is volatile so a
 * callback observing it from another thread still sees a current value.
 * Pure Kotlin, no Android dependencies.
 */
class StreamSessionGate {
    @Volatile
    private var generation = 0L

    /**
     * Starts a new session, invalidating every previously issued token.
     * Returns the token the new session's callbacks must capture.
     */
    fun beginSession(): Long {
        generation += 1
        return generation
    }

    /** Terminal invalidation (service destruction): no outstanding token stays valid. */
    fun invalidate() {
        generation += 1
    }

    /** True only for a token captured from the currently active session. */
    fun isCurrent(token: Long): Boolean = token == generation
}
