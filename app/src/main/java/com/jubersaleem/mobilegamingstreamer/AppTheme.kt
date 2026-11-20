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
 * Mobile Gaming Streamer ships with a single claymorphism design language.
 *
 * Every page, menu, dialog and overlay surface is sculpted from the tokens
 * below: a soft lilac page, raised surfaces that are lit from the top-left and
 * recessed wells for every input. There is no second "classic" skin any more,
 * so the whole product reads as one continuous piece of clay.
 */
data class MgsPalette(
    val background: Color,
    val surface: Color,
    val surfaceDeep: Color,
    val highlight: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val primaryDeep: Color,
    val secondary: Color,
    val accent: Color,
    val danger: Color,
    val edge: Color,
    val clayShadow: Color,
    val clayEdge: Color,
    val inset: Color,
    val onPrimary: Color
)

/** The single clay palette used by the whole app. */
val ClayPalette = MgsPalette(
    background = Color(0xFFE9E5F2),
    surface = Color(0xFFF4F1FB),
    surfaceDeep = Color(0xFFE9E5F3),
    highlight = Color(0xFFFFFFFF),
    text = Color(0xFF26233A),
    muted = Color(0xFF6B6781),
    primary = Color(0xFFE4736B),
    primaryDeep = Color(0xFFC9574E),
    secondary = Color(0xFF7A6AB8),
    accent = Color(0xFF4E9E7C),
    danger = Color(0xFFD9535F),
    edge = Color(0xFFFFFFFF),
    clayShadow = Color(0xFFB3ADC6),
    clayEdge = Color(0xFFD3CDE3),
    inset = Color(0xFFE7E3F1),
    onPrimary = Color(0xFFFFFFFF)
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
        primaryContainer = p.primary.copy(alpha = 0.16f),
        onPrimaryContainer = p.primaryDeep,
        secondary = p.secondary,
        onSecondary = Color.White,
        secondaryContainer = p.secondary.copy(alpha = 0.16f),
        onSecondaryContainer = p.secondary,
        tertiary = p.accent,
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
        scrim = Color(0x6626233A)
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
 * Raised clay surface: a dark shadow cast to the bottom-right, a white
 * highlight to the top-left, a soft vertical gradient and a sculpted rim.
 */
@Composable
fun clayRaisedModifier(
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    depth: ClayDepth = ClayDepth.Medium
): Modifier {
    val p = LocalMgsPalette.current
    val radius = when (depth) {
        ClayDepth.Subtle -> 12.dp
        ClayDepth.Medium -> 18.dp
        ClayDepth.Deep -> 24.dp
    }
    val offset = when (depth) {
        ClayDepth.Subtle -> DpOffset(4.dp, 5.dp)
        ClayDepth.Medium -> DpOffset(7.dp, 8.dp)
        ClayDepth.Deep -> DpOffset(10.dp, 11.dp)
    }
    val alpha = when (depth) {
        ClayDepth.Subtle -> 0.45f
        ClayDepth.Medium -> 0.55f
        ClayDepth.Deep -> 0.62f
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
                color = Color.White.copy(alpha = 0.95f),
                offset = DpOffset(-offset.x, -offset.y)
            )
        )
        .clip(shape)
        .background(
            Brush.linearGradient(listOf(p.highlight, p.surface, p.surfaceDeep)),
            shape
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White, p.clayEdge, Color.White)),
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
