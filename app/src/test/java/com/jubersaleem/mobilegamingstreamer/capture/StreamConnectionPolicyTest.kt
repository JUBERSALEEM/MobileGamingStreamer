package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConnectionPolicyTest {
    @Test
    fun backoffDoublesPerAttempt() {
        assertEquals(2_000L, StreamConnectionPolicy.retryDelayMs(1))
        assertEquals(4_000L, StreamConnectionPolicy.retryDelayMs(2))
        assertEquals(8_000L, StreamConnectionPolicy.retryDelayMs(3))
        assertEquals(16_000L, StreamConnectionPolicy.retryDelayMs(4))
    }

    @Test
    fun backoffIsCapped() {
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(5))
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(6))
        assertEquals(30_000L, StreamConnectionPolicy.retryDelayMs(99))
    }

    @Test
    fun attemptsAreBounded() {
        assertEquals(5, StreamConnectionPolicy.attemptsRemaining(0))
        assertEquals(1, StreamConnectionPolicy.attemptsRemaining(4))
        assertEquals(0, StreamConnectionPolicy.attemptsRemaining(5))
        assertEquals(0, StreamConnectionPolicy.attemptsRemaining(9))
    }

    @Test
    fun transientNetworkFailuresAreRetryable() {
        listOf(
            "Connection failed: broken pipe",
            "No response from server",
            "Handshake failed",
            "Error configure stream, connection reset",
            "Connection failed: timeout"
        ).forEach { reason ->
            assertTrue(reason, StreamConnectionPolicy.isRetryableReason(reason))
        }
    }

    @Test
    fun authAndMalformedEndpointsAreTerminal() {
        listOf(
            "Endpoint malformed, should be: rtmp://ip:port/appname/streamname",
            "Stream authentication failed. Check the stream key.",
            "Auth error from server"
        ).forEach { reason ->
            assertFalse(reason, StreamConnectionPolicy.isRetryableReason(reason))
        }
    }

    @Test
    fun reconnectMessagesStateProgress() {
        assertEquals(
            "Reconnecting to streaming server… attempt 1 of 5",
            StreamConnectionPolicy.reconnectMessage(1)
        )
        assertEquals(
            "Reconnecting to streaming server… attempt 5 of 5",
            StreamConnectionPolicy.reconnectMessage(5)
        )
        assertTrue(
            StreamConnectionPolicy.exhaustedMessage().contains("after 5 attempts")
        )
    }
}
