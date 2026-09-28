package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ymusic.player.Player

/**
 * Letterbox [surface] inside [modifier] using the playing picture's aspect ratio. [fill] stretches
 * to the hole (fullscreen toggle). Black shows in the unused region.
 */
@Composable
internal fun VideoHole(
    player: Player,
    surface: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val status by player.status.collectAsState()
    val ratio = status.videoSize?.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val hole = if (fill) {
            Modifier.fillMaxSize()
        } else {
            val unbounded = maxHeight == Dp.Infinity || maxHeight == Dp.Unspecified
            val parentRatio =
                if (!unbounded && maxHeight > 0.dp) maxWidth / maxHeight else ratio
            if (unbounded || parentRatio <= ratio) {
                Modifier.fillMaxWidth().aspectRatio(ratio)
            } else {
                Modifier.fillMaxHeight().aspectRatio(ratio)
            }
        }
        surface(hole)
    }
}
