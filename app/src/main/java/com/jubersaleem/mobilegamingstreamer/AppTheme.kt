package com.jubersaleem.mobilegamingstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Mobile Gaming Streamer ships with a single premium claymorphism design
 * language: deep violet brand identity, electric-violet primary actions,
 * teal reserved for live/audio/technical status, restrained coral for
 * important secondary actions, graphite text on cool neutral surfaces, and
 * explicit semantic colors. Every product color lives in [MgsPalette] — no
 * screen or component may scatter its own brand hex values.
 */
data class MgsPalette(
    // Cool neutral surfaces.
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceDeep: Color,
    val elevated: Color,
    val inset: Color,
    val highlight: Color,
    // Graphite text.
    val text: Color,
    val muted: Color,
    val disabled: Color,
    // Electric-violet primary actions.
    val primary: Color,
    val primaryDeep: Color,
    val primaryInk: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    // Restrained coral secondary actions.
    val secondary: Color,
    val secondaryDeep: Color,
    val secondaryInk: Color,
    // Teal live / audio / technical accent.
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    // Semantic states.
    val success: Color,
    val successDeep: Color,
    val successSoft: Color,
    val warning: Color,
    val warningDeep: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerDeep: Color,
    val dangerSoft: Color,
    // Sculpted clay material.
    val edge: Color,
    val clayShadow: Color,
    val clayEdge: Color
)

/** The single clay palette used by the whole app. */
val ClayPalette = MgsPalette(
    background = Color(0xFFE2E4EE),
    backgroundDeep = Color(0xFFD2D5E4),
    surface = Color(0xFFF4F5FA),
    surfaceDeep = Color(0xFFDFE2EC),
    elevated = Color(0xFFFCFCFE),
    inset = Color(0xFFD7DBE8),
    highlight = Color(0xFFFFFFFF),
    text = Color(0xFF23232E),
    muted = Color(0xFF5D5A73),
    disabled = Color(0xFFA9A5BE),
    primary = Color(0xFF5B3DF5),
    primaryDeep = Color(0xFF3D2AB8),
    primaryInk = Color(0xFF4330B8),
    primarySoft = Color(0xFFE2DBFD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFD95F45),
    secondaryDeep = Color(0xFFB84A35),
    secondaryInk = Color(0xFFA03A29),
    accent = Color(0xFF0097A7),
    accentDeep = Color(0xFF0B7280),
    accentSoft = Color(0xFFD5EFF3),
    success = Color(0xFF2E9E63),
    successDeep = Color(0xFF1F7350),
    successSoft = Color(0xFFD8EFDF),
    warning = Color(0xFFBE7A12),
    warningDeep = Color(0xFF8A570A),
    warningSoft = Color(0xFFF6EACD),
    danger = Color(0xFFD1344C),
    dangerDeep = Color(0xFFA11F36),
    dangerSoft = Color(0xFFF9DCE1),
    edge = Color(0xFFFFFFFF),
    clayShadow = Color(0xFF99A0BB),
    clayEdge = Color(0xFFC2C8DC)
)

val LocalMgsPalette = staticCompositionLocalOf { ClayPalette }

/** Depth presets for raised clay surfaces. */
enum class ClayDepth { Subtle, Medium, Deep }

@Composable
fun MgsTheme(content: @Composable () -> Unit) {
    val p = ClayPalette
    val scheme = lightColorScheme(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.primarySoft,
        onPrimaryContainer = p.primaryInk,
        secondary = p.accent,
        onSecondary = Color.White,
        secondaryContainer = p.accentSoft,
        onSecondaryContainer = p.accentDeep,
        tertiary = p.secondary,
        onTertiary = Color.White,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surfaceDeep,
        onSurfaceVariant = p.muted,
        outline = p.clayEdge,
        outlineVariant = p.clayEdge,
        error = p.danger,
        onError = Color.White,
        scrim = Color(0x661E1B2E)
    )
    CompositionLocalProvider(LocalMgsPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(22.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(34.dp)
            ),
            content = content
        )
    }
}

/**
 * Raised clay surface: a cool ambient shadow cast to the bottom-right, a
 * white highlight to the top-left, a restrained vertical gradient and a
 * sculpted rim. Depth presets keep every card in the same material family
 * without muddy over-shadowing.
 */
@Composable
fun clayRaisedModifier(
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    depth: ClayDepth = ClayDepth.Medium
): Modifier {
    val p = LocalMgsPalette.current
    val radius = when (depth) {
        ClayDepth.Subtle -> 10.dp
        ClayDepth.Medium -> 16.dp
        ClayDepth.Deep -> 22.dp
    }
    val offset = when (depth) {
        ClayDepth.Subtle -> DpOffset(3.dp, 4.dp)
        ClayDepth.Medium -> DpOffset(6.dp, 7.dp)
        ClayDepth.Deep -> DpOffset(9.dp, 10.dp)
    }
    val alpha = when (depth) {
        ClayDepth.Subtle -> 0.40f
        ClayDepth.Medium -> 0.46f
        ClayDepth.Deep -> 0.52f
    }
    return Modifier
        .dropShadow(
            shape,
            Shadow(radius = radius, color = p.clayShadow.copy(alpha = alpha), offset = offset)
        )
        .dropShadow(
            shape,
            Shadow(
                radius = radius - 2.dp,
                color = Color.White.copy(alpha = 0.92f),
                offset = DpOffset(-offset.x, -offset.y)
            )
        )
        .clip(shape)
        .background(
            Brush.linearGradient(listOf(p.elevated, p.surface, p.surfaceDeep)),
            shape
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(p.edge, p.clayEdge, p.edge)),
            shape
        )
}

/** Recessed clay well used for text fields, segmented controls and stat tiles. */
@Composable
fun clayInsetModifier(
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    rim: Boolean = true
): Modifier {
    val p = LocalMgsPalette.current
    val well = Modifier
        .clip(shape)
        .background(p.inset, shape)
        .innerShadow(
            shape,
            Shadow(
                radius = 10.dp,
                color = p.clayShadow.copy(alpha = 0.5f),
                offset = DpOffset(4.dp, 4.dp)
            )
        )
        .innerShadow(
            shape,
            Shadow(
                radius = 9.dp,
                color = Color.White.copy(alpha = 0.9f),
                offset = DpOffset((-4).dp, (-4).dp)
            )
        )
    return if (rim) {
        well.border(1.dp, p.clayEdge.copy(alpha = 0.75f), shape)
    } else {
        well
    }
}

/** The standard raised clay card used by every page and menu. */
@Composable
fun ClaySurface(
    modifier: Modifier = Modifier.fillMaxWidth(),
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    depth: ClayDepth = ClayDepth.Medium,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.then(clayRaisedModifier(shape, depth)),
        content = content
    )
}
