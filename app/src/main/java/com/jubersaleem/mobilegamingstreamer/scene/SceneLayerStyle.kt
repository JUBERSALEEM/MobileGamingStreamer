package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H7 pure style contract shared by the renderer and the editor preview:
 * style padding and styled sizing. Both sides call these functions with
 * spec fields, so a styled box measures identically in GL measurement
 * and preview measurement (placement then flows through the same H4
 * `resolve`, and both draw back-to-front in the fixed order shadow →
 * background → border → content).
 *
 * Display geometry only — colors are opaque ARGB Ints carried through.
 */
internal const val MAX_STYLE_BORDER_PX = 64
internal const val MAX_STYLE_RADIUS_PX = 256
internal const val MAX_STYLE_SHADOW_PX = 64
internal val TEXT_COLOR_DEFAULT = 0xFFFFFFFF.toInt()
internal val BORDER_COLOR_DEFAULT = 0xFFFFFFFF.toInt()
internal val SHADOW_COLOR_DEFAULT = 0x99000000.toInt()

/** Extra pixels style adds around content on each side. */
internal data class StylePaddingPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal object SceneLayerStyle {
    /**
     * Padding for a border + offset shadow: the border frames all sides,
     * the shadow extends right/bottom only.
     */
    fun paddingPx(borderWidthPx: Int, shadowOffsetPx: Int): StylePaddingPx =
        StylePaddingPx(
            left = borderWidthPx,
            top = borderWidthPx,
            right = borderWidthPx + shadowOffsetPx,
            bottom = borderWidthPx + shadowOffsetPx
        )

    /**
     * Content size plus style padding. Long math (no overflow); null on
     * invalid content, negative widths, or non-positive/overflowing
     * results.
     */
    fun styledSize(
        content: SceneSizePx,
        borderWidthPx: Int,
        shadowOffsetPx: Int
    ): SceneSizePx? {
        if (content.widthPx <= 0 || content.heightPx <= 0) return null
        if (borderWidthPx < 0 || shadowOffsetPx < 0) return null
        val width = content.widthPx.toLong() + borderWidthPx * 2L + shadowOffsetPx
        val height = content.heightPx.toLong() + borderWidthPx * 2L + shadowOffsetPx
        if (width <= 0 || height <= 0) return null
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return SceneSizePx(width.toInt(), height.toInt())
    }
}
