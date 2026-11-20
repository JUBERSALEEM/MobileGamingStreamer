package com.jubersaleem.mobilegamingstreamer.capture

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneAudioSourcePolicyTest {
    @Test
    fun accessibilityWithPhoneMicrophoneUsesDefault() {
        assertEquals(
            MediaRecorder.AudioSource.DEFAULT,
            MicrophoneAudioSourcePolicy.select(accessibilityConnected = true, externalInputConnected = false)
        )
    }

    @Test
    fun accessibilityWithExternalHeadsetUsesVoiceRecognition() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicrophoneAudioSourcePolicy.select(accessibilityConnected = true, externalInputConnected = true)
        )
    }

    @Test
    fun withoutAccessibilityUsesMicrophoneFallback() {
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            MicrophoneAudioSourcePolicy.select(accessibilityConnected = false, externalInputConnected = false)
        )
    }

    @Test
    fun withoutAccessibilityExternalHeadsetStillUsesMicrophoneFallback() {
        assertEquals(
            MediaRecorder.AudioSource.MIC,
            MicrophoneAudioSourcePolicy.select(accessibilityConnected = false, externalInputConnected = true)
        )
    }
}
