package com.jubersaleem.mobilegamingstreamer.capture

import android.media.MediaRecorder

/** Mirrors the Glip/RootEncoder microphone-source selection for concurrent game voice chat. */
internal object MicrophoneAudioSourcePolicy {
    fun select(accessibilityConnected: Boolean, externalInputConnected: Boolean): Int = when {
        accessibilityConnected && externalInputConnected -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        accessibilityConnected -> MediaRecorder.AudioSource.DEFAULT
        else -> MediaRecorder.AudioSource.MIC
    }
}
