package com.jubersaleem.mobilegamingstreamer.capture

/** Encoder profile selected for the live screen-capture pipeline. */
internal data class VideoProfile(
    val quality: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int
) {
    val label: String get() = "$quality @ ${fps}fps"
}

/**
 * Builds deterministic hardware-encoder fallbacks.
 *
 * For gameplay, preserve the requested frame rate before reducing it: if 1440p60
 * is unsupported, try 1080p60 and 720p60 before falling back to 30 FPS.
 */
internal object VideoProfileSelector {
    fun build(quality: String, requestedFps: Int, portrait: Boolean): List<VideoProfile> {
        val qualityOrder = when (quality) {
            "1440p" -> listOf("1440p", "1080p", "720p")
            "720p" -> listOf("720p")
            else -> listOf("1080p", "720p")
        }
        val targetFps = if (requestedFps >= 60) 60 else 30
        val fpsOrder = if (targetFps == 60) listOf(60, 30) else listOf(30)
        return buildList {
            // Prefer the selected quality/FPS, then lower resolutions at the same FPS,
            // and only then reduce FPS at each supported resolution.
            fpsOrder.forEach { candidateFps ->
                qualityOrder.forEach { candidateQuality ->
                    val longEdge = when (candidateQuality) {
                        "720p" -> 1280
                        "1440p" -> 2560
                        else -> 1920
                    }
                    val shortEdge = ((longEdge * 9f / 16f).toInt() / 2) * 2
                    val width = if (portrait) shortEdge else longEdge
                    val height = if (portrait) longEdge else shortEdge
                    val bitrate = when (candidateQuality) {
                        "720p" -> if (candidateFps == 60) 6_000_000 else 4_000_000
                        "1440p" -> if (candidateFps == 60) 18_000_000 else 14_000_000
                        else -> if (candidateFps == 60) 12_000_000 else 8_000_000
                    }
                    add(VideoProfile(candidateQuality, candidateFps, width, height, bitrate))
                }
            }
        }
    }
}
