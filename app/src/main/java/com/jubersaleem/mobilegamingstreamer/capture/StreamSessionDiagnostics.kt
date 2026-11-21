package com.jubersaleem.mobilegamingstreamer.capture

/**
 * One session-scoped diagnostic incident: a stable [id] plus a sanitized
 * human-readable [detail], stamped on the session monotonic clock ([atMs]).
 * Only meaningful incidents are recorded (failures, retries, recoveries,
 * health transitions, terminal reasons) — never routine per-tick samples.
 */
data class DiagnosticEvent(
    val id: String,
    val detail: String,
    val atMs: Long
)

/** Stable diagnostic event ids, so logs and bug reports stay comparable across versions. */
object DiagnosticEventId {
    const val SESSION_STARTED = "session_started"
    const val STREAM_CONFIRMED = "stream_confirmed"
    const val CONNECTION_FAILED = "connection_failed"
    const val RETRY_SCHEDULED = "retry_scheduled"
    const val RETRY_FIRED = "retry_fired"
    const val RECOVERY_SUCCEEDED = "recovery_succeeded"
    const val RECOVERY_EXPIRED = "recovery_expired"
    const val HEALTH_TRANSITION = "health_transition"
    const val AUTH_FAILED = "auth_failed"
    const val STREAM_STOPPED = "stream_stopped"
    const val SERVICE_DESTROYED = "service_destroyed"
}

private val RtmpUrlPattern = Regex("(?i)rtmps?://\\S+")
private val SecretAssignmentPattern =
    Regex("(?i)\\b([\\w-]*key|password|passwd|secret|token)\\s*[\"']?\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|\\S+)")

/**
 * Redact credentials and connection secrets from diagnostic text. RTMP(S)
 * URLs (whose path carries the stream key) collapse to a placeholder, as do
 * `key=`/`password=`/`token=`-style assignments, including `stream_key` /
 * `streamKey` spellings and quoted JSON-style values (which may contain
 * spaces). Applied to every recorded detail at the
 * [StreamSessionDiagnostics.record] boundary, so no call site can leak by
 * omission. Pure function with no Android dependencies.
 */
fun sanitizeDiagnosticText(text: String): String =
    RtmpUrlPattern.replace(text, "<url-redacted>")
        .let { SecretAssignmentPattern.replace(it, "$1=<redacted>") }

/**
 * Run one diagnostic action (recording, report building) so an ordinary
 * failure can never propagate into streaming control flow. Only
 * [Exception] is contained; fatal [Error]s are never caught. The service
 * routes every diagnostic call through this guard; recording sites that must
 * precede a terminal report keep their order and rely on this isolation
 * instead of reordering. Pure inline helper with no Android dependencies.
 */
inline fun guardDiagnosticAction(block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
        // Diagnostics must never break streaming.
    }
}

/**
 * Session-aware diagnostic event log with a terminal report builder
 * (Phase F).
 *
 * Records meaningful streaming incidents for exactly one session: lifecycle
 * events, connection failures, scheduled/fired retries, recovery outcomes,
 * health transitions and the terminal reason. The log is strictly
 * observational — recording never gates, delays or alters any streaming
 * decision — and strictly session-scoped: [record] accepts only the token
 * issued by [startSession], so a stale post from a previous session can
 * never contaminate the active session's diagnostics (mirroring the Phase E
 * session gate). History is bounded to [MAX_EVENTS]; the oldest incidents
 * evict first.
 *
 * [terminalReport] renders the concise session summary from observed facts
 * only: the recorded events plus authoritative snapshots the caller passes
 * (recovery phase, health, attempts used, rejected stale-post count). No
 * metric is inferred or invented.
 *
 * Pure Kotlin with no Android dependencies; covered by JVM unit tests with
 * fixed clocks. Main-thread confined, like the service paths that feed it.
 */
class StreamSessionDiagnostics {
    companion object {
        /**
         * Ring capacity. Only incidents are recorded (not ticks), so this
         * comfortably holds several sessions' worth of failures, retries and
         * transitions while bounding memory.
         */
        const val MAX_EVENTS = 64

        /**
         * Longest stored detail, in characters. Ordinary error messages are
         * far shorter; anything longer truncates with a marker so one
         * pathological callback cannot bloat the log or the terminal report.
         */
        const val MAX_DETAIL_LENGTH = 512
    }

    /** True once a session opened; recording before that is dropped. */
    var hasSession = false
        private set
    private var sessionToken = -1L
    private var sessionStartMs = 0L
    private val events = ArrayDeque<DiagnosticEvent>()

    /** Open a fresh session bound to [token]; drops all previous events. */
    fun startSession(token: Long, nowMs: Long) {
        hasSession = true
        sessionToken = token
        sessionStartMs = nowMs
        events.clear()
    }

    /**
     * Record one incident when [token] matches the current session.
     * Returns false (recording nothing) before any session opened or for a
     * stale token. [detail] is always sanitized and length-bounded before
     * storage.
     */
    fun record(token: Long, id: String, detail: String, nowMs: Long): Boolean {
        if (!hasSession || token != sessionToken) return false
        events.addLast(DiagnosticEvent(id, boundDetailLength(sanitizeDiagnosticText(detail)), nowMs))
        while (events.size > MAX_EVENTS) events.removeFirst()
        return true
    }

    /** Oldest-first snapshot of the recorded incidents. */
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    /**
     * Concise terminal summary: session identity, duration, final
     * authoritative state, retry usage, stale-post rejections, the health
     * transition trail and the incident list with session-relative offsets.
     * Every line restates an observed fact; nothing is inferred.
     */
    fun terminalReport(
        reason: String,
        endMs: Long,
        phase: StreamPhase,
        health: StreamHealth,
        attemptsUsed: Int,
        staleDropped: Int
    ): String {
        val durationSec = ((endMs - sessionStartMs).coerceAtLeast(0L) / 1000L)
        val transitions = events
            .filter { it.id == DiagnosticEventId.HEALTH_TRANSITION }
            .joinToString(separator = " > ") { it.detail }
            .ifEmpty { "none observed" }
        val lines = StringBuilder()
        lines.appendLine("Session $sessionToken • ${durationSec}s • $phase/$health")
        lines.appendLine("Reason: ${boundDetailLength(sanitizeDiagnosticText(reason))}")
        lines.appendLine("Attempts used: $attemptsUsed • stale posts rejected: $staleDropped")
        lines.appendLine("Health: $transitions")
        if (events.isEmpty()) {
            lines.append("Events: none recorded")
        } else {
            lines.appendLine("Events:")
            events.forEachIndexed { index, event ->
                val offsetSec = ((event.atMs - sessionStartMs).coerceAtLeast(0L) / 1000L)
                val last = if (index == events.size - 1) "" else "\n"
                lines.append("  +${offsetSec}s ${event.id} ${event.detail}$last")
            }
        }
        return lines.toString()
    }

    private fun boundDetailLength(detail: String): String =
        if (detail.length > MAX_DETAIL_LENGTH) {
            detail.take(MAX_DETAIL_LENGTH) + "…[truncated]"
        } else {
            detail
        }
}
