package io.ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small icon action: the glyph centered in a [hitSize] circular hover/press target. Interactive
 * icons never take clicks on their glyph bounds alone — [hitSize] keeps the target ≥32dp (24dp is
 * the floor for the tightest spots).
 */
@Composable
fun ActionIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    hitSize: Dp = 32.dp,
    tint: Color = DesignColors.InkMuted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionIconBox(modifier, hitSize, enabled, onClick) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
fun ActionIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    hitSize: Dp = 32.dp,
    tint: Color = DesignColors.InkMuted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ActionIconBox(modifier, hitSize, enabled, onClick) {
        Icon(painter, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}

@Composable
private fun ActionIconBox(
    modifier: Modifier,
    hitSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(hitSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
