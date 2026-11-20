package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProfileSelectorTest {
    @Test
    fun prefersRequestedFpsBeforeReducingResolution() {
        val profiles = VideoProfileSelector.build("1440p", 60, false)
        assertEquals(
            listOf("1440p@60", "1080p@60", "720p@60", "1440p@30", "1080p@30", "720p@30"),
            profiles.map { "${it.quality}@${it.fps}" }
        )
    }

    @Test
    fun keepsRequestedResolutionWhenThirtyFpsWasSelected() {
        val profiles = VideoProfileSelector.build("1080p", 30, false)
        assertEquals(listOf("1080p@30", "720p@30"), profiles.map { "${it.quality}@${it.fps}" })
    }

    @Test
    fun portraitProfilesUseEvenVerticalDimensions() {
        val profile = VideoProfileSelector.build("1080p", 60, true).first()
        assertEquals(1080, profile.width)
        assertEquals(1920, profile.height)
        assertEquals(0, profile.width % 2)
        assertEquals(0, profile.height % 2)
    }

    @Test
    fun onlyUsesSupportedThirtyOrSixtyFpsModes() {
        val profiles = VideoProfileSelector.build("720p", 45, false)
        assertTrue(profiles.all { it.fps == 30 })
    }
}
