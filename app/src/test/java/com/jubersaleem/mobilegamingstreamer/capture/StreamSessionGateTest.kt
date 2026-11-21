package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Phase E session-ownership gate. Every test
 * drives the real production [StreamSessionGate]; the scripted tests route
 * A/B-session callback tokens through it exactly as the service's checker
 * and projection callbacks do (validate before any effect) and assert both
 * the accepted current-session effects and the rejected stale ones. No
 * Android dependencies, no sleeps. Each test uses a local gate instance.
 */
class StreamSessionGateTest {

    /** Legitimate counterpart: a current-session callback is never blocked. */
    @Test
    fun currentTokenAccepted() {
        val gate = StreamSessionGate()
        val token = gate.beginSession()
        assertTrue(gate.isCurrent(token))
    }

    /** DEFECT 1 core: a repeated START invalidates the previous session's token. */
    @Test
    fun newSessionInvalidatesPreviousToken() {
        val gate = StreamSessionGate()
        val oldToken = gate.beginSession()
        val newToken = gate.beginSession()
        assertFalse("stale onDisconnect token must be rejected", gate.isCurrent(oldToken))
        assertTrue(gate.isCurrent(newToken))
    }

    /** A→B→C chain: only the latest session's token is valid. */
    @Test
    fun onlyLatestTokenValidAcrossRepeatedSessions() {
        val gate = StreamSessionGate()
        val first = gate.beginSession()
        val second = gate.beginSession()
        val third = gate.beginSession()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(third))
    }

    /** DEFECT 2 core: destruction invalidates every outstanding token. */
    @Test
    fun invalidateRejectsOutstandingToken() {
        val gate = StreamSessionGate()
        val token = gate.beginSession()
        gate.invalidate()
        assertFalse("post-destroy release echo must be rejected", gate.isCurrent(token))
    }

    /** After destruction a fresh session works while the destroyed token stays dead. */
    @Test
    fun freshSessionAfterInvalidate() {
        val gate = StreamSessionGate()
        val destroyed = gate.beginSession()
        gate.invalidate()
        val fresh = gate.beginSession()
        assertFalse(gate.isCurrent(destroyed))
        assertTrue(gate.isCurrent(fresh))
    }

    /**
     * Scripted repeated-START (scenarios A/H/I): session A's release echo
     * (onDisconnect) and in-flight failure/bitrate posts arrive after session
     * B started. Only B's callbacks may produce effects; stale posts must
     * cause no stop, no status publish, no retry and no LIVE line.
     */
    @Test
    fun staleCallbacksProduceNoEffectsOnNewerSession() {
        val gate = StreamSessionGate()
        val tokenA = gate.beginSession()
        val tokenB = gate.beginSession()
        val effects = mutableListOf<String>()
        // Stale session-A posts arriving after B started.
        deliver(gate, tokenA, "A", "stop", effects)
        deliver(gate, tokenA, "A", "publish", effects)
        deliver(gate, tokenA, "A", "retry", effects)
        deliver(gate, tokenA, "A", "live", effects)
        // Current session-B callbacks.
        deliver(gate, tokenB, "B", "publish", effects)
        deliver(gate, tokenB, "B", "live", effects)
        assertEquals(listOf("B:publish", "B:live"), effects)
    }

    /**
     * Scripted destroy (scenario C): the release echo and any in-flight post
     * arriving after onDestroy must produce no effect — in particular no
     * terminal-state overwrite — while the pre-destroy current callback did.
     */
    @Test
    fun postDestroyCallbacksProduceNoEffects() {
        val gate = StreamSessionGate()
        val token = gate.beginSession()
        val effects = mutableListOf<String>()
        deliver(gate, token, "A", "publish", effects)
        gate.invalidate()
        deliver(gate, token, "A", "stop", effects)
        deliver(gate, token, "A", "publish", effects)
        assertEquals(listOf("A:publish"), effects)
    }

    /**
     * Mirrors the service wiring: validate the captured token before any
     * effect. Labels effects with their originating session so the test can
     * prove which session acted.
     */
    private fun deliver(
        gate: StreamSessionGate,
        token: Long,
        session: String,
        effect: String,
        log: MutableList<String>
    ) {
        if (!gate.isCurrent(token)) return
        log.add("$session:$effect")
    }
}
