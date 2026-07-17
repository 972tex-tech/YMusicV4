package ymusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The wireframe design language (design/final/…html): white surfaces, near-black text (#333),
 * gray secondary (#8a8a8a), light-gray placeholders (#E3E3E3), the app's orange accent.
 */
object DesignColors {
    val Accent = Color(0xFFE8732A)
    val Ink = Color(0xFF333333)
    val InkMuted = Color(0xFF555555)
    val InkSoft = Color(0xFF666666)
    val InkSecondary = Color(0xFF8A8A8A)
    val InkFaint = Color(0xFF9A9A9A)
    val IconDots = Color(0xFF999999)
    val Placeholder = Color(0xFFE3E3E3)
    val PlaceholderDim = Color(0xFFE0E0E0)
    val PlaceholderIcon = Color(0xFFB3B3B3)
    val StackNear = Color(0xFFECECEC)
    val StackFar = Color(0xFFF3F3F3)
    val StackTab = Color(0xFFEDEDED)
    val Outline = Color(0xFFCFCFCF)
    val OutlineSoft = Color(0xFFD8D8D8)
    val Divider = Color(0xFFE6E6E6)
    val DividerMini = Color(0xFFE2E2E2)
    val Surface = Color.White
    val SurfaceDim = Color(0xFFFAFAFA)
    val BadgeInk = Ink
    val BadgeScrim = Color(0xB8000000)
    val BrandGreen = Color(0xFF1DB954)
}

/**
 * Layout system per M3 grids & spacing (m3.material.io/foundations/layout): window-size-class
 * driven — compact `<600dp` lists, medium `600–839` 2-col, expanded `≥840` 3-col — with the M3
 * margins and gutter. Values sit on the 4dp scale.
 */
object DesignSpacing {
    /** Window edge margin, compact/medium windows. */
    val MarginCompact = 16.dp

    /** Window edge margin, expanded (`≥840dp`) windows. */
    val MarginExpanded = 24.dp

    /** Between grid cards. */
    val Gutter = 16.dp
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
