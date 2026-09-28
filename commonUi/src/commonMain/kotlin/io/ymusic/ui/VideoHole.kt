package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
 * to the hole (fullscreen toggle). Black shows in the unused region; on [desktopLayoutHole] it is
 * only the bars, never the picture tile (that tile is glass over the native Frame).
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
    BoxWithConstraints(
        modifier.then(if (desktopLayoutHole) Modifier else Modifier.background(Color.Black)),
        contentAlignment = Alignment.Center,
    ) {
        val unbounded = maxHeight == Dp.Infinity || maxHeight == Dp.Unspecified
        val hole = if (fill) {
            Modifier.fillMaxSize()
        } else {
            val parentRatio =
                if (!unbounded && maxHeight > 0.dp) maxWidth / maxHeight else ratio
            if (unbounded || parentRatio <= ratio) {
                Modifier.fillMaxWidth().aspectRatio(ratio)
            } else {
                Modifier.fillMaxHeight().aspectRatio(ratio)
            }
        }
        if (desktopLayoutHole && !fill && !unbounded && maxWidth > 0.dp && maxHeight > 0.dp) {
            val parentRatio = maxWidth / maxHeight
            if (parentRatio > ratio) {
                val barW = ((maxWidth - maxHeight * ratio) / 2f).coerceAtLeast(0.dp)
                if (barW > 0.dp) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(barW).background(Color.Black))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(barW).background(Color.Black))
                }
            } else if (parentRatio < ratio) {
                val barH = ((maxHeight - maxWidth / ratio) / 2f).coerceAtLeast(0.dp)
                if (barH > 0.dp) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(barH).background(Color.Black))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barH).background(Color.Black))
                }
            }
        }
        surface(hole)
    }
}
