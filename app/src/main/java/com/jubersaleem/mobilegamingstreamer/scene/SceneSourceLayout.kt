package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H2 source geometry: pure, unit-tested mirror of the RootEncoder 2.8.1
 * sizing rules our `SceneSource` must reproduce for passthrough parity.
 *
 * - Display/input size mirrors `ScreenSource.start()`: the VD and input
 *   buffer swap width/height when `rotation` is 90/270, else use as-is.
 * - Output size mirrors `StreamBase.prepareVideo()`: the encoder (and hence
 *   the library SurfaceTexture our compositor draws into) swaps on 90/270.
 * - Even-dimension rule mirrors `ScreenSource.create()`, which throws
 *   `IllegalArgumentException` unless both dimensions are divisible by 2.
 * - [isPortraitStream] mirrors `ScreenSource.getOrientationConfig()`.
 *
 * Input and output sizes are equal today (1:1 passthrough) but modeled as
 * separate spaces: future layers may diverge them. Rotation values outside
 * 90/270 are treated as unrotated (mirroring library leniency, which never
 * validates rotation).
 */
internal data class SceneSourceLayout(
    /** VD + compositor input buffer, pixels. */
    val displayWidth: Int,
    val displayHeight: Int,
    /** Library ST viewport our compositor draws into, pixels. */
    val outputWidth: Int,
    val outputHeight: Int
)

internal object SceneSourceLayoutCalculator {
    /**
     * Computes the source layout for `VideoSource.init` dimensions. Returns
     * null (never throws) unless both dimensions are positive and even; the
     * Android shell maps null to the mirrored `IllegalArgumentException`.
     */
    fun layout(width: Int, height: Int, rotation: Int): SceneSourceLayout? {
        if (width <= 0 || height <= 0) return null
        if (width % 2 != 0 || height % 2 != 0) return null
        val shouldRotate = rotation == 90 || rotation == 270
        val majorW = if (shouldRotate) height else width
        val majorH = if (shouldRotate) width else height
        return SceneSourceLayout(
            displayWidth = majorW,
            displayHeight = majorH,
            outputWidth = majorW,
            outputHeight = majorH
        )
    }

    /**
     * Portrait formula mirrored from `ScreenSource.getOrientationConfig()`:
     * `(rotation == 90 || rotation == 270) != (height > width)`.
     */
    fun isPortraitStream(width: Int, height: Int, rotation: Int): Boolean =
        (rotation == 90 || rotation == 270) != (height > width)
}
