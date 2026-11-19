package com.jubersaleem.mobilegamingstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val isClay: Boolean = false
)

val LocalMgsPalette = staticCompositionLocalOf {
    MgsPalette(
        background = Color(0xFF080C11), surface = Color(0xFF121A23),
        highlight = Color(0xFF1C3035), text = Color.White,
        muted = Color(0xFF91A0AE), primary = Color(0xFFC4FF5C),
        secondary = Color(0xFF63D8E8), edge = Color(0xFF263541)
    )
}

@Composable
fun MgsTheme(mode: String, content: @Composable () -> Unit) {
    val palette = if (mode == "CLAY") {
        MgsPalette(
            background = Color(0xFFE3DED4),
            surface = Color(0xFFF1ECE2),
            highlight = Color(0xFFFFFCF5),
            text = Color(0xFF27332F),
            muted = Color(0xFF64736C),
            primary = Color(0xFF2F7968),
            secondary = Color(0xFF8873A5),
            edge = Color(0xFFFFFEF9),
            isClay = true
        )
    } else {
        LocalMgsPalette.current
    }
    val scheme = if (mode == "CLAY") {
        lightColorScheme(
            primary = palette.primary, onPrimary = Color.White,
            secondary = palette.secondary, background = palette.background,
            surface = palette.surface, onBackground = palette.text,
            onSurface = palette.text, outline = Color(0xFFD2D8D0)
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
                small = RoundedCornerShape(18.dp),
                medium = RoundedCornerShape(26.dp),
                large = RoundedCornerShape(34.dp)
            ) else Shapes(),
            content = content
        )
    }
}

@Composable
fun ClayPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(32.dp),
    content: @Composable () -> Unit
) {
    val palette = LocalMgsPalette.current
    Box(
        modifier
            .shadow(26.dp, shape, clip = false)
            .background(
                Brush.linearGradient(
                    listOf(palette.highlight, palette.surface, Color(0xFFE8E2D7))
                ),
                shape
            )
            .border(1.4.dp, Brush.linearGradient(listOf(Color.White, palette.edge, Color(0xFFD2C9BB))), shape)
            .padding(1.dp)
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(listOf(palette.highlight.copy(alpha = 0.72f), palette.surface)),
                    shape
                )
                .border(1.dp, Color.White.copy(alpha = 0.72f), shape)
        ) { content() }
    }
}
