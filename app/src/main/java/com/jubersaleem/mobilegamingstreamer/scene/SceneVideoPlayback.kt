package com.jubersaleem.mobilegamingstreamer.scene

/**
 * J2 pure video-playback policy: states, per-slot controller, decoder
 * budget, and rotation handling. Pure Kotlin: no Android, threads, or
 * I/O. The Android shells (`SceneVideoPlayer`, `SceneCompositorGL`
 * video slots) execute; every decision lives here, unit-tested.
 *
 * A video slot moves NOT_LOADED → READY → PLAYING ⇄ PAUSED, with
 * FAILED sticky until the next rebuild and STOPPED terminal until the
 * next rebuild. Invalid transitions are rejected (false), never throw.
 */

/** Max live decoders per scene. Each decoder holds a codec instance +
 * hardware frame buffers; 2 is safe on modern devices (incl. S25)
 * while extras would risk codec exhaustion mid-stream. */
internal const val MAX_CONCURRENT_VIDEO_LAYERS = 2

/** J2 playback states (editor badge + slot truth share these). */
enum class SceneVideoState {
    NOT_LOADED,
    READY,
    PLAYING,
    PAUSED,
    FAILED,
    STOPPED
}

/**
 * Per-slot playback controller. Owns the transition matrix only; the
 * player shell reports events here and reads the accepted state.
 * Single-thread affinity is the caller's contract (GL thread); the
 * logic itself is synchronous and allocation-free.
 */
internal class SceneVideoPlaybackController {
    var state: SceneVideoState = SceneVideoState.NOT_LOADED
        private set
    var failureReason: String? = null
        private set

    /** Slot claimed a decodable spec (build assigned a decoder). */
    fun onSlotAssigned(): Boolean {
        if (state != SceneVideoState.NOT_LOADED) return false
        state = SceneVideoState.READY
        return true
    }

    /** Decoder prepared (or first frame latched — playing starts). */
    fun onPlaying(): Boolean {
        if (state != SceneVideoState.READY && state != SceneVideoState.PAUSED) return false
        state = SceneVideoState.PLAYING
        return true
    }

    /** Playback paused (revoke freeze; decoder held, last frame kept). */
    fun onPaused(): Boolean {
        if (state != SceneVideoState.PLAYING) return false
        state = SceneVideoState.PAUSED
        return true
    }

    /** Decoder/asset failure. Sticky: only a rebuild clears it. */
    fun onError(reason: String): Boolean {
        if (state == SceneVideoState.FAILED || state == SceneVideoState.STOPPED) return false
        state = SceneVideoState.FAILED
        failureReason = reason
        return true
    }

    /** Slot torn down (rebuild/stop/destroy). Terminal until rebuild. */
    fun onStopped(): Boolean {
        if (state == SceneVideoState.STOPPED) return false
        if (state == SceneVideoState.NOT_LOADED) return false
        state = SceneVideoState.STOPPED
        return true
    }

    /** Rebuild releases every slot: back to unclaimed. */
    fun onRebuild(): Boolean {
        state = SceneVideoState.NOT_LOADED
        failureReason = null
        return true
    }
}

/**
 * Deterministic decoder-budget assignment. Returns the ids (in
 * streamable order) that may start a decoder: the first
 * [MAX_CONCURRENT_VIDEO_LAYERS] enabled VIDEO specs. Pure and total:
 * empty in → empty out, never throws.
 */
internal object SceneVideoBudget {
    fun assign(specs: List<SceneOverlaySpec>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (spec in specs) {
            if (ids.size >= MAX_CONCURRENT_VIDEO_LAYERS) break
            if (spec.enabled && spec.kind == SceneLayerKind.VIDEO) ids.add(spec.id)
        }
        return ids
    }
}

/**
 * Applies container rotation to probed dimensions. MediaPlayer renders
 * frames pre-rotated, so 90/270° swaps the measurement dims; 0/180°
 * and any garbage value keep them. Pure; null on invalid dims.
 */
internal fun applyVideoRotation(widthPx: Int, heightPx: Int, degrees: Int): SceneSizePx? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val turns = ((degrees % 360) + 360) % 360
    return if (turns == 90 || turns == 270) SceneSizePx(heightPx, widthPx)
    else SceneSizePx(widthPx, heightPx)
}
