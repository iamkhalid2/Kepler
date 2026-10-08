package app.betterbooks.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalBbPalette = staticCompositionLocalOf { bbPalette(BbSeed.Cobalt.color, dark = true) }

object Bb {
    val palette: BbPalette
        @Composable @ReadOnlyComposable get() = LocalBbPalette.current
}

@Composable
fun BbTheme(palette: BbPalette, content: @Composable () -> Unit) {
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            background = palette.bg, onBackground = palette.text,
            surface = palette.surface1, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.text2,
            outline = palette.hairline, secondary = palette.secondary,
        )
    } else {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.onAccent,
            background = palette.bg, onBackground = palette.text,
            surface = palette.surface1, onSurface = palette.text,
            surfaceVariant = palette.surface2, onSurfaceVariant = palette.text2,
            outline = palette.hairline, secondary = palette.secondary,
        )
    }
    CompositionLocalProvider(LocalBbPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
