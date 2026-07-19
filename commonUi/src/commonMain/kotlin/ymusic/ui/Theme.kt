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
 * The spacing scale: every layout spacing (margins, gaps, paddings between elements) is one of
 * these steps — component-internal geometry (pill insets, badge chrome, the 5dp stack tab) is
 * shape, not rhythm, and stays free. Feed layout bands live in `feedLayoutFor`: `<400dp` square
 * list, `400–469` 16:9 list, `≥470` grid — the fewest columns keeping cards ≤324dp wide
 * (`gridColumnsFor`; 3 columns from 690dp).
 */
object DesignSpacing {
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space48 = 48.dp

    /** Window edge margin, compact/medium windows. */
    val MarginCompact = Space16

    /** Window edge margin, expanded (`≥840dp`) windows. */
    val MarginExpanded = Space24

    /** Between grid cards (expanded windows; mobile-class uses [Space8]). */
    val Gutter = Space16
}

/**
 * Per-window-class grid metrics. [inset] is each card's hover/ripple breathing room — half the
 * gutter, so cards sit exactly one gutter apart with zero structural row spacing (callers lay rows
 * with `margin - inset` edges and `gutter - 2×inset` gaps).
 */
class GridMetrics(val margin: androidx.compose.ui.unit.Dp, val gutter: androidx.compose.ui.unit.Dp) {
    val inset = gutter / 2
}

fun gridMetricsFor(windowWidth: androidx.compose.ui.unit.Dp): GridMetrics {
    val expanded = windowWidth >= 840.dp
    return GridMetrics(
        margin = if (expanded) DesignSpacing.MarginExpanded else DesignSpacing.MarginCompact,
        gutter = if (expanded) DesignSpacing.Gutter else DesignSpacing.Space8,
    )
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
