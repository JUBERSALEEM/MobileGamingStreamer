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

// "reconnect" covers the socket-recovery lines ("Reconnecting…",
// "Reconnected …"). Deliberately NOT "retry": the terminal failure line ends
// with "…and retry." and must keep classifying as ERROR, not STARTING.
private val STARTING_KEYWORDS = listOf(
    "preparing",
    "encoder ready",
    "connecting",
    "rtmp connected",
    "waiting for",
    "stream key accepted",
    "reconnect",
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
 * Authoritative service status lines (Phase G1). The broadcast service
 * publishes exactly these strings — never a copied literal — so the
 * classifier above and its tests always judge the real user-facing wording.
 * Dynamic per-tick lines (LIVE bitrate/fps, target bitrate) are not part of
 * this contract: they classify through the live flag, not their text. The
 * capture-permission line stays owned by
 * [com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.PROJECTION_ENDED_MESSAGE],
 * which the service references directly instead of duplicating.
 *
 * Pure Kotlin with no Android dependencies.
 */
object BroadcastStatusMessages {
    const val STARTUP_TIMEOUT_STOP =
        "No outgoing video bitrate after 25 seconds. Encoder or RTMP startup failed."
    const val STALL_STOP =
        "Stream output stalled for 20 seconds. Check your network, then tap GO LIVE and approve a fresh screen-capture session."
    const val NO_ACTIVE_STREAM = "No active stream to adjust"
    const val INVALID_SETUP = "Invalid stream setup"
    const val PREPARING_ENCODER = "Preparing video encoder…"
    const val CONNECTING = "Connecting to streaming server…"
    const val RECONNECTED_WAITING = "Reconnected • Waiting for video frames"
    const val RTMP_CONNECTED_WAITING = "RTMP connected • Waiting for video frames"
    const val DISCONNECTED = "Broadcast disconnected. Tap GO LIVE to start a fresh capture session."
    const val AUTH_FAILED = "Stream authentication failed. Check the stream key."
    const val KEY_ACCEPTED = "Stream key accepted"
    const val CONNECTED_WAITING = "Connected • Waiting for outgoing video"
    const val STOPPED = "Broadcast stopped"
    const val SERVICE_STOPPED = "Broadcast service stopped"
    const val SCENE_SWITCHED = "Scene switched"
    const val SCENE_SWITCH_FAILED = "Scene switch failed • Keeping current video"
    const val UNABLE_TO_START_PREFIX = "Unable to start: "

    /** Transitional encoder-ready line with the active profile label. */
    fun encoderReadyMessage(profileLabel: String): String =
        "Encoder ready • $profileLabel • Connecting…"

    /** Loud single-shot notice when the scene path fails over to the default source. */
    fun sceneFallbackMessage(reason: String): String =
        "Scene source failed ($reason). Using ScreenSource fallback."
}

/**
 * Displayed product version. This must match `versionName` in
 * `app/build.gradle.kts`; `scripts/verify_release_readiness.py` enforces it
 * so the in-app build badge and legal pages can never drift from the release.
 */
const val MgsVersionName = "0.5.23"

/**
 * One concise TalkBack announcement per broadcast-state transition. The
 * activity calls this only when [classifyBroadcastStatus] changes, so the
 * two-second bitrate ticks never spam the accessibility stream.
 */
fun broadcastAnnouncement(kind: BroadcastStatusKind, message: String): String =
    when (kind) {
        BroadcastStatusKind.LIVE -> "You are live"
        BroadcastStatusKind.STARTING -> "Connecting to stream"
        BroadcastStatusKind.ERROR -> "Broadcast error. $message"
        BroadcastStatusKind.STANDBY -> "Broadcast stopped"
    }

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
