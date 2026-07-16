package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ymusic.player.LoopMode
import ymusic.player.PlaybackState

internal fun formatMillis(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * The docked mini-player (design/final/library.html `.mini`): tap to expand to Now Playing.
 *
 * [surface] is the shared video container rendered as the 36dp art tile — only passed on platforms
 * whose surface tolerates thumbnail size ([AppGraph.surfaceScalesDown]); it keeps a WebView source
 * playing while Now Playing is closed.
 */
@Composable
fun MiniPlayer(
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)? = null,
    onExpand: () -> Unit,
) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    val queue by coordinator.player.queue.collectAsState()
    val item = queue.currentItem ?: return

    Column(Modifier.background(DesignColors.SurfaceDim)) {
        HorizontalDivider(color = DesignColors.DividerMini)
        val duration = progress.durationMs
        LinearProgressIndicator(
            progress = { if (duration > 0) progress.positionMs.toFloat() / duration else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = DesignColors.Accent,
            trackColor = Color(0xFFEEEEEE),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(DesignColors.PlaceholderDim),
                contentAlignment = Alignment.Center,
            ) {
                surface?.invoke(Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(
                    coordinator.titleFor(item),
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                coordinator.metadataFor(item)?.subtitleLine1?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { coordinator.player.togglePlay() }) {
                Icon(
                    if (status.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    tint = DesignColors.InkMuted,
                )
            }
            IconButton(onClick = { coordinator.player.skipNext() }) {
                Icon(Icons.Default.SkipNext, "Next", tint = DesignColors.InkMuted)
            }
        }
    }
}

/** Full Now Playing (design/final/now-playing.html, portrait): top bar · video · meta · seek ·
 *  transport · "Up next" queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(graph: AppGraph, coordinator: PlaybackCoordinator, onClose: () -> Unit) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            PlayerTopBar(coordinator, sleepMs, onClose)

            // Video surface — the shell's shared container; each engine shows/hides its own picture.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF222222))) {
                graph.playerSurface(Modifier.fillMaxSize())
            }

            val current = queue.currentItem
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    coordinator.titleFor(current),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                coordinator.metadataFor(current)?.subtitleLine1?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                status.error?.let { Text("Failed (${it.code})", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }

            SeekBar(player, progress.positionMs, progress.durationMs)
            TransportRow(coordinator, status.playWhenReady, status.state == PlaybackState.Buffering)

            QueueHeader(coordinator, queue.currentIndex, queue.items.size, queue.shuffle, queue.loopMode)
            HorizontalDivider(color = Color(0xFFF0F0F0))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(queue.items) { index, item ->
                    QueueRow(coordinator, index, item, current = index == queue.currentIndex)
                }
            }
        }
    }
}

/** chevron-down · [right:] download · sleep timer · quality chip · ⋮ (audio-only + tracks). */
@Composable
private fun PlayerTopBar(coordinator: PlaybackCoordinator, sleepMs: Long?, onClose: () -> Unit) {
    val player = coordinator.player
    val quality by player.videoQuality.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            "Close",
            Modifier.size(22.dp).clickable(onClick = onClose),
            tint = DesignColors.InkMuted,
        )
        Box(Modifier.weight(1f))
        // Download lands with M6 (LocalMediaProxy + downloads); the slot is design chrome until then.
        Icon(Icons.Outlined.FileDownload, "Download", Modifier.size(22.dp), tint = DesignColors.InkMuted)
        SleepTimerButton(sleepMs) { player.setSleepTimer(it) }
        if (quality.options.isNotEmpty()) {
            QualityChip(
                label = quality.options.firstOrNull { it.quality == quality.actualQuality }?.label
                    ?: quality.preferredQuality,
                options = quality.options.map { it.quality to it.label },
                onSelect = { player.selectVideoQuality(it) },
            )
        }
        PlayerMoreMenu(coordinator)
    }
}

@Composable
private fun QualityChip(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
            modifier = Modifier
                .border(1.dp, Color(0xFFD2D2D2), RoundedCornerShape(6.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}

/** The ⋮ menu: audio-only toggle + caption / audio-track selection (capability-driven). */
@Composable
private fun PlayerMoreMenu(coordinator: PlaybackCoordinator) {
    val player = coordinator.player
    val config by player.config.collectAsState()
    val captions by player.captions.collectAsState()
    val audioTracks by player.audioTracks.collectAsState()
    var open by remember { mutableStateOf(false) }

    Box {
        Icon(
            Icons.Default.MoreVert,
            "More",
            Modifier.size(22.dp).clickable { open = true },
            tint = DesignColors.InkMuted,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Audio-only") },
                trailingIcon = { Switch(checked = config.audioOnly, onCheckedChange = null) },
                onClick = { player.setAudioOnly(!config.audioOnly) },
            )
            if (captions.tracks.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("CC off") },
                    onClick = { player.selectCaption(null); open = false },
                )
                captions.tracks.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(if (t.languageCode == captions.selectedLanguage) "✓ ${t.displayName}" else t.displayName) },
                        onClick = { player.selectCaption(t.languageCode); open = false },
                    )
                }
            }
            if (audioTracks.tracks.size > 1) {
                audioTracks.tracks.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(if (t.id == audioTracks.selectedId) "✓ ${t.displayName}" else t.displayName) },
                        onClick = { player.selectAudioTrack(t.id); open = false },
                    )
                }
            }
        }
    }
}

/** 3dp track, orange fill, 13dp orange thumb; the m:ss / h:mm:ss labels underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(player: ymusic.player.Player, positionMs: Long, durationMs: Long) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp)) {
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { if (durationMs > 0) player.seekTo((it * durationMs).toLong()) },
            enabled = durationMs > 0,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            thumb = {
                Box(
                    Modifier
                        .size(13.dp)
                        .shadow(1.dp, CircleShape)
                        .background(DesignColors.Accent, CircleShape),
                )
            },
            track = { state ->
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFDCDCDC), RoundedCornerShape(2.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.value)
                            .height(3.dp)
                            .background(DesignColors.Accent, RoundedCornerShape(2.dp)),
                    )
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary)
            Text(formatMillis(positionMs), style = timeStyle)
            Text(if (durationMs > 0) formatMillis(durationMs) else "--:--", style = timeStyle)
        }
    }
}

/** rewind · prev · [64dp play] · next · forward. */
@Composable
private fun TransportRow(coordinator: PlaybackCoordinator, playing: Boolean, buffering: Boolean) {
    val player = coordinator.player
    val iconTint = Color(0xFF2B2B2B)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.FastRewind,
            "Rewind 10s",
            Modifier.size(26.dp).clickable { player.seekTo((player.progress.value.positionMs - 10_000).coerceAtLeast(0)) },
            tint = iconTint,
        )
        Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(26.dp).clickable { player.skipPrev() }, tint = iconTint)
        Box(
            Modifier.size(64.dp).background(Color(0xFF222222), CircleShape).clickable { player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            if (buffering) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    Modifier.size(26.dp),
                    tint = Color.White,
                )
            }
        }
        Icon(Icons.Default.SkipNext, "Next", Modifier.size(26.dp).clickable { player.skipNext() }, tint = iconTint)
        Icon(
            Icons.Default.FastForward,
            "Forward 10s",
            Modifier.size(26.dp).clickable { player.seekTo(player.progress.value.positionMs + 10_000) },
            tint = iconTint,
        )
    }
}

/** "Up next" · "n / total" · [right:] repeat · shuffle. */
@Composable
private fun QueueHeader(coordinator: PlaybackCoordinator, currentIndex: Int, total: Int, shuffle: Boolean, loopMode: LoopMode) {
    val player = coordinator.player
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Up next", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink))
        Text(
            "${currentIndex + 1} / $total",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkFaint),
        )
        Box(Modifier.weight(1f))
        Icon(
            if (loopMode == LoopMode.One) Icons.Default.RepeatOne else Icons.Default.Repeat,
            "Repeat",
            Modifier
                .size(20.dp)
                .clickable {
                    player.setLoopMode(
                        when (loopMode) {
                            LoopMode.None -> LoopMode.All
                            LoopMode.All -> LoopMode.One
                            LoopMode.One -> LoopMode.None
                        },
                    )
                },
            tint = if (loopMode != LoopMode.None) DesignColors.Accent else DesignColors.InkSoft,
        )
        Icon(
            Icons.Default.Shuffle,
            "Shuffle",
            Modifier.size(20.dp).clickable { player.setShuffle(!shuffle) },
            tint = if (shuffle) DesignColors.Accent else DesignColors.InkSoft,
        )
    }
}

@Composable
private fun QueueRow(coordinator: PlaybackCoordinator, index: Int, item: ymusic.player.PlayableItem, current: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { coordinator.player.playAt(index) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = Color(0xFFC4C4C4))
        Box(
            Modifier.size(48.dp).background(DesignColors.Placeholder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = DesignColors.PlaceholderIcon)
        }
        Column(Modifier.weight(1f)) {
            Text(
                coordinator.titleFor(item),
                style = TextStyle(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) DesignColors.Accent else DesignColors.Ink,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = coordinator.metadataFor(item)
            val sub = listOfNotNull(meta?.subtitleLine1, meta?.duration).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box {
            Icon(
                Icons.Default.MoreVert,
                "More",
                Modifier.size(22.dp).clickable { menuOpen = true },
                tint = DesignColors.IconDots,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = { coordinator.player.removeAt(index); menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun SleepTimerButton(sleepMs: Long?, onSet: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            Icons.Default.Bedtime,
            "Sleep timer",
            Modifier.size(22.dp).clickable { open = true },
            tint = if (sleepMs != null) DesignColors.Accent else DesignColors.InkMuted,
        )
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
