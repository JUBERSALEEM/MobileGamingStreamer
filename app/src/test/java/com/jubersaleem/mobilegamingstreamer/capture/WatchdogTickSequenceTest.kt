package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the extracted watchdog-cycle ordering: the
 * observational health sample runs before the authoritative driver decision,
 * the decision passes through unchanged, and a throwing sampler can never
 * suppress termination.
 *
 * Limitation: these tests pin the extracted [runWatchdogCycle] logic. That
 * the service's watchdog runnable actually routes through it is verified by
 * diff review and by the CI Android build, not here — the service needs the
 * Android framework and cannot run in this JVM harness.
 */
class WatchdogTickSequenceTest {
    @Test
    fun sampleRunsBeforeDecision() {
        val order = mutableListOf<String>()
        val decision = runWatchdogCycle(
            sampleHealth = { order.add("sample") },
            decide = { order.add("decide"); WatchdogAction.NONE }
        )
        assertEquals(WatchdogAction.NONE, decision)
        assertEquals(listOf("sample", "decide"), order)
    }

    @Test
    fun everyDecisionPassesThroughUnchanged() {
        for (action in WatchdogAction.values()) {
            var sampled = false
            val decision = runWatchdogCycle(
                sampleHealth = { sampled = true },
                decide = { action }
            )
            assertTrue(sampled)
            assertEquals(action, decision)
        }
    }

    @Test
    fun throwingSampleCannotSuppressDecision() {
        var decided = false
        val decision = runWatchdogCycle(
            sampleHealth = { throw IllegalStateException("sampler blew up") },
            decide = { decided = true; WatchdogAction.STOP_STALLED }
        )
        assertTrue(decided)
        assertEquals(WatchdogAction.STOP_STALLED, decision)
    }
}
