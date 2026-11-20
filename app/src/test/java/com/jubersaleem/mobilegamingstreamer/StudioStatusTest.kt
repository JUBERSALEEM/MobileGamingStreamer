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
            classifyBroadcastStatus("Encoder ready • 1080p @ 30fps • Connecting…", active = false, starting = true)
        )
    }

    @Test
    fun failureMessagesSurfaceAsError() {
        listOf(
            "Connection failed: auth error. Open the app and tap GO LIVE to grant a fresh screen-capture session and retry.",
            "Stream authentication failed. Check the stream key.",
            "Invalid stream setup",
            "Unable to start: encoder error",
            "Stream output stalled for 20 seconds. Check your network, then tap GO LIVE and approve a fresh screen-capture session."
        ).forEach { message ->
            assertEquals(message, BroadcastStatusKind.ERROR, classifyBroadcastStatus(message, active = false, starting = false))
        }
    }

    @Test
    fun idleMessagesStayStandby() {
        listOf(
            "Ready to stream",
            "Broadcast stopped",
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
            "Preparing video encoder…",
            "Encoder ready • 1080p @ 30fps • Connecting…",
            "Connecting to streaming server…",
            "RTMP connected • Waiting for video frames",
            "Connected • Waiting for outgoing video",
            "Stream key accepted",
            "60 FPS unavailable — continuing at 30"
        ).forEach { message ->
            assertTrue(message, isStartingBroadcastMessage(message))
        }
    }

    @Test
    fun steadyMessagesAreNotStarting() {
        listOf("Ready to stream", "LIVE • 8123 kbps", "Broadcast stopped").forEach { message ->
            assertFalse(message, isStartingBroadcastMessage(message))
        }
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
