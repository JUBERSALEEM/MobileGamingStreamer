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
 * - Transmission progress rests on bytes the socket actually accepted: the
 *   library increments its sent-frame counters before the socket write but
 *   its byte counter only after a packet write returns, so positive byte
 *   deltas are the primary evidence and newly arrived positive-bitrate
 *   callbacks the supporting evidence. Frame-counter movement alone — frames
 *   merely consumed from the queue — never establishes transmission.
 * - Library counters reset on every socket reconnect, so all deltas are
 *   reset-aware (a backwards counter re-baselines; this also covers the
 *   theoretical Long rollover uniformly). A reset to zero with no new bytes
 *   is silence, not progress.
 * - Samples are timestamp-validated: anything older than the session start,
 *   duplicated or out of order is rejected without touching counts,
 *   baselines, totals, progress timestamps or state.
 * - Windows are time-based (robust to delayed ticks); confirmations are
 *   consecutive-tick based (robust to single-sample noise); hysteresis
 *   separates degraded entry from healthy exit.
 * - Encoder progress (fps callbacks), sender progress (frames dequeued) and
 *   transmission progress (bytes written) are tracked independently so a
 *   stall with a live encoder stays distinguishable whenever the metrics
 *   allow it.
 * - Missing metrics are conservative: a session that never showed progress
 *   reports UNKNOWN, never STALLED.
 * - Halting freezes the terminal verdict for the final status event instead
 *   of clearing it; sampling stays dead until a new session resets.
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

    /** True when the sender recently dequeued frames toward the socket. */
    var senderProgressing: Boolean = false
        private set

    /** True when the socket recently accepted bytes (primary) or bitrate evidence is fresh. */
    var transmissionProgressing: Boolean = false
        private set

    /** Accepted in-session samples; rejected stale/duplicate/out-of-order samples never count. */
    var samplesAccepted: Int = 0
        private set

    /** Reset-aware session totals of queue-full drops (diagnostic aid). */
    var sessionDroppedVideo: Long = 0L
        private set
    var sessionDroppedAudio: Long = 0L
        private set

    private var halted = false
    private var sessionStartMs = 0L
    private var lastSampleMs = 0L
    private var lastBytesSend = 0L
    private var lastSentVideo = 0L
    private var lastSentAudio = 0L
    private var lastDroppedVideo = 0L
    private var lastDroppedAudio = 0L
    private var lastProgressMs = 0L
    private var lastSenderProgressMs = 0L
    private var lastEncoderProgressMs = 0L
    private var prevPositiveBitrateMs = 0L
    private var consecutiveBad = 0
    private var consecutiveGood = 0
    private var consecutiveProgress = 0
    private var wasRecovering = false
    private var recoveringEndMs = 0L

    /** Reset for a fresh session; clears every baseline so stale samples cannot leak across sessions. */
    fun startSession(nowMs: Long) {
        halted = false
        sessionStartMs = nowMs
        lastSampleMs = 0L
        samplesAccepted = 0
        lastBytesSend = 0L
        lastSentVideo = 0L
        lastSentAudio = 0L
        lastDroppedVideo = 0L
        lastDroppedAudio = 0L
        lastProgressMs = 0L
        lastSenderProgressMs = 0L
        lastEncoderProgressMs = 0L
        prevPositiveBitrateMs = 0L
        consecutiveBad = 0
        consecutiveGood = 0
        consecutiveProgress = 0
        wasRecovering = false
        recoveringEndMs = 0L
        sessionDroppedVideo = 0L
        sessionDroppedAudio = 0L
        encoderProgressing = false
        senderProgressing = false
        transmissionProgressing = false
        state = StreamHealth.UNKNOWN
    }

    /**
     * Halt monitoring for teardown. Idempotent; samples after this are
     * ignored. The terminal verdict is retained (not cleared to UNKNOWN) so
     * the final status event published during teardown keeps it; only
     * [startSession] resets for a fresh session.
     */
    fun onStopped() {
        halted = true
    }

    /**
     * Classify one sampling tick. Returns the (possibly unchanged) state.
     * Rejected samples (older than the session, duplicated or out of order)
     * return the current state without touching any counter, baseline, total
     * or timestamp. Baselines advance on every accepted tick, including
     * recovering ticks, so a sender restart mid-episode never produces a
     * false delta afterwards.
     */
    fun onSample(sample: StreamHealthSample): StreamHealth {
        // Halted: echo the frozen terminal verdict; nothing is counted,
        // baselined or transitioned.
        if (halted) return state
        val now = sample.nowMs
        if (now < sessionStartMs) return state
        if (samplesAccepted > 0 && now <= lastSampleMs) return state
        samplesAccepted++
        lastSampleMs = now

        val byteDelta = resetAwareDelta(sample.bytesSendTotal, lastBytesSend)
        val sentVideoDelta = resetAwareDelta(sample.sentVideoTotal, lastSentVideo)
        val sentAudioDelta = resetAwareDelta(sample.sentAudioTotal, lastSentAudio)
        val dropVideoDelta = resetAwareDelta(sample.droppedVideoTotal, lastDroppedVideo)
        val dropAudioDelta = resetAwareDelta(sample.droppedAudioTotal, lastDroppedAudio)
        lastBytesSend = sample.bytesSendTotal.coerceAtLeast(0L)
        lastSentVideo = sample.sentVideoTotal.coerceAtLeast(0L)
        lastSentAudio = sample.sentAudioTotal.coerceAtLeast(0L)
        lastDroppedVideo = sample.droppedVideoTotal.coerceAtLeast(0L)
        lastDroppedAudio = sample.droppedAudioTotal.coerceAtLeast(0L)
        sessionDroppedVideo += dropVideoDelta
        sessionDroppedAudio += dropAudioDelta

        // Supporting evidence must be new since the previous sample: a frozen
        // timestamp (bitrate callbacks paused while the socket is down) is
        // history, not progress.
        val newPositiveBitrate = sample.lastPositiveBitrateMs > prevPositiveBitrateMs
        prevPositiveBitrateMs = max(prevPositiveBitrateMs, sample.lastPositiveBitrateMs)
        val progressOnTick = byteDelta > 0L || newPositiveBitrate
        if (byteDelta > 0L) lastProgressMs = max(lastProgressMs, now)
        lastProgressMs = max(lastProgressMs, sample.lastPositiveBitrateMs)
        if (sentVideoDelta > 0L || sentAudioDelta > 0L) {
            lastSenderProgressMs = max(lastSenderProgressMs, now)
        }
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
        senderProgressing = lastSenderProgressMs > 0L &&
            now - lastSenderProgressMs < StreamHealthThresholds.STALL_NO_PROGRESS_MS
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
        if (consecutiveProgress >= StreamHealthThresholds.HEALTHY_ENTER_PROGRESS_TICKS) {
            state = StreamHealth.HEALTHY
        } else if (state != StreamHealth.HEALTHY) {
            // Below the confirmation run and not previously healthy: no
            // verdict yet. A previously healthy session holds HEALTHY through
            // transient non-progress; the stall window and the degraded
            // confirmation above own the exits.
            state = StreamHealth.UNKNOWN
        }
        return state
    }

    /**
     * Delta between consecutive cumulative counters. A backwards counter
     * means the library restarted its per-connection counters (every socket
     * reconnect does this); the new total re-baselines and counts as
     * tick-local activity rather than producing a negative or a false stall.
     * A reset that lands on zero yields a zero delta: silence, not progress.
     */
    private fun resetAwareDelta(newTotal: Long, oldTotal: Long): Long {
        val fresh = newTotal.coerceAtLeast(0L)
        val previous = oldTotal.coerceAtLeast(0L)
        return if (fresh >= previous) fresh - previous else fresh
    }
}
