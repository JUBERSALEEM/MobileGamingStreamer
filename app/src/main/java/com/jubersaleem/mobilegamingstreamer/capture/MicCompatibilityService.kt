package com.jubersaleem.mobilegamingstreamer.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * User-enabled compatibility service used by Android during game microphone capture.
 * It has no event subscriptions and does not inspect screen content or automate the device.
 */
class MicCompatibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
            packageNames = null
            flags = 0
        }
        Log.i(TAG, "Microphone compatibility service connected with no event subscriptions")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "MGS-MIC-COMPAT"
    }
}
