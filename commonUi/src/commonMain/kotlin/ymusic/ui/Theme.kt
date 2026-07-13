package ymusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The wireframe design language (design/final/…html): white surfaces, near-black text (#333),
 * gray secondary (#8a8a8a), light-gray placeholders (#E3E3E3), the app's orange accent.
 */
object DesignColors {
    val Accent = Color(0xFFE8732A)
    val Ink = Color(0xFF333333)
    val InkSecondary = Color(0xFF8A8A8A)
    val Placeholder = Color(0xFFE3E3E3)
    val Outline = Color(0xFFCFCFCF)
    val Surface = Color.White
    val SurfaceDim = Color(0xFFFAFAFA)
    val BadgeScrim = Color(0xCC333333)
    val BrandGreen = Color(0xFF1DB954)
}

private val LightScheme = lightColorScheme(
    primary = DesignColors.Accent,
    onPrimary = Color.White,
    secondary = DesignColors.Ink,
    onSecondary = Color.White,
    tertiary = DesignColors.BrandGreen,
    background = DesignColors.Surface,
    onBackground = DesignColors.Ink,
    surface = DesignColors.Surface,
    onSurface = DesignColors.Ink,
    surfaceVariant = DesignColors.Placeholder,
    onSurfaceVariant = DesignColors.InkSecondary,
    primaryContainer = Color(0xFFFBE9DD),
    onPrimaryContainer = Color(0xFF8A3D0F),
    secondaryContainer = Color(0xFFF0F1F0),
    onSecondaryContainer = DesignColors.Ink,
    surfaceContainer = DesignColors.SurfaceDim,
    surfaceContainerLow = DesignColors.SurfaceDim,
    surfaceContainerHighest = DesignColors.Placeholder,
    outline = DesignColors.Outline,
    outlineVariant = Color(0xFFE6E6E6),
    scrim = DesignColors.BadgeScrim,
)

@Composable
fun YMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightScheme, content = content)
}
