package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Observational stream-health classification.
 *
 * Produced by [StreamHealthMonitor] from transmission counters and callback
 * timestamps. Health never drives the streaming lifecycle: it cannot stop,
 * restart or recover a session — Phase B owns recovery and the watchdogs own
 * termination. A session that is stopping or stopped is never reported with
 * a live health state.
 *
 * Pure Kotlin with no Android dependencies.
 */
enum class StreamHealth {
    /** Insufficient reliable samples (startup, grace windows, missing metrics, halted). */
    UNKNOWN,

    /** Transmission is progressing with no sustained concerning metrics. */
    HEALTHY,

    /** Sustained pressure: starved bitrate, congestion, drops or queue backlog. */
    DEGRADED,

    /** Reliable evidence that meaningful transmission progress has stopped. */
    STALLED,

    /** The Phase B recovery driver reports an active reconnect episode. */
    RECOVERING
}

/**
 * One health sample: a single tick's worth of pipeline observations.
 *
 * All counters are raw cumulative library values; the monitor converts them
 * to reset-aware deltas. Timestamps are monotonic milliseconds on the same
 * clock ([SystemClock.elapsedRealtime] in production, fake clocks in tests).
 * A zero timestamp means "never observed in this session".
 *
 * @param bitrateBps last smoothed outgoing bits/sec (0 when unknown/none).
 * @param sentVideoTotal cumulative transmitted video frames (per connection).
 * @param sentAudioTotal cumulative transmitted audio frames (per connection).
 * @param droppedVideoTotal cumulative queue-full video drops (per connection).
 * @param droppedAudioTotal cumulative queue-full audio drops (per connection).
 * @param queueItems frames currently queued for transmission (0..capacity).
 * @param queueBytesOut bytes currently queued for transmission.
 * @param congested library queue-congestion flag for this tick.
 * @param lastPositiveBitrateMs last time bitrate was above zero.
 * @param lastEncoderFpsMs last time the encoder-output fps callback fired > 0.
 * @param recovering true while the recovery driver reports RECONNECTING.
 */
data class StreamHealthSample(
    val nowMs: Long,
    val bitrateBps: Long = 0L,
    val sentVideoTotal: Long = 0L,
    val sentAudioTotal: Long = 0L,
    val droppedVideoTotal: Long = 0L,
    val droppedAudioTotal: Long = 0L,
    val queueItems: Int = 0,
    val queueBytesOut: Long = 0L,
    val congested: Boolean = false,
    val lastPositiveBitrateMs: Long = 0L,
    val lastEncoderFpsMs: Long = 0L,
    val recovering: Boolean = false
)

/**
 * Documented health-classification thresholds.
 *
 * Every value exists for a stated reason; none claims to guarantee detection.
 * Windows are time-based (robust to delayed ticks); confirmations are
 * consecutive-tick based (robust to single-sample noise).
 */
object StreamHealthThresholds {
    /**
     * Ticks with progress required before HEALTHY. One sample is never
     * enough: the first tick after startup has no baseline for deltas.
     */
    const val HEALTHY_ENTER_SAMPLES = 2

    /**
     * Consecutive concerning ticks required before DEGRADED. A single hot
     * tick (one congested sample, one drop burst) is absorbed.
     */
    const val DEGRADED_ENTER_TICKS = 2

    /**
     * Consecutive clean ticks required to leave DEGRADED for HEALTHY.
     * Together with [DEGRADED_ENTER_TICKS] this hysteresis prevents flapping
     * when metrics hover around a boundary.
     */
    const val HEALTHY_EXIT_TICKS = 2

    /**
     * Silence window after the last transmission progress before STALLED.
     * Matches the product's existing stall-watchdog definition (20 seconds
     * without positive outgoing bitrate), so health agrees with the
     * termination rule it observes but never triggers.
     */
    const val STALL_NO_PROGRESS_MS = 20_000L

    /**
     * Consecutive progress ticks required to leave STALLED. Progress means
     * positive bitrate or advancing sent counters on the tick.
     */
    const val STALL_EXIT_TICKS = 2

    /**
     * Sustained outgoing bitrate below this floor means starved transmission.
     * A healthy session always carries 128 kbps AAC audio plus video; even a
     * static screen exceeds this floor. Content-dependent dips above it stay
     * HEALTHY when no pressure signal is present.
     */
    const val LOW_BITRATE_BPS = 100_000L

    /**
     * Queued backlog above this many bytes means multi-second transmit
     * latency (about 2 seconds of an 8 Mbps stream), independent of the
     * item-count congestion flag, which large keyframes can understate.
     */
    const val QUEUE_BYTES_PRESSURE = 2_000_000L

    /**
     * Session age below which DEGRADED/STALLED are suppressed (UNKNOWN
     * instead). Covers encoder preparation, handshake and first frames while
     * staying shorter than the 25-second startup bound, so a genuinely dead
     * startup still surfaces promptly.
     */
    const val STARTUP_GRACE_MS = 15_000L

    /**
     * Window after a reconnect episode ends during which STALLED is capped to
     * DEGRADED. The socket just came back; its counters restarted and its
     * bitrate smoother is cold, so silence here must not read as a new stall.
     */
    const val RECONNECT_GRACE_MS = 10_000L
}
