package io.ymusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The wireframe design language (design/final/…html) as a resolvable palette: the mockups are the
 * light theme (white surfaces, near-black #333 text, gray placeholders, the orange accent); the
 * dark palette mirrors each role on near-black surfaces. Composables read the active palette
 * through [DesignColors] — the call sites look exactly like the old constant object.
 *
 * On-artwork chrome (badge fills/scrims and text over thumbnails or video) is NOT part of the
 * palette split: it sits on imagery, not on the surface, so it keeps fixed colors in both themes
 * ([BadgeInk], [BadgeScrim], and the raw white/dark literals in the player and badge composables).
 */
@Immutable
class DesignPalette(
    val isDark: Boolean,
    val Accent: Color,
    /** Strongest text — big titles (mockup #1A–#22 blacks). */
    val InkStrong: Color,
    val Ink: Color,
    val InkMuted: Color,
    val InkSoft: Color,
    val InkSecondary: Color,
    val InkFaint: Color,
    val IconDots: Color,
    /** Text/icons on an [InkStrong]-filled control (the play button, selected pills). */
    val OnInk: Color,
    /** Text/icons on an [Accent]-filled control (Sign in, Send, Retry). */
    val OnAccent: Color,
    val Placeholder: Color,
    val PlaceholderDim: Color,
    val PlaceholderIcon: Color,
    val StackNear: Color,
    val StackFar: Color,
    val StackTab: Color,
    val Outline: Color,
    val OutlineSoft: Color,
    val Divider: Color,
    val DividerMini: Color,
    val Surface: Color,
    val SurfaceDim: Color,
    val SurfaceElevated: Color,
    /** Settings section card fill — one visible step off [Surface] in both themes. */
    val SettingsCard: Color,
    /** The search input's gray pill fill. */
    val FieldPill: Color,
    val BrandGreen: Color,
) {
    /** On-artwork badge fill — fixed across themes (sits on imagery, not the surface). */
    val BadgeInk = Color(0xFF333333)
    val BadgeScrim = Color(0xB8000000)
    val WatchedTrack = Color(0x66FFFFFF)
    val WatchedFill = Color(0xFFFF0000)
}

val LightDesignColors = DesignPalette(
    isDark = false,
    Accent = Color(0xFFE8732A),
    InkStrong = Color(0xFF222222),
    Ink = Color(0xFF333333),
    InkMuted = Color(0xFF555555),
    InkSoft = Color(0xFF666666),
    InkSecondary = Color(0xFF8A8A8A),
    InkFaint = Color(0xFF9A9A9A),
    IconDots = Color(0xFF999999),
    OnInk = Color.White,
    OnAccent = Color.White,
    Placeholder = Color(0xFFE3E3E3),
    PlaceholderDim = Color(0xFFE0E0E0),
    PlaceholderIcon = Color(0xFFB3B3B3),
    StackNear = Color(0xFFECECEC),
    StackFar = Color(0xFFF3F3F3),
    StackTab = Color(0xFFEDEDED),
    Outline = Color(0xFFCFCFCF),
    OutlineSoft = Color(0xFFD8D8D8),
    Divider = Color(0xFFE6E6E6),
    DividerMini = Color(0xFFE2E2E2),
    Surface = Color.White,
    SurfaceDim = Color(0xFFFAFAFA),
    SurfaceElevated = Color(0xFFF5F5F5),
    SettingsCard = Color(0xFFF2F2F2),
    FieldPill = Color(0xFFF1F1F1),
    BrandGreen = Color(0xFF1DB954),
)

val DarkDesignColors = DesignPalette(
    isDark = true,
    Accent = Color(0xFFE8732A),
    InkStrong = Color(0xFFEDEDED),
    Ink = Color(0xFFE2E2E2),
    InkMuted = Color(0xFFC6C6C6),
    InkSoft = Color(0xFFB2B2B2),
    InkSecondary = Color(0xFF9A9A9A),
    InkFaint = Color(0xFF7E7E7E),
    IconDots = Color(0xFF8E8E8E),
    OnInk = Color(0xFF141414),
    OnAccent = Color.White,
    // The legacy YMusic grounds (#303030 / #343434 / #383838, from the v3 app) and their
    // companions, each keeping roughly the lift it had over the old near-black trio
    // (0xFF121212 / 0xFF171717 / 0xFF1F1F1F): placeholders/stacks/pills sit clearly above the
    // surface, outlines lift further, and dividers stay the legacy way — slightly DARKER lines
    // cut into the grey rather than light hairlines on black.
    Placeholder = Color(0xFF454545),
    PlaceholderDim = Color(0xFF414141),
    PlaceholderIcon = Color(0xFF767676),
    StackNear = Color(0xFF454545),
    StackFar = Color(0xFF3D3D3D),
    StackTab = Color(0xFF424242),
    Outline = Color(0xFF5C5C5C),
    OutlineSoft = Color(0xFF525252),
    Divider = Color(0xFF2A2A2A),
    DividerMini = Color(0xFF2D2D2D),
    Surface = Color(0xFF272727),
    SurfaceDim = Color(0xFF313131),
    SurfaceElevated = Color(0xFF292929),
    SettingsCard = Color(0xFF333333),
    FieldPill = Color(0xFF3F3F3F),
    BrandGreen = Color(0xFF1DB954),
)

val LocalDesignColors = staticCompositionLocalOf { LightDesignColors }

/** The active theme's palette — reads like the old `object DesignColors` at every call site. */
val DesignColors: DesignPalette
    @Composable @ReadOnlyComposable
    get() = LocalDesignColors.current

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

    /** Feed list rows' vertical inset — rows sit 2× this apart. Deliberately off the step scale:
     *  [Space8]'s 16dp rhythm read cramped against 92dp thumbs (review 2026-07-31) and the next
     *  step doubles it, so the row rhythm takes the midpoint. */
    val ListRowVertical = 10.dp

    /** Vertical gap between grid rows, every width class (the compact gutter stays horizontal). */
    val GridRowGap = Space16

    /** Settings row insets inside a section card. Vertical is off the step scale — the rows were tuned
     *  to this rhythm before the scale existed. */
    val SettingsRowHorizontal = Space16
    val SettingsRowVertical = 14.dp

    /** Settings section card: its margin from the screen edge, corner, and the gap above each header. */
    val SettingsCardInset = Space16
    val SettingsCardCorner = 12.dp
    val SettingsSectionGap = Space24

    /** Row insets inside the elevated-bar picker dialog ([ElevatedBarDialog]) — the choice rows align
     *  with the title (24) and take a 12 midpoint vertical, matching the content-region picker. */
    val DialogRowHorizontal = Space24
    val DialogRowVertical = 12.dp

    /** Max width for a single-column form (Feedback) — full-bleed text fields on an expanded
     *  window read as ribbons, so the column caps here and centers; matches [ElevatedBarDialog]'s
     *  560 max so forms and dialogs share one content width. */
    val FormMaxWidth = 560.dp
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

private fun schemeFor(c: DesignPalette) = with(c) {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = Accent,
        onPrimary = OnAccent,
        secondary = Ink,
        onSecondary = OnInk,
        tertiary = BrandGreen,
        background = Surface,
        onBackground = Ink,
        surface = Surface,
        onSurface = Ink,
        surfaceVariant = Placeholder,
        onSurfaceVariant = InkSecondary,
        primaryContainer = if (isDark) Color(0xFF43220E) else Color(0xFFFBE9DD),
        onPrimaryContainer = if (isDark) Color(0xFFF0A36B) else Color(0xFF8A3D0F),
        secondaryContainer = if (isDark) Color(0xFF3F3F3F) else Color(0xFFF0F1F0),
        onSecondaryContainer = Ink,
        surfaceContainer = SurfaceDim,
        surfaceContainerLow = SurfaceDim,
        surfaceContainerHighest = Placeholder,
        outline = Outline,
        outlineVariant = DividerMini,
        scrim = BadgeScrim,
    )
}

private val LightScheme = schemeFor(LightDesignColors)
private val DarkScheme = schemeFor(DarkDesignColors)

@Composable
fun YMusicTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkDesignColors else LightDesignColors
    CompositionLocalProvider(LocalDesignColors provides palette) {
        SyncSystemBars(dark)
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
            // No root Surface sets it, so an uncolored Text would fall back to black in dark theme.
            CompositionLocalProvider(LocalContentColor provides palette.Ink) { AccentRipple(palette.Accent, content) }
        }
    }
}

/** Every hover/press background renders in the accent (at the ripple's own alphas). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AccentRipple(accent: Color, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalRippleConfiguration provides
            androidx.compose.material3.RippleConfiguration(color = accent),
        content = content,
    )
}
