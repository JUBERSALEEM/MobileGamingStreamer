package com.jubersaleem.mobilegamingstreamer.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.pedro.encoder.input.sources.audio.GetMicrophoneData

/**
 * Opt-in Android accessibility bridge used only for concurrent microphone capture
 * while a game owns the microphone for voice communication.
 *
 * It does not retrieve window content, inspect text, perform gestures, or automate UI.
 */
class MgsMicAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = 0
            packageNames = null
        }
        instance = this
    }

    /** Construct the microphone recorder from the active accessibility-service path. */
    fun createMicrophoneManager(callback: GetMicrophoneData): SafeMicrophoneManager =
        SafeMicrophoneManager(callback)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: MgsMicAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val target = ComponentName(context, MgsMicAccessibilityService::class.java)
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == target
            }
        }
    }
}
