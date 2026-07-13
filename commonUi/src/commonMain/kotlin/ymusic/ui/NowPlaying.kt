package ymusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ymusic.player.LoopMode
import ymusic.player.PlaybackState

internal fun formatMillis(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** The docked mini-player (every screen, design §3): tap to expand to the full Now Playing. */
@Composable
fun MiniPlayer(coordinator: PlaybackCoordinator, onExpand: () -> Unit) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    val queue by coordinator.player.queue.collectAsState()
    val item = queue.currentItem ?: return

    Column {
        val duration = progress.durationMs
        LinearProgressIndicator(
            progress = { if (duration > 0) progress.positionMs.toFloat() / duration else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            drawStopIndicator = {},
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    coordinator.titleFor(item),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                coordinator.metadataFor(item)?.byline?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            IconButton(onClick = { coordinator.player.togglePlay() }) {
                Icon(if (status.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
            }
            IconButton(onClick = { coordinator.player.skipNext() }) { Icon(Icons.Default.SkipNext, "Next") }
        }
    }
}

/** Full Now Playing (design §10/§11): video area, transport, selectors, queue. */
@Composable
fun NowPlayingScreen(graph: AppGraph, coordinator: PlaybackCoordinator, onClose: () -> Unit) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val engine by player.currentEngine.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SleepTimerButton(sleepMs) { player.setSleepTimer(it) }
            }

            // Video surface — shell-provided; audio-only renders nothing inside it.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                graph.playerSurface(engine, Modifier.fillMaxSize())
            }

            val current = queue.currentItem
            Text(coordinator.titleFor(current), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            coordinator.metadataFor(current)?.byline?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status.error?.let { Text("Failed (${it.code})", color = MaterialTheme.colorScheme.error) }

            val duration = progress.durationMs
            Slider(
                value = if (duration > 0) progress.positionMs.toFloat() / duration else 0f,
                onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()) },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMillis(progress.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(if (duration > 0) formatMillis(duration) else "--:--", style = MaterialTheme.typography.labelMedium)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.setShuffle(!queue.shuffle) }) {
                    Icon(
                        Icons.Default.Shuffle,
                        "Shuffle",
                        tint = if (queue.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { player.skipPrev() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                FilledIconButton(onClick = { player.togglePlay() }, modifier = Modifier.size(56.dp)) {
                    if (status.state == PlaybackState.Buffering) {
                        Text("…")
                    } else {
                        Icon(if (status.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                    }
                }
                IconButton(onClick = { player.skipNext() }) { Icon(Icons.Default.SkipNext, "Next") }
                IconButton(
                    onClick = {
                        player.setLoopMode(
                            when (queue.loopMode) {
                                LoopMode.None -> LoopMode.All
                                LoopMode.All -> LoopMode.One
                                LoopMode.One -> LoopMode.None
                            },
                        )
                    },
                ) {
                    Icon(
                        if (queue.loopMode == LoopMode.One) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (queue.loopMode != LoopMode.None) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SelectorsRow(coordinator)

            HorizontalDivider()
            Text("Queue · ${queue.currentIndex + 1}/${queue.items.size}", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(queue.items) { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { player.playAt(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            coordinator.titleFor(item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == queue.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { player.removeAt(index) }) {
                            Icon(Icons.Default.Delete, "Remove", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Audio-only / quality / captions / audio-track — driven by the facade's capability flows. */
@Composable
private fun SelectorsRow(coordinator: PlaybackCoordinator) {
    val player = coordinator.player
    val config by player.config.collectAsState()
    val quality by player.videoQuality.collectAsState()
    val captions by player.captions.collectAsState()
    val audioTracks by player.audioTracks.collectAsState()

    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Audio-only", style = MaterialTheme.typography.labelMedium)
        Switch(checked = config.audioOnly, onCheckedChange = { player.setAudioOnly(it) })

        if (quality.options.isNotEmpty()) {
            Selector(
                label = quality.options.firstOrNull { it.quality == quality.actualQuality }?.label ?: quality.preferredQuality,
                options = quality.options.map { it.quality to it.label },
            ) { player.selectVideoQuality(it) }
        }
        if (captions.tracks.isNotEmpty()) {
            Selector(
                label = captions.selectedLanguage ?: "CC off",
                options = listOf("off" to "CC off") + captions.tracks.map { it.languageCode to it.displayName },
            ) { player.selectCaption(it.takeIf { it != "off" }) }
        }
        if (audioTracks.tracks.size > 1) {
            Selector(
                label = audioTracks.tracks.firstOrNull { it.id == audioTracks.selectedId }?.displayName ?: "Audio",
                options = audioTracks.tracks.map { it.id to it.displayName },
            ) { player.selectAudioTrack(it) }
        }
    }
}

@Composable
private fun Selector(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}

@Composable
private fun SleepTimerButton(sleepMs: Long?, onSet: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.Bedtime,
                "Sleep timer",
                tint = if (sleepMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sleepMs?.let {
                DropdownMenuItem(text = { Text("Off (${formatMillis(it)} left)") }, onClick = { onSet(null); open = false })
            }
            listOf(15, 30, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes min") },
                    onClick = { onSet(minutes * 60_000L); open = false },
                )
            }
        }
    }
}
