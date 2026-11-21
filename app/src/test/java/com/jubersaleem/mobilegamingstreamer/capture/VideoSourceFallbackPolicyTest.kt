package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All eight input combinations, pinned: fallback fires in exactly one —
 * scene requested, no prior attempt, never confirmed.
 */
class VideoSourceFallbackPolicyTest {
    @Test
    fun fallbackFiresForUnconfirmedSceneFailure() {
        assertTrue(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = true,
                fallbackAlreadyAttempted = false,
                streamingConfirmed = false
            )
        )
    }

    @Test
    fun noFallbackWhenSceneNeverRequested() {
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = false,
                fallbackAlreadyAttempted = false,
                streamingConfirmed = false
            )
        )
    }

    @Test
    fun noFallbackAfterOneAttempt() {
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = true,
                fallbackAlreadyAttempted = true,
                streamingConfirmed = false
            )
        )
    }

    @Test
    fun noFallbackOnceStreamingConfirmed() {
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = true,
                fallbackAlreadyAttempted = false,
                streamingConfirmed = true
            )
        )
    }

    @Test
    fun noFallbackForDefaultPathInAnyState() {
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = false,
                fallbackAlreadyAttempted = true,
                streamingConfirmed = true
            )
        )
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = false,
                fallbackAlreadyAttempted = false,
                streamingConfirmed = true
            )
        )
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = false,
                fallbackAlreadyAttempted = true,
                streamingConfirmed = false
            )
        )
    }

    @Test
    fun noFallbackWhenAllBlockingConditionsHold() {
        assertFalse(
            VideoSourceFallbackPolicy.shouldFallbackToScreenSource(
                requestedSceneSource = true,
                fallbackAlreadyAttempted = true,
                streamingConfirmed = true
            )
        )
    }
}
