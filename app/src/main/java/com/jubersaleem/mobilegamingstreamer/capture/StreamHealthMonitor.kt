package com.jubersaleem.mobilegamingstreamer.capture

import kotlin.math.max

/**
 * Observational health classifier for the live streaming pipeline.
 *
 * Consumes one [StreamHealthSample] per sampling tick (the service's existing
 * 5-second watchdog tick) and reports a [StreamHealth] state. The monitor is
 * strictly observational: it cannot stop, restart, recover or otherwise
 * affect the session. Lifecycle authority stays with the service, the
 * recovery driver and the termination watchdogs.
 *
 * Design notes:
 * - Library counters reset on every socket reconnect, so all deltas are
 *   reset-aware (a backwards counter re-baselines; this also covers the
 *   theoretical Long rollover uniformly).
 * - Windows are time-based (robust to delayed ticks); confirmations are
 *   consecutive-tick based (robust to single-sample noise); hysteresis
 *   separates degraded entry from healthy exit.
 * - Encoder progress (fps callbacks) and transmission progress (bitrate and
 *   sent counters) are tracked independently so a stall with a live encoder
 *   is distinguishable whenever the metrics allow it.
 * - Missing metrics are conservative: a session that never showed progress
 *   reports UNKNOWN, never STALLED.
 *
 * Main-thread confined, like the sampling tick that feeds it; no locking is
 * required. Holds only scalar state (no per-tick allocations beyond the
 * incoming sample). Pure Kotlin with no Android dependencies.
 */
class StreamHealthMonitor {
    var state: StreamHealth = StreamHealth.UNKNOWN
        private set

    /** True when the encoder recently produced output frames. */
    var encoderProgressing: Boolean = false
        private set

    /** True when transmission recently progressed (bitrate or sent counters). */
    var transmissionProgressing: Boolean = false
        private set

    /** Reset-aware session totals of queue-full drops (diagnostic aid). */
    var sessionDroppedVideo: Long = 0L
        private set
    var sessionDroppedAudio: Long = 0L
        private set

    private var halted = false
    private var sessionStartMs = 0L
    private var samplesSeen = 0
    private var lastSentVideo = 0L
    private var lastSentAudio = 0L
    private var lastDroppedVideo = 0L
    private var lastDroppedAudio = 0L
    private var lastProgressMs = 0L
    private var lastEncoderProgressMs = 0L
    private var consecutiveBad = 0
    private var consecutiveGood = 0
    private var consecutiveProgress = 0
    private var wasRecovering = false
    private var recoveringEndMs = 0L

    /** Reset for a fresh session; clears every baseline so stale samples cannot leak across sessions. */
    fun startSession(nowMs: Long) {
        halted = false
        sessionStartMs = nowMs
        samplesSeen = 0
        lastSentVideo = 0L
        lastSentAudio = 0L
        lastDroppedVideo = 0L
        lastDroppedAudio = 0L
        lastProgressMs = 0L
        lastEncoderProgressMs = 0L
        consecutiveBad = 0
        consecutiveGood = 0
        consecutiveProgress = 0
        wasRecovering = false
        recoveringEndMs = 0L
        sessionDroppedVideo = 0L
        sessionDroppedAudio = 0L
        encoderProgressing = false
        transmissionProgressing = false
        state = StreamHealth.UNKNOWN
    }

    /** Halt monitoring for teardown. Idempotent; samples after this are ignored. */
    fun onStopped() {
        halted = true
        state = StreamHealth.UNKNOWN
    }

    /**
     * Classify one sampling tick. Returns the (possibly unchanged) state.
     * Baselines advance on every tick, including recovering ticks, so a
     * sender restart mid-episode never produces a false delta afterwards.
     */
    fun onSample(sample: StreamHealthSample): StreamHealth {
        if (halted) return StreamHealth.UNKNOWN
        val now = sample.nowMs
        samplesSeen++

        val sentVideoDelta = resetAwareDelta(sample.sentVideoTotal, lastSentVideo)
        val sentAudioDelta = resetAwareDelta(sample.sentAudioTotal, lastSentAudio)
        val dropVideoDelta = resetAwareDelta(sample.droppedVideoTotal, lastDroppedVideo)
        val dropAudioDelta = resetAwareDelta(sample.droppedAudioTotal, lastDroppedAudio)
        lastSentVideo = sample.sentVideoTotal.coerceAtLeast(0L)
        lastSentAudio = sample.sentAudioTotal.coerceAtLeast(0L)
        lastDroppedVideo = sample.droppedVideoTotal.coerceAtLeast(0L)
        lastDroppedAudio = sample.droppedAudioTotal.coerceAtLeast(0L)
        sessionDroppedVideo += dropVideoDelta
        sessionDroppedAudio += dropAudioDelta

        val progressOnTick = sample.bitrateBps > 0L || sentVideoDelta > 0L || sentAudioDelta > 0L
        if (progressOnTick) lastProgressMs = max(lastProgressMs, now)
        lastProgressMs = max(lastProgressMs, sample.lastPositiveBitrateMs)
        lastEncoderProgressMs = max(lastEncoderProgressMs, sample.lastEncoderFpsMs)

        // Pressure evidence for this tick. Starved bitrate needs a positive
        // reading: silence is judged by the no-progress window, not here.
        val starved = sample.bitrateBps > 0L && sample.bitrateBps < StreamHealthThresholds.LOW_BITRATE_BPS
        val pressured = sample.congested || dropVideoDelta > 0L || dropAudioDelta > 0L ||
            sample.queueBytesOut >= StreamHealthThresholds.QUEUE_BYTES_PRESSURE
        val badTick = starved || pressured
        consecutiveBad = if (badTick) consecutiveBad + 1 else 0
        consecutiveGood = if (!badTick && progressOnTick) consecutiveGood + 1 else 0
        consecutiveProgress = if (progressOnTick) consecutiveProgress + 1 else 0

        transmissionProgressing = lastProgressMs > 0L &&
            now - lastProgressMs < StreamHealthThresholds.STALL_NO_PROGRESS_MS
        encoderProgressing = lastEncoderProgressMs > 0L &&
            now - lastEncoderProgressMs < StreamHealthThresholds.STALL_NO_PROGRESS_MS

        if (sample.recovering) {
            wasRecovering = true
            state = StreamHealth.RECOVERING
            return state
        }
        if (wasRecovering) {
            wasRecovering = false
            recoveringEndMs = now
        }

        val inStartupGrace = now - sessionStartMs < StreamHealthThresholds.STARTUP_GRACE_MS
        val inReconnectGrace = recoveringEndMs > 0L &&
            now - recoveringEndMs < StreamHealthThresholds.RECONNECT_GRACE_MS
        val stalledByWindow = lastProgressMs > 0L &&
            now - lastProgressMs >= StreamHealthThresholds.STALL_NO_PROGRESS_MS

        // A confirmed stall only exits on consecutive progress; anything else
        // holds the verdict so a single stray tick cannot clear it.
        if (state == StreamHealth.STALLED && consecutiveProgress < StreamHealthThresholds.STALL_EXIT_TICKS) {
            return state
        }
        if (stalledByWindow && !inStartupGrace) {
            // Fresh out of an episode the socket is still warming (counters
            // restarted, bitrate smoother cold): cap the verdict at DEGRADED.
            state = if (inReconnectGrace) StreamHealth.DEGRADED else StreamHealth.STALLED
            return state
        }
        if (!inStartupGrace && consecutiveBad >= StreamHealthThresholds.DEGRADED_ENTER_TICKS) {
            state = StreamHealth.DEGRADED
            return state
        }
        if (state == StreamHealth.DEGRADED) {
            // Hysteresis: leaving DEGRADED needs sustained clean progress,
            // not merely the absence of one more bad tick.
            if (consecutiveGood >= StreamHealthThresholds.HEALTHY_EXIT_TICKS) {
                state = StreamHealth.HEALTHY
            }
            return state
        }
        val progressFresh = lastProgressMs > 0L &&
            now - lastProgressMs < StreamHealthThresholds.STALL_NO_PROGRESS_MS
        state = if (samplesSeen >= StreamHealthThresholds.HEALTHY_ENTER_SAMPLES && progressFresh) {
            StreamHealth.HEALTHY
        } else {
            StreamHealth.UNKNOWN
        }
        return state
    }

    /**
     * Delta between consecutive cumulative counters. A backwards counter
     * means the library restarted its per-connection counters (every socket
     * reconnect does this); the new total re-baselines and counts as
     * tick-local activity rather than producing a negative or a false stall.
     */
    private fun resetAwareDelta(newTotal: Long, oldTotal: Long): Long {
        val fresh = newTotal.coerceAtLeast(0L)
        val previous = oldTotal.coerceAtLeast(0L)
        return if (fresh >= previous) fresh - previous else fresh
    }
}
