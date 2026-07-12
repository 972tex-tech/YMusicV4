package ymusic.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ymusic.player.LocalPlayer
import ymusic.player.PlayableItem
import ymusic.player.PlaybackState
import ymusic.player.PlaybackStatus

/** The file-picker seam — each platform supplies one (Desktop: AWT dialog; Android/iOS: SAF/UIDocument). */
fun interface FilePicker {
    /** Returns a `file://` URI or absolute path, or null if the user cancelled. Runs on the UI thread. */
    fun pick(): String?
}

/**
 * The whole playground screen: pick a local file, then drive [player] — play/pause, seek, ±10s,
 * volume — with a live position/state readout. Reused verbatim on any Compose target.
 */
@Composable
fun PlayerScreen(player: LocalPlayer, filePicker: FilePicker, modifier: Modifier = Modifier) {
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    var fileName by remember { mutableStateOf<String?>(null) }
    var audioOnly by remember { mutableStateOf(status.audioOnly) }

    val duration = progress.durationMs.coerceAtLeast(0L)
    val position = progress.positionMs.coerceAtLeast(0L)

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("YMusic · LocalPlayer playground", style = MaterialTheme.typography.titleLarge)

        Button(onClick = {
            val picked = filePicker.pick() ?: return@Button
            fileName = picked.substringAfterLast('/')
            player.load(PlayableItem.LocalUri(picked), playWhenReady = true)
        }) { Text("Open file…") }

        Text(
            fileName ?: "No file loaded",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("State: ${status.state}", style = MaterialTheme.typography.bodyMedium)
        status.error?.let {
            Text("Failed (${it.code})", color = MaterialTheme.colorScheme.error)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Audio-only")
            Switch(
                checked = audioOnly,
                onCheckedChange = {
                    audioOnly = it
                    player.setAudioOnly(it)
                },
            )
            Spacer(Modifier.weight(1f))
            status.videoSize?.let { Text("${it.width}×${it.height}") }
        }

        if (!audioOnly) {
            val ratio = status.videoSize?.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                PlayerSurface(player, Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.height(4.dp))

        Slider(
            value = if (duration > 0L) position.toFloat() / duration else 0f,
            onValueChange = { frac -> if (duration > 0L) player.seekTo((frac * duration).toLong()) },
            enabled = duration > 0L,
        )
        Text("${formatMs(position)} / ${if (duration > 0L) formatMs(duration) else "--:--"}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { player.play() }, enabled = isLoaded(status)) { Text("Play") }
            Button(onClick = { player.pause() }, enabled = isLoaded(status)) { Text("Pause") }
            Button(onClick = { player.seekTo(position - 10_000) }, enabled = isLoaded(status)) { Text("−10s") }
            Button(onClick = { player.seekTo(position + 10_000) }, enabled = isLoaded(status)) { Text("+10s") }
            Button(onClick = { player.stop() }) { Text("Stop") }
        }

        Spacer(Modifier.height(4.dp))
        Text("Volume ${(status.volume * 100).toInt()}%")
        Slider(value = status.volume, onValueChange = { player.setVolume(it) })

        Text("Speed ${formatSpeed(status.speed)}×")
        Slider(
            value = status.speed,
            onValueChange = { player.setSpeed(it) },
            valueRange = 0.5f..2.0f,
        )
    }
}

private fun isLoaded(status: PlaybackStatus): Boolean =
    status.state == PlaybackState.Ready ||
        status.state == PlaybackState.Buffering ||
        status.state == PlaybackState.Ended

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 10).toInt() / 10.0
    return rounded.toString()
}
