package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Same-package forged handle: permitted by the sealed-subclass rule, but must
 * be inert — complete() only accepts the window's private implementation.
 * If this declaration ever stops compiling, the sealed boundary got
 * stronger, not weaker; see forgedSamePackageCompletionRejected.
 */
private class ForgedCompletion : SceneFrameWindow.Completion

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
    fun windowAcceptsUpToCapacityReturnsHandles() {
        val window = SceneFrameWindow(2)
        val first = window.offer(SceneFrameMetadata(1L, 10L))
        val second = window.offer(SceneFrameMetadata(2L, 20L))
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(2, window.inFlight)
        assertEquals(2L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
    }

    @Test
    fun windowDropsWhenFullReturnsNull() {
        val window = SceneFrameWindow(1)
        assertNotNull(window.offer(SceneFrameMetadata(1L, 10L)))
        assertNull(window.offer(SceneFrameMetadata(2L, 20L)))
        assertNull(window.offer(SceneFrameMetadata(3L, 30L)))
        assertEquals(1, window.inFlight)
        assertEquals(1L, window.acceptedCount)
        assertEquals(2L, window.droppedCount)
    }

    @Test
    fun windowDropsInvalidFrameWithoutConsumingSlot() {
        val window = SceneFrameWindow(1)
        assertNull(window.offer(SceneFrameMetadata(-1L, 10L)))
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(1L, window.droppedCount)
        // The slot is still free for a valid frame.
        assertNotNull(window.offer(SceneFrameMetadata(1L, 10L)))
        assertEquals(1, window.inFlight)
    }

    // ---- Completion handles ----

    @Test
    fun windowHandlesUsableIndependentlyOutOfOrder() {
        val window = SceneFrameWindow(2)
        val first = window.offer(SceneFrameMetadata(1L, 10L))!!
        val second = window.offer(SceneFrameMetadata(2L, 20L))!!
        assertTrue(window.complete(second))
        assertEquals(1, window.inFlight)
        assertTrue(window.complete(first))
        assertEquals(0, window.inFlight)
        assertEquals(2L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
    }

    @Test
    fun windowCompleteReleasesExactlyOneSlot() {
        val window = SceneFrameWindow(2)
        val first = window.offer(SceneFrameMetadata(1L, 10L))!!
        val second = window.offer(SceneFrameMetadata(2L, 20L))!!
        assertTrue(window.complete(first))
        assertEquals(1, window.inFlight)
        // Exactly one slot freed: one re-offer fits, the next does not.
        assertNotNull(window.offer(SceneFrameMetadata(3L, 30L)))
        assertNull(window.offer(SceneFrameMetadata(4L, 40L)))
        assertEquals(2, window.inFlight)
        assertTrue(window.complete(second))
        assertEquals(1, window.inFlight)
    }

    @Test
    fun windowDoubleCompleteReleasesOnce() {
        val window = SceneFrameWindow(2)
        val first = window.offer(SceneFrameMetadata(1L, 10L))!!
        val second = window.offer(SceneFrameMetadata(2L, 20L))!!
        assertTrue(window.complete(first))
        // Second completion of the same handle must not release another slot.
        assertFalse(window.complete(first))
        assertFalse(window.complete(first))
        assertEquals(1, window.inFlight)
        // The other frame's slot is intact and still completes exactly once.
        assertTrue(window.complete(second))
        assertEquals(0, window.inFlight)
        assertEquals(2L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
    }

    @Test
    fun windowRejectsForeignHandle() {
        val window = SceneFrameWindow(1)
        val other = SceneFrameWindow(1)
        val foreign = other.offer(SceneFrameMetadata(100L, 1L))!!
        assertFalse(window.complete(foreign))
        // Neither window changed: no slot moved, counts untouched.
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
        assertEquals(1, other.inFlight)
        assertEquals(1L, other.acceptedCount)
        assertEquals(0L, other.droppedCount)
        // The foreign handle still belongs to its owner.
        assertTrue(other.complete(foreign))
        assertEquals(0, other.inFlight)
    }

    @Test
    fun windowRejectsNullCompletion() {
        val window = SceneFrameWindow(1)
        assertFalse(window.complete(null))
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
        val handle = window.offer(SceneFrameMetadata(1L, 10L))!!
        assertFalse(window.complete(null))
        assertEquals(1, window.inFlight)
        assertTrue(window.complete(handle))
        assertEquals(0, window.inFlight)
    }

    @Test
    fun windowCapacityNeverExceeded() {
        val window = SceneFrameWindow(3)
        fun checkBound(tag: String) {
            assertTrue(tag, window.inFlight >= 0)
            assertTrue(tag, window.inFlight <= 3)
        }
        // Over-offer: exactly capacity handles, no more slots.
        val first = (1L..6L).map { window.offer(SceneFrameMetadata(it, it * 10L)) }
        checkBound("over-offer")
        assertEquals(3, first.count { it != null })
        var nonNullOffers = 3
        var nullOffers = 3
        first.filterNotNull().forEachIndexed { index, handle ->
            assertTrue("complete#$index", window.complete(handle))
            checkBound("complete#$index")
        }
        assertEquals(0, window.inFlight)
        // Interleaved offers, completions, and rejections: bound holds.
        val other = SceneFrameWindow(1)
        val foreign = other.offer(SceneFrameMetadata(100L, 1L))!!
        val live = mutableListOf<SceneFrameWindow.Completion>()
        repeat(20) { step ->
            val offered = window.offer(SceneFrameMetadata(1000L + step, step.toLong()))
            if (offered != null) {
                live.add(offered)
                nonNullOffers += 1
            } else {
                nullOffers += 1
            }
            checkBound("offer#$step")
            if (step % 3 == 0 && live.isNotEmpty()) {
                assertTrue("step#$step", window.complete(live.removeAt(0)))
                checkBound("complete#$step")
            }
            if (step % 5 == 0) {
                val droppedBefore = window.droppedCount
                assertFalse("foreign#$step", window.complete(foreign))
                assertFalse("null#$step", window.complete(null))
                // Rejections never pollute accounting.
                assertEquals(droppedBefore, window.droppedCount)
                checkBound("reject#$step")
            }
        }
        assertEquals(nonNullOffers.toLong(), window.acceptedCount)
        assertEquals(nullOffers.toLong(), window.droppedCount)
        // Drain: every live handle completes exactly once.
        live.forEach { assertTrue(window.complete(it)) }
        assertEquals(0, window.inFlight)
        live.forEach { assertFalse(window.complete(it)) }
        assertEquals(0, window.inFlight)
    }

    @Test
    fun windowMixedSequenceCountsExact() {
        val window = SceneFrameWindow(2)
        val first = window.offer(SceneFrameMetadata(1L, 10L)) // accepted
        assertNotNull(first)
        assertNotNull(window.offer(SceneFrameMetadata(2L, 20L))) // accepted
        assertNull(window.offer(SceneFrameMetadata(3L, 30L))) // dropped (full)
        assertNull(window.offer(SceneFrameMetadata(-9L, 40L))) // dropped (invalid)
        assertTrue(window.complete(first)) // 1 in flight
        assertNotNull(window.offer(SceneFrameMetadata(4L, 50L))) // accepted
        assertNull(window.offer(SceneFrameMetadata(5L, 60L))) // dropped (full)
        assertEquals(2, window.inFlight)
        assertEquals(3L, window.acceptedCount)
        assertEquals(3L, window.droppedCount)
    }

    @Test
    fun windowDeterministic() {
        fun runScript(): SceneFrameWindow {
            val window = SceneFrameWindow(3)
            val live = mutableListOf<SceneFrameWindow.Completion>()
            for (seq in 1L..10L) {
                val handle = window.offer(SceneFrameMetadata(seq, seq * 10L))
                if (handle != null) live.add(handle)
            }
            assertTrue(window.complete(live[0]))
            assertTrue(window.complete(live[1]))
            assertTrue(window.offer(SceneFrameMetadata(11L, 110L)) != null)
            assertTrue(window.offer(SceneFrameMetadata(-1L, 120L)) == null)
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

    @Test
    fun windowInvalidMetadataNeverConsumesCapacity() {
        val window = SceneFrameWindow(2)
        repeat(5) { step ->
            assertNull(window.offer(SceneFrameMetadata(-1L - step, step.toLong())))
        }
        assertEquals(0, window.inFlight)
        assertEquals(0L, window.acceptedCount)
        assertEquals(5L, window.droppedCount)
    }

    // ---- Mint boundary (compile-time encapsulation, pinned at runtime) ----

    @Test
    fun completionImplIsSingleAndPrivate() {
        val completionType = SceneFrameWindow.Completion::class.java
        // The interface itself exposes no callable surface.
        assertTrue(completionType.declaredMethods.isEmpty())
        // Exactly one implementation exists in the window, and it is private:
        // no other caller can name the type to construct or consume it.
        val impls = SceneFrameWindow::class.java.declaredClasses
            .filter { !it.isSynthetic && !it.isInterface && completionType.isAssignableFrom(it) }
        assertEquals(1, impls.size)
        assertTrue(Modifier.isPrivate(impls[0].modifiers))
    }

    @Test
    fun offerIsOnlyMinterOfCompletion() {
        val completionType = SceneFrameWindow.Completion::class.java
        val minters = SceneFrameWindow::class.java.declaredMethods
            .filter { it.returnType == completionType }
        assertEquals(1, minters.size)
        assertEquals("offer", minters[0].name)
    }

    @Test
    fun forgedSamePackageCompletionRejected() {
        // Even a same-package forged implementation (permitted by the sealed
        // rule) is inert: complete() only accepts the window's own private
        // implementation minted by offer().
        val window = SceneFrameWindow(1)
        val handle = window.offer(SceneFrameMetadata(1L, 10L))!!
        assertFalse(window.complete(ForgedCompletion()))
        assertEquals(1, window.inFlight)
        assertEquals(1L, window.acceptedCount)
        assertEquals(0L, window.droppedCount)
        assertTrue(window.complete(handle))
        assertEquals(0, window.inFlight)
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
