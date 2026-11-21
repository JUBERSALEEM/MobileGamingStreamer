package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the observational health classifier. A fake clock
 * (explicit nowMs) and scripted counter sequences drive the real production
 * [StreamHealthMonitor]; no timing, threads or Android dependencies.
 *
 * Transmission progress rests on socket-accepted bytes (primary) and newly
 * arrived positive-bitrate evidence (supporting); sent-frame counters are
 * sender diagnostics only. The harness defaults simulate a flowing session;
 * each test overrides the channels it needs to isolate.
 */
class StreamHealthMonitorTest {
    private class Harness {
        val monitor = StreamHealthMonitor()
        var now = 0L
        private var bytes = 0L
        private var sentV = 0L
        private var sentA = 0L
        private var dropV = 0L
        private var dropA = 0L
        private var posBitrate = 0L
        private var posFps = 0L

        fun start() {
            now = 0L
            resetCounters()
            monitor.startSession(now)
        }

        /** Zero all scripted counters to mirror a fresh sender; the fake clock is set separately. */
        fun resetCounters() {
            bytes = 0L
            sentV = 0L
            sentA = 0L
            dropV = 0L
            dropA = 0L
            posBitrate = 0L
            posFps = 0L
        }

        fun tick(
            bitrate: Long = 8_000_000L,
            bytesSendStep: Long = 1_000_000L,
            bytesSendTotal: Long? = null,
            sentVideoStep: Long = 150L,
            sentAudioStep: Long = 200L,
            dropVideoStep: Long = 0L,
            dropAudioStep: Long = 0L,
            queueItems: Int = 5,
            queueBytes: Long = 200_000L,
            congested: Boolean = false,
            fpsStale: Boolean = false,
            recovering: Boolean = false,
            stepMs: Long = 5_000L
        ): StreamHealth {
            now += stepMs
            bytes = if (bytesSendTotal != null) bytesSendTotal else bytes + bytesSendStep
            sentV += sentVideoStep
            sentA += sentAudioStep
            dropV += dropVideoStep
            dropA += dropAudioStep
            if (bitrate > 0L) posBitrate = now
            if (!fpsStale) posFps = now
            return monitor.onSample(
                StreamHealthSample(
                    nowMs = now,
                    bitrateBps = bitrate,
                    bytesSendTotal = bytes,
                    sentVideoTotal = sentV,
                    sentAudioTotal = sentA,
                    droppedVideoTotal = dropV,
                    droppedAudioTotal = dropA,
                    queueItems = queueItems,
                    queueBytesOut = queueBytes,
                    congested = congested,
                    lastPositiveBitrateMs = posBitrate,
                    lastEncoderFpsMs = posFps,
                    recovering = recovering
                )
            )
        }

        fun outageTick(stepMs: Long = 5_000L): StreamHealth = tick(
            bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 0L, sentAudioStep = 0L,
            queueItems = 0, queueBytes = 0L, fpsStale = true, stepMs = stepMs
        )
    }

    @Test
    fun initialStateIsUnknown() {
        val monitor = StreamHealthMonitor()
        assertEquals(StreamHealth.UNKNOWN, monitor.state)
        assertEquals(0, monitor.samplesAccepted)
    }

    @Test
    fun insufficientSamplesRemainUnknown() {
        val h = Harness()
        h.start()
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(2, h.monitor.samplesAccepted)
    }

    @Test
    fun normalTransmissionBecomesHealthy() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertTrue(h.monitor.transmissionProgressing)
        assertTrue(h.monitor.senderProgressing)
        assertTrue(h.monitor.encoderProgressing)
        assertEquals(3, h.monitor.samplesAccepted)
    }

    @Test
    fun temporaryBitrateDropDoesNotDegrade() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.outageTick()
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        assertEquals(StreamHealth.HEALTHY, h.tick())
    }

    @Test
    fun sustainedLowBitrateBecomesDegraded() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(bitrate = 50_000L)
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        assertEquals(StreamHealth.DEGRADED, h.tick(bitrate = 50_000L))
        assertEquals(StreamHealth.DEGRADED, h.tick(bitrate = 50_000L))
    }

    @Test
    fun lowBitrateThresholdBoundaryIsExplicit() {
        // Strictly below 200_000 bps: starved, degrades after confirmation.
        var h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(bitrate = 199_999L)
        assertEquals(StreamHealth.DEGRADED, h.tick(bitrate = 199_999L))
        // Exactly at the floor: not starved, stays healthy.
        h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(4) { h.tick(bitrate = 200_000L) }
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        // Just above the floor: not starved, stays healthy.
        h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(4) { h.tick(bitrate = 200_001L) }
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
    }

    @Test
    fun staticScreenBitrateWithCleanPressureStaysHealthy() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        repeat(6) { h.tick(bitrate = 300_000L) }
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
    }

    @Test
    fun temporaryCongestionNeverStalls() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(congested = true, queueItems = 120)
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
    }

    @Test
    fun sustainedCongestionBecomesDegraded() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(congested = true, queueItems = 120)
        assertEquals(StreamHealth.DEGRADED, h.tick(congested = true, queueItems = 150))
    }

    @Test
    fun dropBurstIsAbsorbedButSustainedDropsDegrade() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(dropVideoStep = 50L)
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        assertEquals(50L, h.monitor.sessionDroppedVideo)
        assertEquals(StreamHealth.DEGRADED, h.tick(dropVideoStep = 12L))
        assertEquals(62L, h.monitor.sessionDroppedVideo)
    }

    @Test
    fun sustainedNoProgressBecomesStalled() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.outageTick()
        h.outageTick()
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        h.outageTick()
        h.outageTick()
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertFalse(h.monitor.transmissionProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertFalse(h.monitor.encoderProgressing)
    }

    @Test
    fun encoderProgressWithStalledTransmissionIsDistinguished() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(4) {
            h.tick(bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = false)
        }
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertTrue(h.monitor.encoderProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertFalse(h.monitor.transmissionProgressing)
    }

    @Test
    fun sentFramesWithoutByteProgressDoNotEstablishTransmission() {
        val h = Harness()
        h.start()
        // Frames advance every tick but no byte reaches the socket and no
        // positive bitrate ever arrives: sender-side movement only.
        repeat(3) {
            h.tick(bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 150L, sentAudioStep = 200L)
            assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        }
        assertTrue(h.monitor.senderProgressing)
        assertFalse(h.monitor.transmissionProgressing)
        assertEquals(3, h.monitor.samplesAccepted)
    }

    @Test
    fun positiveByteDeltasEstablishTransmissionProgress() {
        val h = Harness()
        h.start()
        // Bytes advance with no bitrate evidence at all: byte deltas alone
        // confirm a flowing session.
        h.tick(bitrate = 0L, bytesSendStep = 1_000_000L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true)
        h.tick(bitrate = 0L, bytesSendStep = 1_000_000L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true)
        assertEquals(
            StreamHealth.HEALTHY,
            h.tick(bitrate = 0L, bytesSendStep = 1_000_000L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true)
        )
        assertTrue(h.monitor.transmissionProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertFalse(h.monitor.encoderProgressing)
    }

    @Test
    fun blockedSocketCannotStayHealthyOnFrameCountersAlone() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        // The socket stops accepting bytes while the sender keeps dequeuing
        // frames: frame movement must not hold the session healthy.
        repeat(4) {
            h.tick(bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 150L, sentAudioStep = 200L)
        }
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertTrue(h.monitor.senderProgressing)
        assertFalse(h.monitor.transmissionProgressing)
    }

    @Test
    fun byteCounterResetDoesNotFabricateProgress() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        // Sender restart zeroes the byte counter with nothing written yet and
        // no new bitrate evidence: the reset tick is silence, so the stall
        // schedule still anchors at the last genuine progress (t=15s).
        assertEquals(
            StreamHealth.HEALTHY,
            h.tick(bitrate = 0L, bytesSendTotal = 0L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true)
        )
        assertTrue(h.monitor.transmissionProgressing)
        h.outageTick()
        h.outageTick()
        assertEquals(StreamHealth.STALLED, h.outageTick())
    }

    @Test
    fun missingMetricsNeverStall() {
        val h = Harness()
        h.start()
        repeat(8) { h.outageTick() }
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
    }

    @Test
    fun startupGracePreventsFalseAlarms() {
        val h = Harness()
        h.start()
        // Two consecutive alarming ticks inside the startup grace window:
        // past the grace this pressure would read DEGRADED, but early
        // samples must never raise a bad verdict.
        repeat(2) {
            h.tick(
                bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 0L, sentAudioStep = 0L,
                congested = true, queueItems = 200, dropVideoStep = 30L, fpsStale = true
            )
            assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        }
    }

    @Test
    fun reconnectGraceCapsStallAtDegraded() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(5) {
            assertEquals(
                StreamHealth.RECOVERING,
                h.tick(bitrate = 0L, bytesSendStep = 0L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true, recovering = true)
            )
        }
        assertEquals(StreamHealth.DEGRADED, h.outageTick())
        assertEquals(StreamHealth.DEGRADED, h.outageTick())
        assertEquals(StreamHealth.STALLED, h.outageTick())
    }

    @Test
    fun recoveringFollowsDriverFlag() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(StreamHealth.RECOVERING, h.tick(recovering = true))
        assertEquals(StreamHealth.RECOVERING, h.tick(recovering = true))
        assertEquals(StreamHealth.HEALTHY, h.tick(recovering = false))
    }

    @Test
    fun stoppedMonitorHaltsAndIgnoresSamples() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.monitor.onStopped()
        // The halt freezes the terminal verdict for the final status event
        // instead of clearing it; sampling stays dead.
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        assertEquals(3, h.monitor.samplesAccepted)
        h.monitor.onStopped()
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
    }

    @Test
    fun terminalVerdictSurvivesHaltAndDestruction() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(4) { h.outageTick() }
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        // stopBroadcast publishes the terminal event, then halts: the STALLED
        // verdict is retained for that event.
        h.monitor.onStopped()
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        // Stray samples after the halt are ignored: no resurrection, no
        // counting, no transitions; the frozen verdict echoes back.
        h.outageTick()
        assertEquals(StreamHealth.STALLED, h.tick())
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertEquals(7, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        // onDestroy halts again (idempotent cleanup): the verdict still
        // stands for the final destruction event instead of UNKNOWN.
        h.monitor.onStopped()
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        // A fresh session still starts clean at UNKNOWN.
        h.start()
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        assertEquals(0, h.monitor.samplesAccepted)
        assertEquals(StreamHealth.UNKNOWN, h.tick())
    }

    @Test
    fun newSessionResetsBaselines() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick(dropVideoStep = 9L)
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(9L, h.monitor.sessionDroppedVideo)
        h.start()
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        assertEquals(0, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        assertEquals(StreamHealth.UNKNOWN, h.tick())
    }

    @Test
    fun staleSamplesCannotAffectNewSession() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(3, h.monitor.samplesAccepted)
        // Fresh session; a leftover sample from the previous session arrives
        // late with alarming content and rich-looking evidence.
        h.monitor.startSession(200_000L)
        assertEquals(0, h.monitor.samplesAccepted)
        val stale = h.monitor.onSample(
            StreamHealthSample(
                nowMs = 5_000L,
                bitrateBps = 8_000_000L,
                bytesSendTotal = 50_000_000L,
                sentVideoTotal = 9_999_999L,
                sentAudioTotal = 9_999_999L,
                droppedVideoTotal = 100L,
                congested = true,
                queueBytesOut = 9_000_000L,
                lastPositiveBitrateMs = 190_000L,
                lastEncoderFpsMs = 190_000L,
                recovering = true
            )
        )
        // Nothing moves: not state, count, baselines, totals or timestamps.
        assertEquals(StreamHealth.UNKNOWN, stale)
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        assertEquals(0, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        assertEquals(0L, h.monitor.sessionDroppedAudio)
        assertFalse(h.monitor.transmissionProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertFalse(h.monitor.encoderProgressing)
        // Mirror a fresh sender (counters restart at zero) and prove the
        // rejected evidence landed nowhere: a no-progress tick first keeps
        // every progress flag false — the rejected bitrate/fps evidence
        // (t=190s) would otherwise read as fresh.
        h.resetCounters()
        h.now = 200_000L
        assertEquals(StreamHealth.UNKNOWN, h.outageTick())
        assertFalse(h.monitor.transmissionProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertFalse(h.monitor.encoderProgressing)
        assertEquals(1, h.monitor.samplesAccepted)
        // Then a normal confirmation run: three consecutive progress ticks to
        // HEALTHY, a lone bad tick still unconfirmed (the rejected congestion
        // primed no bad streak), and a real 100-drop tick accounting exactly
        // 100 — a poisoned drop baseline of 100 would have swallowed it.
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick(congested = true, queueItems = 120))
        assertEquals(StreamHealth.DEGRADED, h.tick(dropVideoStep = 100L))
        assertEquals(100L, h.monitor.sessionDroppedVideo)
        assertEquals(6, h.monitor.samplesAccepted)
    }

    @Test
    fun outOfOrderAndDuplicateSamplesAreRejected() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(3, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        // Out-of-order sample with alarming content and huge totals:
        // rejected outright, baselines untouched.
        val backwards = h.monitor.onSample(
            StreamHealthSample(
                nowMs = h.now - 5_000L,
                congested = true,
                droppedVideoTotal = 700L,
                bytesSendTotal = 999_999_999L,
                sentVideoTotal = 999_999_999L,
                sentAudioTotal = 999_999_999L,
                lastPositiveBitrateMs = h.now,
                lastEncoderFpsMs = h.now
            )
        )
        assertEquals(StreamHealth.HEALTHY, backwards)
        assertEquals(3, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        // Duplicate timestamp: rejected as well.
        val duplicate = h.monitor.onSample(
            StreamHealthSample(nowMs = h.now, congested = true, droppedVideoTotal = 700L, bytesSendTotal = 999_999_999L)
        )
        assertEquals(StreamHealth.HEALTHY, duplicate)
        assertEquals(3, h.monitor.samplesAccepted)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        // Frozen ticks afterwards stall on the original schedule (last genuine
        // progress t=15s): acceptance would have fabricated reset-deltas from
        // the poisoned baselines and held the session healthy instead.
        h.outageTick()
        h.outageTick()
        assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        h.outageTick()
        assertEquals(StreamHealth.STALLED, h.outageTick())
        assertFalse(h.monitor.transmissionProgressing)
        assertFalse(h.monitor.senderProgressing)
        assertEquals(0L, h.monitor.sessionDroppedVideo)
        assertEquals(7, h.monitor.samplesAccepted)
    }

    @Test
    fun counterResetDoesNotProduceFalseConclusions() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        // Sender restart zeroes library counters mid-session; the monitor
        // re-baselines instead of concluding anything from the backwards jump.
        val reset = h.monitor.onSample(
            StreamHealthSample(
                nowMs = h.now + 5_000L,
                bitrateBps = 8_000_000L,
                bytesSendTotal = 100L,
                sentVideoTotal = 100L,
                sentAudioTotal = 120L,
                droppedVideoTotal = 3L,
                lastPositiveBitrateMs = h.now + 5_000L,
                lastEncoderFpsMs = h.now + 5_000L
            )
        )
        h.now += 5_000L
        assertEquals(StreamHealth.HEALTHY, reset)
        assertEquals(3L, h.monitor.sessionDroppedVideo)
        assertTrue(h.monitor.transmissionProgressing)
        assertTrue(h.monitor.senderProgressing)
        assertEquals(StreamHealth.HEALTHY, h.tick())
    }

    @Test
    fun twoProgressSamplesAreInsufficient() {
        val h = Harness()
        h.start()
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(2, h.monitor.samplesAccepted)
        // A non-progress gap resets the run: the next two progress ticks
        // still cannot confirm.
        h.outageTick()
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick())
    }

    @Test
    fun hysteresisPreventsOscillation() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(congested = true)
        assertEquals(StreamHealth.DEGRADED, h.tick(congested = true))
        h.tick()
        assertEquals(StreamHealth.DEGRADED, h.monitor.state)
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(6) { index ->
            h.tick(congested = index % 2 == 0)
            assertEquals(StreamHealth.HEALTHY, h.monitor.state)
        }
    }

    @Test
    fun stalledExitsOnlyOnConsecutiveProgress() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        repeat(4) { h.outageTick() }
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        // One stray progress tick holds the stall; two clear the hold back to
        // unconfirmed; the third completes the healthy-entry run.
        h.tick()
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertEquals(StreamHealth.UNKNOWN, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick())
    }

    @Test
    fun duplicateStartSessionIsSafe() {
        val h = Harness()
        h.start()
        h.tick()
        h.monitor.startSession(50_000L)
        h.monitor.startSession(50_000L)
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        h.now = 50_000L
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
    }

    @Test
    fun queueBytePressureDegradesWithoutItemCongestion() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        h.tick(queueBytes = 3_000_000L, queueItems = 10)
        assertEquals(StreamHealth.DEGRADED, h.tick(queueBytes = 3_500_000L, queueItems = 12))
    }
}
