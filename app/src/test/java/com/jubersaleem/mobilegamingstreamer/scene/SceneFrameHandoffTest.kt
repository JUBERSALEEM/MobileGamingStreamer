package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic tests for the H1 frame-handoff primitives. Every test drives
 * the real [SceneFrameWindow] / [SceneReleaseGuard] — no Android, no threads,
 * no sleeps.
 */
class SceneFrameHandoffTest {
    // ---- Metadata ----

    @Test
    fun metadataValidTypical() {
        assertTrue(SceneFrameMetadata(sequence = 41L, timestampNs = 1_700_000_000L).isValid)
    }

    @Test
    fun metadataZeroBoundaryValid() {
        assertTrue(SceneFrameMetadata(sequence = 0L, timestampNs = 0L).isValid)
    }

    @Test
    fun metadataNegativeSequenceInvalid() {
        assertFalse(SceneFrameMetadata(sequence = -1L, timestampNs = 100L).isValid)
    }

    @Test
    fun metadataNegativeTimestampInvalid() {
        assertFalse(SceneFrameMetadata(sequence = 7L, timestampNs = -1L).isValid)
    }

    // ---- Window capacity ----

    @Test
    fun windowRejectsZeroCapacity() {
        try {
            SceneFrameWindow(0)
            fail("capacity 0 must throw")
        } catch (expected: IllegalArgumentException) {
            // Required: construction invariant.
        }
    }

    @Test
    fun windowRejectsNegativeCapacity() {
        try {
            SceneFrameWindow(-3)
            fail("negative capacity must throw")
        } catch (expected: IllegalArgumentException) {
            // Required.
        }
    }

    @Test
    fun windowAcceptsUpToCapacity() {
        val window = SceneFrameWindow(2)
        assertEquals(SceneFrameOfferResult.ACCEPTED, window.offer(SceneFrameMetadata(1L, 10L)))
        assertEquals(SceneFrameOfferResult.ACCEPTED, window.offer(SceneFrameMetadata(2L, 20L)))
        assertEquals(2, window.inFlight)
        assertEquals(2L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
    }

    @Test
    fun windowDropsWhenFull() {
        val window = SceneFrameWindow(1)
        assertEquals(SceneFrameOfferResult.ACCEPTED, window.offer(SceneFrameMetadata(1L, 10L)))
        assertEquals(SceneFrameOfferResult.DROPPED, window.offer(SceneFrameMetadata(2L, 20L)))
        assertEquals(SceneFrameOfferResult.DROPPED, window.offer(SceneFrameMetadata(3L, 30L)))
        assertEquals(1, window.inFlight)
        assertEquals(1L, window.acceptedCount)
        assertEquals(2L, window.droppedCount)
    }

    @Test
    fun windowDropsInvalidFrameWithoutConsumingSlot() {
        val window = SceneFrameWindow(1)
        assertEquals(SceneFrameOfferResult.DROPPED, window.offer(SceneFrameMetadata(-1L, 10L)))
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(1L, window.droppedCount)
        // The slot is still free for a valid frame.
        assertEquals(SceneFrameOfferResult.ACCEPTED, window.offer(SceneFrameMetadata(1L, 10L)))
        assertEquals(1, window.inFlight)
    }

    @Test
    fun windowCompleteReleasesSlot() {
        val window = SceneFrameWindow(1)
        window.offer(SceneFrameMetadata(1L, 10L))
        assertTrue(window.complete())
        assertEquals(0, window.inFlight)
        assertEquals(SceneFrameOfferResult.ACCEPTED, window.offer(SceneFrameMetadata(2L, 20L)))
        assertEquals(2L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
    }

    @Test
    fun windowCompleteOnEmptyFalse() {
        val window = SceneFrameWindow(2)
        assertFalse(window.complete())
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
        window.offer(SceneFrameMetadata(1L, 10L))
        assertTrue(window.complete())
        assertFalse(window.complete())
        assertEquals(0, window.inFlight)
    }

    @Test
    fun windowMixedSequenceCountsExact() {
        val window = SceneFrameWindow(2)
        window.offer(SceneFrameMetadata(1L, 10L)) // accepted (1 in flight)
        window.offer(SceneFrameMetadata(2L, 20L)) // accepted (2 in flight)
        window.offer(SceneFrameMetadata(3L, 30L)) // dropped (full)
        window.offer(SceneFrameMetadata(-9L, 40L)) // dropped (invalid)
        assertTrue(window.complete()) // 1 in flight
        window.offer(SceneFrameMetadata(4L, 50L)) // accepted (2 in flight)
        window.offer(SceneFrameMetadata(5L, 60L)) // dropped (full)
        assertEquals(2, window.inFlight)
        assertEquals(3L, window.acceptedCount)
        assertEquals(3L, window.droppedCount)
    }

    @Test
    fun windowDeterministic() {
        fun runScript(): SceneFrameWindow {
            val window = SceneFrameWindow(3)
            for (seq in 1L..10L) window.offer(SceneFrameMetadata(seq, seq * 10L))
            repeat(2) { window.complete() }
            window.offer(SceneFrameMetadata(11L, 110L))
            window.offer(SceneFrameMetadata(-1L, 120L))
            return window
        }
        val first = runScript()
        val second = runScript()
        assertEquals(first.inFlight, second.inFlight)
        assertEquals(first.acceptedCount, second.acceptedCount)
        assertEquals(first.droppedCount, second.droppedCount)
        assertEquals(2, first.inFlight)
        assertEquals(4L, first.acceptedCount)
        assertEquals(8L, first.droppedCount)
    }

    // ---- Release guard ----

    @Test
    fun releaseGuardStartsUnreleased() {
        assertFalse(SceneReleaseGuard().isReleased)
    }

    @Test
    fun releaseGuardRunsActionExactlyOnce() {
        val guard = SceneReleaseGuard()
        var runs = 0
        assertTrue(guard.release { runs += 1 })
        assertTrue(guard.isReleased)
        assertFalse(guard.release { runs += 1 })
        assertFalse(guard.release { runs += 1 })
        assertEquals(1, runs)
        assertTrue(guard.isReleased)
    }

    @Test
    fun releaseGuardThrowingActionRunsOnceAndStaysReleased() {
        val guard = SceneReleaseGuard()
        var runs = 0
        try {
            guard.release {
                runs += 1
                throw IllegalStateException("teardown failed")
            }
            fail("action exception must propagate")
        } catch (expected: IllegalStateException) {
            // Required: never swallowed.
        }
        assertEquals(1, runs)
        assertTrue(guard.isReleased)
        assertFalse(guard.release { runs += 1 })
        assertEquals(1, runs)
    }
}
