package com.jubersaleem.mobilegamingstreamer

/**
 * Single source of truth for broadcast-status interpretation.
 *
 * The activity, the floating overlay and the Studio page previously matched
 * overlapping keyword lists. Unifying them here keeps every surface truthful
 * and consistent, and keeps the behaviour covered by JVM unit tests.
 *
 * Pure Kotlin with no Android dependencies.
 */
enum class BroadcastStatusKind { STANDBY, STARTING, LIVE, ERROR }

private val STARTING_KEYWORDS = listOf(
    "preparing",
    "encoder ready",
    "connecting",
    "rtmp connected",
    "waiting for",
    "stream key accepted",
    "60 fps unavailable"
)

private val ERROR_KEYWORDS = listOf(
    "failed",
    "error",
    "invalid",
    "authentication",
    "unable to",
    "stall"
)

/** True while the service reports a transitional pre-live phase. */
fun isStartingBroadcastMessage(message: String): Boolean =
    STARTING_KEYWORDS.any { message.contains(it, ignoreCase = true) }

/**
 * Classifies a status line. A confirmed live session always wins; a starting
 * phase wins over stale text; otherwise error keywords surface failures
 * instead of leaving the UI on a misleading standby label.
 */
fun classifyBroadcastStatus(message: String, active: Boolean, starting: Boolean): BroadcastStatusKind =
    when {
        active -> BroadcastStatusKind.LIVE
        starting -> BroadcastStatusKind.STARTING
        ERROR_KEYWORDS.any { message.contains(it, ignoreCase = true) } -> BroadcastStatusKind.ERROR
        else -> BroadcastStatusKind.STANDBY
    }

/**
 * Microphone-source labels for the Audio diagnostics card.
 *
 * Values mirror android.media.MediaRecorder.AudioSource and are verified
 * against the framework constants by unit tests, so this file stays free of
 * Android imports and safe to exercise on the JVM.
 */
object AudioRouteLabels {
    const val SOURCE_DEFAULT = 0
    const val SOURCE_MIC = 1
    const val SOURCE_VOICE_RECOGNITION = 6
    const val SOURCE_NONE = -1

    fun micSourceLabel(source: Int): String = when (source) {
        SOURCE_DEFAULT -> "DEFAULT"
        SOURCE_MIC -> "MIC"
        SOURCE_VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "—"
    }
}
