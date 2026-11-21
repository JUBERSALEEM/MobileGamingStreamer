package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Ownership-based local-speaker mute policy for the floating controls.
 *
 * The speaker button only ever touches [android.media.AudioManager.STREAM_MUSIC];
 * notification, alarm and call streams are never modified. Muting is only ever
 * *owned* when this policy itself moved a non-zero volume to zero, and ownership
 * is persisted so a service restart can still honour it:
 *
 * - Mute records the exact previous volume and zeroes the music stream.
 * - Unmute restores the recorded volume, unless the user already moved the
 *   volume with hardware buttons while muted — the user's choice always wins
 *   and no stale value is ever written back.
 * - If the service is destroyed while it owns the mute, the volume is restored
 *   exactly once. Restoration never runs twice for one mute operation.
 * - A tap while the speaker is already silent through no action of ours does
 *   not invent ownership; as an explicit unmute request it selects a safe
 *   medium level instead of restoring a value we never recorded.
 *
 * Process death (as opposed to orderly service destroy) cannot run restoration
 * code; in that case the persisted ownership is adopted on the next start only
 * if the music stream is still silent, otherwise it is released.
 *
 * This object is pure Kotlin with no Android dependencies so every transition
 * is covered by JVM unit tests. The service layer only reads/writes the real
 * volume and persists [SpeakerMuteState].
 */
internal data class SpeakerMuteState(
    val owned: Boolean = false,
    val savedVolume: Int = 0
)

internal data class SpeakerMuteTransition(
    val state: SpeakerMuteState,
    /**
     * Volume to apply to the music stream, or null to leave the device volume
     * exactly as the user left it.
     */
    val applyVolume: Int? = null
)

internal object SpeakerMutePolicy {
    /** What the button must show: the speaker is muted whenever volume is zero. */
    fun displayMuted(currentVolume: Int): Boolean = currentVolume <= 0

    /**
     * Adopt persisted ownership when the service (re)starts. Never applies a
     * volume itself; restoration happens on the next toggle or destroy.
     */
    fun onStart(
        storedOwned: Boolean,
        storedVolume: Int,
        currentVolume: Int,
        maxVolume: Int
    ): SpeakerMuteState {
        if (!storedOwned) return SpeakerMuteState(false, 0)
        if (currentVolume > 0) return SpeakerMuteState(false, 0)
        return SpeakerMuteState(true, storedVolume.coerceIn(1, maxVolume.coerceAtLeast(1)))
    }

    /** User tapped the speaker button. */
    fun onToggle(
        state: SpeakerMuteState,
        currentVolume: Int,
        maxVolume: Int
    ): SpeakerMuteTransition {
        val max = maxVolume.coerceAtLeast(1)
        if (!state.owned) {
            if (currentVolume <= 0) {
                val fallback = (max / 2).coerceIn(1, max)
                return SpeakerMuteTransition(SpeakerMuteState(false, 0), fallback)
            }
            return SpeakerMuteTransition(SpeakerMuteState(true, currentVolume.coerceIn(1, max)), 0)
        }
        if (currentVolume != 0) {
            return SpeakerMuteTransition(SpeakerMuteState(false, 0), null)
        }
        return SpeakerMuteTransition(
            SpeakerMuteState(false, 0),
            state.savedVolume.coerceIn(1, max)
        )
    }

    /** Service is being destroyed while it may own the mute. */
    fun onDestroy(
        state: SpeakerMuteState,
        currentVolume: Int,
        maxVolume: Int
    ): SpeakerMuteTransition {
        if (!state.owned || currentVolume != 0) {
            return SpeakerMuteTransition(SpeakerMuteState(false, 0), null)
        }
        return SpeakerMuteTransition(
            SpeakerMuteState(false, 0),
            state.savedVolume.coerceIn(1, maxVolume.coerceAtLeast(1))
        )
    }
}
