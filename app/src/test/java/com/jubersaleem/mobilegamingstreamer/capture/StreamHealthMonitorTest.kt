package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the observational health classifier. A fake clock
 * (explicit nowMs) and scripted counter sequences drive the real production
 * [StreamHealthMonitor]; no timing, threads or Android dependencies.
 */
class StreamHealthMonitorTest {
    private class Harness {
        val monitor = StreamHealthMonitor()
        var now = 0L
        private var sentV = 0L
        private var sentA = 0L
        private var dropV = 0L
        private var dropA = 0L
        private var posBitrate = 0L
        private var posFps = 0L

        fun start() {
            now = 0L
            sentV = 0L
            sentA = 0L
            dropV = 0L
            dropA = 0L
            posBitrate = 0L
            posFps = 0L
            monitor.startSession(now)
        }

        fun tick(
            bitrate: Long = 8_000_000L,
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
            bitrate = 0L, sentVideoStep = 0L, sentAudioStep = 0L,
            queueItems = 0, queueBytes = 0L, fpsStale = true, stepMs = stepMs
        )
    }

    @Test
    fun initialStateIsUnknown() {
        assertEquals(StreamHealth.UNKNOWN, StreamHealthMonitor().state)
    }

    @Test
    fun insufficientSamplesRemainUnknown() {
        val h = Harness()
        h.start()
        assertEquals(StreamHealth.UNKNOWN, h.tick())
    }

    @Test
    fun normalTransmissionBecomesHealthy() {
        val h = Harness()
        h.start()
        h.tick()
        h.tick()
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertTrue(h.monitor.transmissionProgressing)
        assertTrue(h.monitor.encoderProgressing)
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
            h.tick(bitrate = 0L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = false)
        }
        assertEquals(StreamHealth.STALLED, h.monitor.state)
        assertTrue(h.monitor.encoderProgressing)
        assertFalse(h.monitor.transmissionProgressing)
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
                bitrate = 0L, sentVideoStep = 0L, sentAudioStep = 0L,
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
                h.tick(bitrate = 0L, sentVideoStep = 0L, sentAudioStep = 0L, fpsStale = true, recovering = true)
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
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        h.tick()
        h.tick()
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
        h.monitor.onStopped()
        assertEquals(StreamHealth.UNKNOWN, h.monitor.state)
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
        h.monitor.startSession(200_000L)
        val stale = h.monitor.onSample(
            StreamHealthSample(
                nowMs = 5_000L,
                sentVideoTotal = 9_999_999L,
                sentAudioTotal = 9_999_999L,
                droppedVideoTotal = 500L
            )
        )
        assertEquals(StreamHealth.UNKNOWN, stale)
        h.now = 200_000L
        assertEquals(StreamHealth.HEALTHY, h.tick())
        assertEquals(StreamHealth.HEALTHY, h.tick())
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
        h.tick()
        assertEquals(StreamHealth.STALLED, h.monitor.state)
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
