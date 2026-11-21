package com.jubersaleem.mobilegamingstreamer.scene

import java.time.Instant
import java.time.ZoneId

/**
 * H9 pure time contract shared by the GL renderer and the editor
 * preview: widget formatting and tick scheduling. Both sides call
 * these functions, so a clock reads identically on stream and in
 * preview (same minute) and every widget box measures identically
 * (worst-case strings keep all slots a stable fixed size, so live
 * re-render is a same-size texture re-upload, never a relayout).
 *
 * `java.time` is minSdk-safe (API 26+, minSdk 29 — no desugaring)
 * and JVM-safe for unit tests.
 */
internal object SceneClockWidgets {
    /** Fixed-box measure string for CLOCK (widest plausible HH:MM). */
    const val WALL_CLOCK_WORST_CASE = "88:88"

    /** Fixed-box measure string for TIMER (widest plausible HH:MM:SS). */
    const val TIMER_WORST_CASE = "88:88:88"

    const val TIMER_TICK_PERIOD_MS = 1_000L
    const val CLOCK_TICK_PERIOD_MS = 60_000L
    private const val MAX_ELAPSED_SECONDS = 99 * 3600 + 59 * 60 + 59L // 99:59:59.

    /**
     * Wall-clock HH:MM (24h, ASCII digits) for [epochMs] in [zone].
     * Manual zero-pad: locale-independent (never localized digits).
     */
    fun formatWallClock(epochMs: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone)
        return pad2(time.hour) + ":" + pad2(time.minute)
    }

    /**
     * Elapsed HH:MM:SS between monotonic timestamps. Negative ranges
     * (unlatched starts) clamp to zero; hours cap at 99 (a stream
     * past 100h keeps reading 99:59:59 — documented, not wrapped).
     */
    fun formatElapsed(startElapsedMs: Long, nowElapsedMs: Long): String {
        val totalSeconds = ((nowElapsedMs - startElapsedMs) / 1000)
            .coerceAtLeast(0L).coerceAtMost(MAX_ELAPSED_SECONDS)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return pad2(hours.toInt()) + ":" + pad2(minutes.toInt()) + ":" + pad2(seconds.toInt())
    }

    /**
     * Millis until the next [periodMs] boundary after [nowMs], always
     * in `(0, periodMs]`: an exact-boundary tick waits a full period
     * (a zero delay could busy-loop the ticker). Null on invalid
     * period.
     */
    fun tickDelayMs(nowMs: Long, periodMs: Long): Long? {
        if (periodMs <= 0) return null
        val phase = ((nowMs % periodMs) + periodMs) % periodMs
        return periodMs - phase
    }

    private fun pad2(value: Int): String =
        if (value < 10) "0$value" else value.toString()
}
