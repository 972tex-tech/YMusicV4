package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.ymusic.imageloader.AsyncImage
import io.ymusic.player.isPlaying
import io.ymusic.ui.AppGraph
import io.ymusic.ui.SlimSeekBar
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.badge_live
import io.ymusic.ui.skipSegmentFractions
import org.jetbrains.compose.resources.stringResource
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * The tray's notification-style popup: a small always-on-top card near the tray corner with the
 * current media info and transport — art, title/artist, prev / play-pause / next, a seek bar,
 * "open the app", and ✕ = [onDismiss] (end the listening session: clear the queue — which removes
 * the tray — and exit the app when no window is left). Dismisses itself when it loses focus
 * (click-away), like a system notification.
 */
@Composable
fun ApplicationScope.MiniPlayerWindow(
    graph: AppGraph,
    anchorX: Int? = null,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val screen = remember { Toolkit.getDefaultToolkit().screenSize }
    // Centered under the tray icon when the click position is known, else the tray corner.
    val x = (anchorX?.let { it - WIDTH_DP / 2 } ?: (screen.width - WIDTH_DP - 16))
        .coerceIn(8, screen.width - WIDTH_DP - 8)
    Window(
        onCloseRequest = onClose,
        title = "YMusic",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        state = rememberWindowState(
            position = WindowPosition(x.dp, 44.dp),
            size = DpSize(WIDTH_DP.dp, HEIGHT_DP.dp),
        ),
    ) {
        DisposableEffect(Unit) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) = Unit

                override fun windowLostFocus(e: WindowEvent) = onClose()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            MiniPlayerContent(graph, onOpenApp, onDismiss)
        }
    }
}

@Composable
private fun MiniPlayerContent(graph: AppGraph, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
    val player = graph.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val skipSegments by player.skipSegments.collectAsState()
    val item = queue.currentItem
    val playing = status.state.isPlaying()

    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val loader = graph.imageLoader
                val art = item?.displayThumbnailUrl
                if (loader != null && art != null) {
                    AsyncImage(
                        loader,
                        art,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    item?.displayTitle ?: "Nothing playing",
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item?.displaySubtitle?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenApp, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open YMusic", Modifier.size(16.dp))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Close, "Stop and dismiss", Modifier.size(16.dp))
            }
        }

        Spacer(Modifier.height(6.dp))

        val durationMs = progress.durationMs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.progressLabel.orEmpty(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SlimSeekBar(
                fraction = if (durationMs > 0) (progress.positionMs.toFloat() / durationMs) else 0f,
                enabled = progress.seekable && durationMs > 0,
                onSeek = { f -> player.seekTo((f * durationMs).toLong()) },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                skipFractions = skipSegmentFractions(skipSegments, durationMs),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                fillColor = MaterialTheme.colorScheme.primary,
                thumbColor = MaterialTheme.colorScheme.primary,
                thumbSize = 10.dp,
                barHeight = 22.dp,
                thumbShadow = 0.dp,
            )
            Text(
                if (progress.durationIsLive) {
                    stringResource(Res.string.badge_live)
                } else {
                    progress.durationLabel ?: "--:--"
                },
                fontSize = 10.sp,
                color = if (progress.highlightDuration) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = if (progress.durationIsLive && progress.seekable) {
                    Modifier.clickable { player.seekTo(durationMs.coerceAtLeast(0L)) }
                } else {
                    Modifier
                },
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { player.skipPrev() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(20.dp))
            }
            IconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    Modifier.size(26.dp),
                )
            }
            IconButton(onClick = { player.skipNext() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.SkipNext, "Next", Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The tray icon, managed straight through AWT — NOT the compose `Tray`, which always attaches a
 * popup menu that macOS then opens on click instead of firing the action. Bare TrayIcon + mouse
 * listener = a plain click reaches [onClick] (with the click's screen x, to anchor the mini
 * player under the icon).
 *
 * The icon renders at 1x AND 2x into a multi-resolution image: a TrayIcon handed a single 1x
 * raster is upscaled by the OS on Retina and turns soft, no matter how good the source is.
 */
@Composable
fun PlayerTray(icon: Painter, onClick: (screenX: Int) -> Unit) {
    val density = LocalDensity.current
    val currentOnClick by rememberUpdatedState(onClick)
    DisposableEffect(icon) {
        val tray = if (SystemTray.isSupported()) SystemTray.getSystemTray() else null
        val trayIcon = tray?.let {
            val size = it.trayIconSize
            fun render(scale: Int) = icon.toAwtImage(
                density,
                LayoutDirection.Ltr,
                Size(size.width.toFloat() * scale, size.height.toFloat() * scale),
            )
            val image = if (isLinux) {
                // X11's tray peer paints transparent pixels in its own (white) background, so the
                // disc is scaled to cover the whole cell and the corners cropped away.
                val bleed = kotlin.math.ceil(size.width * kotlin.math.sqrt(2.0)).toInt()
                val inset = (bleed - size.width) / 2
                val disc = icon.toAwtImage(density, LayoutDirection.Ltr, Size(bleed.toFloat(), bleed.toFloat()))
                java.awt.image.BufferedImage(size.width, size.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().apply {
                        drawImage(disc, -inset, -inset, null)
                        dispose()
                    }
                }
            } else {
                java.awt.image.BaseMultiResolutionImage(render(1), render(2))
            }
            TrayIcon(image, "YMusic").apply {
                addMouseListener(
                    object : MouseAdapter() {
                        // TrayIcon events carry SCREEN coordinates (there is no component space).
                        override fun mousePressed(e: MouseEvent) = currentOnClick(e.x)
                    },
                )
                it.add(this)
            }
        }
        onDispose { trayIcon?.let { t -> tray?.remove(t) } }
    }
}

private val isLinux = "linux" in System.getProperty("os.name").orEmpty().lowercase()

private const val WIDTH_DP = 340
private const val HEIGHT_DP = 132
