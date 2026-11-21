package com.jubersaleem.mobilegamingstreamer

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioStatusTest {
    @Test
    fun liveSessionAlwaysWinsClassification() {
        assertEquals(
            BroadcastStatusKind.LIVE,
            classifyBroadcastStatus("LIVE • 1080p @ 30fps", active = true, starting = false)
        )
    }

    @Test
    fun startingPhaseWinsOverStaleText() {
        assertEquals(
            BroadcastStatusKind.STARTING,
            classifyBroadcastStatus(
                BroadcastStatusMessages.encoderReadyMessage("1080p @ 30fps"),
                active = false,
                starting = true
            )
        )
    }

    @Test
    fun failureMessagesSurfaceAsError() {
        listOf(
            com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.terminalFailureMessage("auth error"),
            BroadcastStatusMessages.AUTH_FAILED,
            BroadcastStatusMessages.INVALID_SETUP,
            BroadcastStatusMessages.UNABLE_TO_START_PREFIX + "encoder error",
            BroadcastStatusMessages.STALL_STOP
        ).forEach { message ->
            assertEquals(message, BroadcastStatusKind.ERROR, classifyBroadcastStatus(message, active = false, starting = false))
        }
    }

    @Test
    fun idleMessagesStayStandby() {
        // BroadcastStatusMessages.STOPPED is the service line; the rest are
        // UI-owned initial texts that the generic classifier must also keep
        // on standby.
        listOf(
            "Ready to stream",
            BroadcastStatusMessages.STOPPED,
            "Capture stopped",
            "Broadcast setup ready",
            "Previous broadcast ended unexpectedly"
        ).forEach { message ->
            assertEquals(message, BroadcastStatusKind.STANDBY, classifyBroadcastStatus(message, active = false, starting = false))
        }
    }

    @Test
    fun startingKeywordsAreDetected() {
        listOf(
            BroadcastStatusMessages.PREPARING_ENCODER,
            BroadcastStatusMessages.encoderReadyMessage("1080p @ 30fps"),
            BroadcastStatusMessages.CONNECTING,
            BroadcastStatusMessages.RTMP_CONNECTED_WAITING,
            BroadcastStatusMessages.CONNECTED_WAITING,
            BroadcastStatusMessages.KEY_ACCEPTED,
            "60 FPS unavailable — continuing at 30"
        ).forEach { message ->
            assertTrue(message, isStartingBroadcastMessage(message))
        }
    }

    @Test
    fun steadyMessagesAreNotStarting() {
        listOf("Ready to stream", "LIVE • 8123 kbps", BroadcastStatusMessages.STOPPED).forEach { message ->
            assertFalse(message, isStartingBroadcastMessage(message))
        }
    }

    @Test
    fun reconnectProgressMessagesAreStarting() {
        listOf(
            com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.reconnectMessage(1),
            com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.reconnectMessage(5),
            BroadcastStatusMessages.RECONNECTED_WAITING
        ).forEach { message ->
            assertTrue(message, isStartingBroadcastMessage(message))
            assertEquals(
                message, BroadcastStatusKind.STARTING,
                classifyBroadcastStatus(message, active = false, starting = true)
            )
        }
    }

    @Test
    fun exhaustedRecoveryIsErrorNotStarting() {
        val exhausted = com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.exhaustedMessage()
        assertFalse(exhausted, isStartingBroadcastMessage(exhausted))
        assertEquals(
            BroadcastStatusKind.ERROR,
            classifyBroadcastStatus(exhausted, active = false, starting = false)
        )
    }

    @Test
    fun terminalFailureKeepsErrorClassification() {
        val terminal = com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy.terminalFailureMessage(
            "Endpoint malformed, should be: rtmp://ip:port/appname/streamname"
        )
        assertFalse(terminal, isStartingBroadcastMessage(terminal))
        assertEquals(
            BroadcastStatusKind.ERROR,
            classifyBroadcastStatus(terminal, active = false, starting = false)
        )
    }

    /**
     * Pins the complete intended terminal-status set against the actual
     * production lines: every message the service or policy can publish as
     * a session's final status, with its expected classification. Disconnect,
     * projection loss and the plain/destroy stops intentionally read as
     * standby (preserved behavior); failures read as error.
     */
    @Test
    fun terminalStatusSetIsPinned() {
        val policy = com.jubersaleem.mobilegamingstreamer.capture.StreamConnectionPolicy
        val expected = mapOf(
            BroadcastStatusMessages.STARTUP_TIMEOUT_STOP to BroadcastStatusKind.ERROR,
            BroadcastStatusMessages.STALL_STOP to BroadcastStatusKind.ERROR,
            BroadcastStatusMessages.DISCONNECTED to BroadcastStatusKind.STANDBY,
            BroadcastStatusMessages.AUTH_FAILED to BroadcastStatusKind.ERROR,
            policy.PROJECTION_ENDED_MESSAGE to BroadcastStatusKind.STANDBY,
            policy.exhaustedMessage() to BroadcastStatusKind.ERROR,
            policy.terminalFailureMessage("No response from server") to BroadcastStatusKind.ERROR,
            BroadcastStatusMessages.UNABLE_TO_START_PREFIX + "encoder error" to BroadcastStatusKind.ERROR,
            BroadcastStatusMessages.INVALID_SETUP to BroadcastStatusKind.ERROR,
            BroadcastStatusMessages.STOPPED to BroadcastStatusKind.STANDBY,
            BroadcastStatusMessages.SERVICE_STOPPED to BroadcastStatusKind.STANDBY
        )
        assertEquals(11, expected.size)
        expected.forEach { (message, kind) ->
            assertEquals(message, kind, classifyBroadcastStatus(message, active = false, starting = false))
        }
    }

    /** Legitimate non-terminal lines keep their starting/standby behavior. */
    @Test
    fun nonTerminalStatusesBehave() {
        listOf(
            BroadcastStatusMessages.PREPARING_ENCODER,
            BroadcastStatusMessages.encoderReadyMessage("720p @ 30fps"),
            BroadcastStatusMessages.CONNECTING,
            BroadcastStatusMessages.RTMP_CONNECTED_WAITING,
            BroadcastStatusMessages.RECONNECTED_WAITING,
            BroadcastStatusMessages.CONNECTED_WAITING,
            BroadcastStatusMessages.KEY_ACCEPTED
        ).forEach { message ->
            assertTrue(message, isStartingBroadcastMessage(message))
            assertEquals(
                message, BroadcastStatusKind.STARTING,
                classifyBroadcastStatus(message, active = false, starting = true)
            )
        }
        assertEquals(
            BroadcastStatusKind.STANDBY,
            classifyBroadcastStatus(BroadcastStatusMessages.NO_ACTIVE_STREAM, active = false, starting = false)
        )
        assertFalse(BroadcastStatusMessages.NO_ACTIVE_STREAM, isStartingBroadcastMessage(BroadcastStatusMessages.NO_ACTIVE_STREAM))
    }

    @Test
    fun announcementsAreConcisePerState() {
        assertEquals("You are live", broadcastAnnouncement(BroadcastStatusKind.LIVE, "LIVE • 8123 kbps"))
        assertEquals(
            "Connecting to stream",
            broadcastAnnouncement(BroadcastStatusKind.STARTING, "Connecting to streaming server…")
        )
        assertEquals(
            "Broadcast error. Connection failed: auth error.",
            broadcastAnnouncement(BroadcastStatusKind.ERROR, "Connection failed: auth error.")
        )
        assertEquals("Broadcast stopped", broadcastAnnouncement(BroadcastStatusKind.STANDBY, "Broadcast stopped"))
    }

    @Test
    fun sourceConstantsMatchFramework() {
        assertEquals(MediaRecorder.AudioSource.DEFAULT, AudioRouteLabels.SOURCE_DEFAULT)
        assertEquals(MediaRecorder.AudioSource.MIC, AudioRouteLabels.SOURCE_MIC)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioRouteLabels.SOURCE_VOICE_RECOGNITION)
    }

    @Test
    fun sourceLabelsAreHumanReadable() {
        assertEquals("DEFAULT", AudioRouteLabels.micSourceLabel(0))
        assertEquals("MIC", AudioRouteLabels.micSourceLabel(1))
        assertEquals("VOICE_RECOGNITION", AudioRouteLabels.micSourceLabel(6))
        assertEquals("—", AudioRouteLabels.micSourceLabel(-1))
        assertEquals("—", AudioRouteLabels.micSourceLabel(999))
    }
}
