package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Pure decision for the H2 SceneSource fallback: when the opt-in scene
 * source fails before streaming is confirmed, the service retries once
 * with the default ScreenSource instead of failing the session.
 *
 * The fallback fires only when ALL hold:
 * - the scene source was requested ([requestedSceneSource]),
 * - no fallback was already attempted for this session
 *   ([fallbackAlreadyAttempted], carried on the retry intent, so the retry
 *   itself can never retrigger — recursion depth is at most one),
 * - streaming was never confirmed ([streamingConfirmed] false — a
 *   mid-stream failure keeps the existing stall/recovery path and never
 *   rebuilds the session).
 *
 * The default path (scene never requested) can never trigger a fallback,
 * so production behavior is unreachable by this policy. Pure Kotlin, no
 * Android dependencies.
 */
object VideoSourceFallbackPolicy {
    fun shouldFallbackToScreenSource(
        requestedSceneSource: Boolean,
        fallbackAlreadyAttempted: Boolean,
        streamingConfirmed: Boolean
    ): Boolean =
        requestedSceneSource && !fallbackAlreadyAttempted && !streamingConfirmed
}
