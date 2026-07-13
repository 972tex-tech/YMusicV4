package ymusic.playground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import kmphttp.KmpHttpClient
import org.cef.CefClient
import ymusic.player.PlayableItem
import ymusic.player.PlaybackConfig
import ymusic.player.VideoQualityState
import ymusic.player.WebViewPlayer
import ymusic.player.WebViewPlayerContext
import ymusic.player.createWebViewPlayer
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * WebView YouTube check: enter a video id, [WebViewPlayer.load] it, drive transport. The player fills
 * the [JPanel] we hand it (container-injection) and a `SwingPanel` shows that panel — the WebView is
 * the video surface.
 */
@Composable
fun WebViewPlayerScreen(
    cefClient: CefClient,
    engineJs: String,
    httpClient: KmpHttpClient,
    modifier: Modifier = Modifier,
) {
    val container = remember { JPanel(BorderLayout()) }
    val player: WebViewPlayer = remember {
        createWebViewPlayer(WebViewPlayerContext(cefClient, container), engineJs, appVersion = 1, httpClient = httpClient)
    }
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    var videoId by remember { mutableStateOf("dQw4w9WgXcQ") }
    // Video harness → start with video enabled (not the audio-only default).
    var config by remember { mutableStateOf(PlaybackConfig(preferVideoQuality = VideoQualityState.AUTO to 0L)) }
    val audioOnly = config.audioOnly

    DisposableEffect(Unit) { onDispose { player.release() } }

    val duration = progress.durationMs.coerceAtLeast(0L)
    val position = progress.positionMs.coerceAtLeast(0L)

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("YMusic · WebViewPlayer (YouTube / JCEF)", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = videoId,
                onValueChange = { videoId = it.trim() },
                label = { Text("YouTube video id") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { player.load(PlayableItem.YtVideoId(videoId), playWhenReady = true, config = config) }) { Text("Load") }
        }

        Text("State: ${status.state}", style = MaterialTheme.typography.bodyMedium)
        status.error?.let { Text("Failed (${it.code})", color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio-only")
            Switch(
                checked = audioOnly,
                onCheckedChange = {
                    val quality = if (it) VideoQualityState.AUDIO_ONLY else VideoQualityState.AUTO
                    config = config.copy(preferVideoQuality = quality to 0L)
                    player.setConfig(config)
                },
            )
        }

        // The WebView surface (the player already added its browser into `container`).
        SwingPanel(factory = { container }, modifier = Modifier.fillMaxWidth().weight(1f))

        Text("${formatMillis(position)} / ${if (duration > 0L) formatMillis(duration) else "--:--"}")
        Slider(
            value = if (duration > 0L) position.toFloat() / duration else 0f,
            onValueChange = { frac -> if (duration > 0L) player.seekTo((frac * duration).toLong()) },
            enabled = duration > 0L,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { player.play() }) { Text("Play") }
            Button(onClick = { player.pause() }) { Text("Pause") }
            Button(onClick = { player.seekTo(position - 10_000) }) { Text("−10s") }
            Button(onClick = { player.seekTo(position + 10_000) }) { Text("+10s") }
            Button(onClick = { player.stop() }) { Text("Stop") }
        }

        Spacer(Modifier.height(4.dp))
        Text("Volume ${(config.volume * 100).toInt()}%")
        Slider(value = config.volume, onValueChange = { config = config.copy(volume = it); player.setConfig(config) })
    }
}

private fun formatMillis(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
