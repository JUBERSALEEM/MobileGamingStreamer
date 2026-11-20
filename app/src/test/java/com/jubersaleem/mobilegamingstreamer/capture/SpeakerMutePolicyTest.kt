package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerMutePolicyTest {
    @Test
    fun muteRecordsPreviousVolumeAndZeroesStream() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(false, 0), 9, 15)
        assertEquals(0, result.applyVolume)
        assertTrue(result.state.owned)
        assertEquals(9, result.state.savedVolume)
    }

    @Test
    fun muteClampsOutOfRangeVolume() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(false, 0), 99, 15)
        assertEquals(0, result.applyVolume)
        assertEquals(15, result.state.savedVolume)
    }

    @Test
    fun unmuteRestoresRecordedVolume() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(true, 9), 0, 15)
        assertEquals(9, result.applyVolume)
        assertFalse(result.state.owned)
    }

    @Test
    fun unmuteRespectsHardwareVolumeChange() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(true, 9), 5, 15)
        assertNull(result.applyVolume)
        assertFalse(result.state.owned)
    }

    @Test
    fun unmuteClampsRecordedVolumeToDeviceMaximum() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(true, 99), 0, 15)
        assertEquals(15, result.applyVolume)
    }

    @Test
    fun tapWhileAlreadySilentSelectsMediumLevelWithoutOwnership() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(false, 0), 0, 15)
        assertEquals(7, result.applyVolume)
        assertFalse(result.state.owned)
    }

    @Test
    fun silentTapNeverSelectsZero() {
        val result = SpeakerMutePolicy.onToggle(SpeakerMuteState(false, 0), 0, 1)
        assertEquals(1, result.applyVolume)
    }

    @Test
    fun destroyWhileOwnedRestoresVolume() {
        val result = SpeakerMutePolicy.onDestroy(SpeakerMuteState(true, 9), 0, 15)
        assertEquals(9, result.applyVolume)
        assertFalse(result.state.owned)
    }

    @Test
    fun destroyAfterHardwareChangeLeavesUserVolumeAlone() {
        val result = SpeakerMutePolicy.onDestroy(SpeakerMuteState(true, 9), 4, 15)
        assertNull(result.applyVolume)
        assertFalse(result.state.owned)
    }

    @Test
    fun destroyWithoutOwnershipChangesNothing() {
        val result = SpeakerMutePolicy.onDestroy(SpeakerMuteState(false, 0), 0, 15)
        assertNull(result.applyVolume)
    }

    @Test
    fun restorationRunsOnlyOnce() {
        val first = SpeakerMutePolicy.onDestroy(SpeakerMuteState(true, 9), 0, 15)
        val second = SpeakerMutePolicy.onDestroy(first.state, 0, 15)
        assertEquals(9, first.applyVolume)
        assertNull(second.applyVolume)
    }

    @Test
    fun restartAdoptsOwnershipWhileStreamStillSilent() {
        val state = SpeakerMutePolicy.onStart(true, 9, 0, 15)
        assertTrue(state.owned)
        assertEquals(9, state.savedVolume)
    }

    @Test
    fun restartReleasesOwnershipAfterVolumeMoved() {
        val state = SpeakerMutePolicy.onStart(true, 9, 6, 15)
        assertFalse(state.owned)
    }

    @Test
    fun restartWithoutStoredOwnershipStaysUnowned() {
        val state = SpeakerMutePolicy.onStart(false, 9, 0, 15)
        assertFalse(state.owned)
    }

    @Test
    fun displayFollowsActualVolume() {
        assertTrue(SpeakerMutePolicy.displayMuted(0))
        assertFalse(SpeakerMutePolicy.displayMuted(1))
    }
}
