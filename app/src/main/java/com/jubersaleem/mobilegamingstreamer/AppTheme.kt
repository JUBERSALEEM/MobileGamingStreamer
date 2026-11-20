package com.jubersaleem.mobilegamingstreamer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

data class MgsPalette(
    val background: Color,
    val surface: Color,
    val highlight: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val secondary: Color,
    val edge: Color,
    val clayShadow: Color,
    val clayEdge: Color,
    val inset: Color,
    val isClay: Boolean = false
)

val LocalMgsPalette = staticCompositionLocalOf {
    MgsPalette(
        background = Color(0xFF080C11), surface = Color(0xFF121A23),
        highlight = Color(0xFF1C3035), text = Color.White,
        muted = Color(0xFF91A0AE), primary = Color(0xFFC4FF5C),
        secondary = Color(0xFF63D8E8), edge = Color(0xFF263541),
        clayShadow = Color(0xFF000000), clayEdge = Color(0xFF263541),
        inset = Color(0xFF0B1118)
    )
}

@Composable
fun MgsTheme(mode: String, content: @Composable () -> Unit) {
    val palette = if (mode == "CLAY") {
        MgsPalette(
            background = Color(0xFFE9E6F2),
            surface = Color(0xFFF3F0FA),
            highlight = Color(0xFFFFFFFF),
            text = Color(0xFF25243A),
            muted = Color(0xFF66647D),
            primary = Color(0xFFE77F78),
            secondary = Color(0xFF7666B5),
            edge = Color(0xFFFFFFFF),
            clayShadow = Color(0xFFB6B0C8),
            clayEdge = Color(0xFFD5D0E2),
            inset = Color(0xFFE4E0EF),
            isClay = true
        )
    } else {
        LocalMgsPalette.current
    }
    val scheme = if (mode == "CLAY") {
        lightColorScheme(
            primary = palette.primary, onPrimary = Color.White,
            secondary = palette.secondary, onSecondary = Color.White,
            background = palette.background, surface = palette.surface,
            onBackground = palette.text, onSurface = palette.text,
            outline = Color(0xFFBDB5A9)
        )
    } else {
        darkColorScheme(
            primary = palette.primary, onPrimary = Color(0xFF11170A),
            secondary = palette.secondary, background = palette.background,
            surface = palette.surface, onBackground = palette.text,
            onSurface = palette.text, outline = palette.edge
        )
    }
    CompositionLocalProvider(LocalMgsPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = if (mode == "CLAY") Shapes(
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(32.dp)
            ) else Shapes(),
            content = content
        )
    }
}

/** Shared raised surface with opposing shadows, layered lighting and a sculpted rim. */
@Composable
fun MgsCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    border: BorderStroke? = null,
    elevation: CardElevation? = null,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalMgsPalette.current
    if (!p.isClay) {
        Card(
            modifier = modifier,
            shape = shape,
            border = border,
            elevation = elevation ?: CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = colors ?: CardDefaults.cardColors(containerColor = p.surface),
            content = content
        )
    } else {
        Box(
            modifier
                .dropShadow(
                    shape,
                    Shadow(
                        radius = 22.dp,
                        color = p.clayShadow.copy(alpha = 0.58f),
                        offset = DpOffset(9.dp, 10.dp)
                    )
                )
                .dropShadow(
                    shape,
                    Shadow(
                        radius = 22.dp,
                        color = Color.White.copy(alpha = 0.96f),
                        offset = DpOffset((-7).dp, (-7).dp)
                    )
                )
                .clip(shape)
                .background(
                    Brush.linearGradient(listOf(p.highlight, p.surface, p.inset.copy(alpha = 0.55f))),
                    shape
                )
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color.White, p.edge, p.clayEdge)),
                    shape
                )
                .padding(1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(
                        Brush.linearGradient(listOf(p.highlight.copy(alpha = 0.9f), p.surface, p.surface)),
                        shape
                    ),
                content = content
            )
        }
    }
}


/** Raised clay surface for persistent navigation and tactile containers. */
@Composable
fun clayRaisedModifier(shape: RoundedCornerShape = RoundedCornerShape(24.dp)): Modifier {
    val p = LocalMgsPalette.current
    return if (!p.isClay) Modifier else Modifier
        .dropShadow(shape, Shadow(radius = 17.dp, color = p.clayShadow.copy(alpha = 0.62f), offset = DpOffset(7.dp, 8.dp)))
        .dropShadow(shape, Shadow(radius = 15.dp, color = Color.White.copy(alpha = 0.96f), offset = DpOffset((-6).dp, (-6).dp)))
        .clip(shape)
        .background(Brush.linearGradient(listOf(p.highlight, p.surface, p.inset.copy(alpha = 0.42f))), shape)
        .border(1.dp, Brush.linearGradient(listOf(Color.White, p.clayEdge, Color.White)), shape)
}

/** Recessed surface for text fields and segmented controls. */
@Composable
fun clayInsetModifier(shape: RoundedCornerShape = RoundedCornerShape(16.dp)): Modifier {
    val p = LocalMgsPalette.current
    return if (!p.isClay) Modifier else Modifier
        .clip(shape)
        .background(p.inset, shape)
        .innerShadow(
            shape,
            Shadow(
                radius = 10.dp,
                color = p.clayShadow.copy(alpha = 0.48f),
                offset = DpOffset(5.dp, 5.dp)
            )
        )
        .innerShadow(
            shape,
            Shadow(
                radius = 9.dp,
                color = Color.White.copy(alpha = 0.88f),
                offset = DpOffset((-5).dp, (-5).dp)
            )
        )
        .border(1.dp, p.clayEdge.copy(alpha = 0.7f), shape)
}

/** Raised primary action with a tactile pressed state in Clay mode. */
@Composable
fun MgsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit
) {
    val p = LocalMgsPalette.current
    val buttonShape = RoundedCornerShape(if (p.isClay) 22.dp else 14.dp)
    val tactileModifier = if (p.isClay) {
        modifier
            .dropShadow(buttonShape, Shadow(radius = 13.dp, color = p.clayShadow.copy(alpha = 0.72f), offset = DpOffset(6.dp, 7.dp)))
            .dropShadow(buttonShape, Shadow(radius = 12.dp, color = Color.White.copy(alpha = 0.98f), offset = DpOffset((-5).dp, (-5).dp)))
            .border(1.dp, Brush.linearGradient(listOf(Color.White, p.clayEdge, Color.White)), buttonShape)
    } else modifier
    Button(
        onClick = onClick,
        modifier = tactileModifier,
        enabled = enabled,
        shape = buttonShape,
        colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = p.primary,
            contentColor = if (p.isClay) Color.White else Color(0xFF11170A)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        content = content
    )
}

/** Compatibility wrapper for the earlier ClayPanel API. */
@Composable
fun ClayPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(32.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    MgsCard(modifier = modifier, shape = shape, content = content)
}
