package ymusic.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.ClosedCaptionOff
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ymusic.player.LoopMode
import ymusic.player.PlayableItem
import ymusic.player.PlaybackState
import ymusic.player.PlaybackStatus
import ymusic.player.VideoQualityOption
import ymusic.player.VideoQualityState
import ymusic.ui.resources.Res
import ymusic.ui.resources.audio_only
import ymusic.ui.resources.captions_off
import ymusic.ui.resources.cd_captions
import ymusic.ui.resources.cd_close
import ymusic.ui.resources.cd_download
import ymusic.ui.resources.cd_exit_fullscreen
import ymusic.ui.resources.cd_forward10
import ymusic.ui.resources.cd_fullscreen
import ymusic.ui.resources.cd_more
import ymusic.ui.resources.cd_next
import ymusic.ui.resources.cd_play_pause
import ymusic.ui.resources.cd_previous
import ymusic.ui.resources.cd_repeat
import ymusic.ui.resources.cd_rewind10
import ymusic.ui.resources.cd_shuffle
import ymusic.ui.resources.cd_sleep_timer
import ymusic.ui.resources.playback_failed
import ymusic.ui.resources.remove_from_queue
import ymusic.ui.resources.retry
import ymusic.ui.resources.sleep_minutes
import ymusic.ui.resources.sleep_off_remaining
import ymusic.ui.resources.up_next
import ymusic.ui.resources.video_quality_auto

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
            trackColor = DesignColors.Divider,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(DesignColors.PlaceholderDim),
                contentAlignment = Alignment.Center,
            ) {
                if (surface != null) {
                    // The video surface renders inside the tile on every platform, but it must be
                    // LAID OUT at real video size and only scale-TRANSFORMED down to fit: a windowed
                    // WebView (Android) sized to an actual thumbnail breaks the page's stream
                    // negotiation, and Chromium backgrounds (and stalls) a page whose visible rect
                    // is clipped away. The draw-time scale changes neither. 240×135 → ×36/135 draws
                    // a 64×36 cover crop inside the 36dp tile.
                    val scale = 36f / 135f
                    Box(
                        Modifier
                            .requiredSize(240.dp, 135.dp)
                            .graphicsLayer(scaleX = scale, scaleY = scale),
                    ) {
                        surface.invoke(Modifier.fillMaxSize())
                    }
                    // Taps on the tile expand Now Playing — they must not reach the video page.
                    Box(Modifier.matchParentSize().clickable(onClick = onExpand))
                } else {
                    ThumbImage(coordinator.metadataFor(item)?.thumbs.orEmpty(), 36.dp, RoundedCornerShape(8.dp))
                }
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
                    stringResource(Res.string.cd_play_pause),
                    tint = DesignColors.InkMuted,
                )
            }
            IconButton(onClick = { coordinator.player.skipNext() }) {
                Icon(Icons.Default.SkipNext, stringResource(Res.string.cd_next), tint = DesignColors.InkMuted)
            }
        }
    }
}

/**
 * Full Now Playing (design/final/now-playing.html). Layout follows the window's orientation:
 * portrait/tablet = the top-bar · video · meta · seek · transport · queue column; landscape
 * (width > height) = the split — video fills the left with the controls overlaid on it, and a fixed
 * right pane carries the top bar and the queue. Full screen overrides both: video only, edge to
 * edge, with the auto-hiding overlay.
 */
@Composable
fun NowPlayingScreen(graph: AppGraph, coordinator: PlaybackCoordinator, onClose: () -> Unit) {
    var fullscreen by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = DesignColors.Surface) {
        BoxWithConstraints {
            when {
                fullscreen -> FullscreenVideo(graph, coordinator, onExit = { fullscreen = false })
                maxWidth > maxHeight -> LandscapeNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
                else -> PortraitNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
            }
        }
    }
}

/** The video alone, edge to edge, with the auto-hiding overlay; its full-screen button exits. */
@Composable
private fun FullscreenVideo(graph: AppGraph, coordinator: PlaybackCoordinator, onExit: () -> Unit) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val current = queue.currentItem

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        graph.playerSurface(Modifier.fillMaxSize())
        AutoHidingOverlay(playing = status.playWhenReady) {
            VideoOverlayControls(coordinator, status, progress, current, isFullscreen = true, onToggleFullscreen = onExit)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortraitNowPlaying(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()
    val current = queue.currentItem

    // Everything under the video scrolls as one list; once the meta/seek/transport block (item 0)
    // scrolls away, those controls re-appear overlaid on the video and the queue header pins.
    val listState = rememberLazyListState()
    val controlsOnVideo by remember { derivedStateOf { listState.firstVisibleItemIndex >= 1 } }

    Column(Modifier.fillMaxSize()) {
        PlayerTopBar(coordinator, sleepMs, onClose)

        // Video surface — the shell's shared container; each engine shows/hides its own picture.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF222222))) {
            graph.playerSurface(Modifier.fillMaxSize())
            // Fully qualified: the enclosing Column's scoped AnimatedVisibility otherwise shadows
            // the top-level overload and cannot be called from Box scope.
            androidx.compose.animation.AnimatedVisibility(controlsOnVideo, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    AutoHidingOverlay(playing = status.playWhenReady) {
                        VideoOverlayControls(
                            coordinator, status, progress, current,
                            compact = true, onToggleFullscreen = onFullscreen,
                        )
                    }
                }
            }
            // The bare video's corner controls (mockup .vctrl: CC + full screen); the overlay carries
            // its own copies for the scrolled state.
            if (!controlsOnVideo) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CaptionToggle(player, offTint = Color(0xFFDCDCDC))
                    ActionIcon(
                        Icons.Default.Fullscreen,
                        stringResource(Res.string.cd_fullscreen),
                        tint = Color(0xFFDCDCDC),
                        onClick = onFullscreen,
                    )
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            coordinator.titleFor(current),
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong),
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
                        status.error?.let { PlaybackErrorRow(player, it) }
                    }
                    SeekBar(player, progress.positionMs, progress.durationMs)
                    TransportRow(
                        coordinator,
                        status.playWhenReady,
                        (status.state as? PlaybackState.Prepared)?.buffering == true,
                    )
                }
            }
            stickyHeader {
                Column(Modifier.fillMaxWidth().background(DesignColors.Surface)) {
                    QueueHeader(coordinator, queue.currentIndex, queue.items.size, queue.shuffle, queue.loopMode)
                    HorizontalDivider(color = DesignColors.Divider)
                }
            }
            itemsIndexed(queue.items) { index, item ->
                QueueRow(coordinator, index, item, current = index == queue.currentIndex)
            }
        }
    }
}

/** Landscape split (mockup `.ls`): video left with overlaid controls; 320dp right pane. */
@Composable
private fun LandscapeNowPlaying(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val sleepMs by player.sleepTimerMs.collectAsState()
    val current = queue.currentItem

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF222222))) {
            graph.playerSurface(Modifier.fillMaxSize())
            AutoHidingOverlay(playing = status.playWhenReady) {
                VideoOverlayControls(coordinator, status, progress, current, onToggleFullscreen = onFullscreen)
            }
        }
        VerticalDivider(color = DesignColors.Divider)
        Column(Modifier.width(320.dp).fillMaxHeight()) {
            PlayerTopBar(coordinator, sleepMs, onClose)
            status.error?.let {
                Box(Modifier.padding(horizontal = 16.dp)) { PlaybackErrorRow(player, it) }
            }
            QueueHeader(coordinator, queue.currentIndex, queue.items.size, queue.shuffle, queue.loopMode)
            HorizontalDivider(color = DesignColors.Divider)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(queue.items) { index, item ->
                    QueueRow(coordinator, index, item, current = index == queue.currentIndex)
                }
            }
        }
    }
}

/**
 * Hosts video-overlay [content] with player-style auto-hide: it fades out after a few seconds of
 * playback, and a tap/click on the video toggles it in either state. While paused nothing
 * auto-hides; pausing brings the controls back.
 */
@Composable
private fun BoxScope.AutoHidingOverlay(playing: Boolean, content: @Composable BoxScope.() -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableStateOf(0) }
    // The tap catcher sits under the controls: taps on a control hit the control; taps on the bare
    // video (or anywhere while hidden) land here and toggle.
    Box(
        Modifier.matchParentSize().pointerInput(Unit) {
            detectTapGestures {
                visible = !visible
                interaction++
            }
        },
    )
    // Pausing brings the controls back (a transition, not a hold — a tap can still hide them).
    LaunchedEffect(playing) {
        if (!playing) visible = true
    }
    LaunchedEffect(playing, visible, interaction) {
        if (playing && visible) {
            delay(OVERLAY_HIDE_DELAY_MS)
            visible = false
        }
    }
    androidx.compose.animation.AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

private const val OVERLAY_HIDE_DELAY_MS = 3_000L

/** The controls drawn over the video (mockup scrim/otop/octl/vbottom): landscape always, portrait
 *  once the inline controls scroll away. [compact] = prev/play/next only (the portrait overlay). */
@Composable
private fun BoxScope.VideoOverlayControls(
    coordinator: PlaybackCoordinator,
    status: PlaybackStatus,
    progress: ymusic.player.Progress,
    current: PlayableItem?,
    compact: Boolean = false,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit,
) {
    val player = coordinator.player
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.24f)))

    Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = 16.dp, top = 11.dp)) {
        Text(
            coordinator.titleFor(current),
            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        coordinator.metadataFor(current)?.subtitleLine1?.let {
            Text(
                it,
                style = TextStyle(fontSize = 11.sp, color = Color(0xFFDADADA)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }

    Box(Modifier.align(Alignment.Center)) {
        TransportRow(
            coordinator,
            status.playWhenReady,
            (status.state as? PlaybackState.Prepared)?.buffering == true,
            iconTint = Color.White,
            playBackground = Color.Black.copy(alpha = 0.42f),
            playForeground = Color.White,
            playSize = 58.dp,
            seekButtons = !compact,
        )
    }

    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatMillis(progress.positionMs)} / " +
                    if (progress.durationMs > 0) formatMillis(progress.durationMs) else "--:--",
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEEEEEE)),
            )
            Box(Modifier.weight(1f))
            CaptionToggle(player, offTint = Color(0xFFEEEEEE))
            ActionIcon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                stringResource(if (isFullscreen) Res.string.cd_exit_fullscreen else Res.string.cd_fullscreen),
                tint = Color(0xFFEEEEEE),
                onClick = onToggleFullscreen,
            )
        }
        SeekSlider(player, progress.positionMs, progress.durationMs, trackColor = Color.White.copy(alpha = 0.32f))
    }
}

/**
 * The video CC control: hidden when the source has no caption tracks. Tapping opens a menu of
 * Off + every available language (checkmark on the active one); the glyph is accent-filled while
 * captions are on. Picking a language turns captions on and selects it; Off hides them.
 */
@Composable
private fun CaptionToggle(player: ymusic.player.Player, offTint: Color) {
    val captions by player.captions.collectAsState()
    val config by player.config.collectAsState()
    if (captions.tracks.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(
            if (config.captionVisible) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
            stringResource(Res.string.cd_captions),
            tint = if (config.captionVisible) DesignColors.Accent else offTint,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val off = !config.captionVisible
            DropdownMenuItem(
                text = { Text(if (off) "✓ ${stringResource(Res.string.captions_off)}" else stringResource(Res.string.captions_off)) },
                onClick = { player.setCaptionVisible(false); open = false },
            )
            captions.tracks.forEach { t ->
                val on = config.captionVisible && t.languageCode == captions.selectedLanguage
                DropdownMenuItem(
                    text = { Text(if (on) "✓ ${t.displayName}" else t.displayName) },
                    onClick = {
                        player.selectCaption(t.languageCode)
                        player.setCaptionVisible(true)
                        open = false
                    },
                )
            }
        }
    }
}

/** "Failed (code)" + a Retry action when the error is retryable. */
@Composable
private fun PlaybackErrorRow(player: ymusic.player.Player, error: ymusic.player.PlayerError) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.playback_failed, error.code),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
        if (error.canRetry) {
            // play() reloads the current item after a retryable error (the facade's port
            // of the legacy recovery path), so retry is just play.
            Text(
                stringResource(Res.string.retry),
                color = DesignColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { player.play() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** chevron-down · [right:] download · sleep timer · quality chip · ⋮ (audio-only + tracks). */
@Composable
private fun PlayerTopBar(coordinator: PlaybackCoordinator, sleepMs: Long?, onClose: () -> Unit) {
    val player = coordinator.player
    val quality by player.videoQuality.collectAsState()
    val audioOnlyLabel = stringResource(Res.string.audio_only)
    val autoQualityLabel = stringResource(Res.string.video_quality_auto)
    fun qualityLabel(option: VideoQualityOption): String = when (option.quality) {
        VideoQualityState.AUDIO_ONLY -> audioOnlyLabel
        VideoQualityState.AUTO -> autoQualityLabel
        else -> option.label
    }

    Row(
        // Paddings/gaps shave the ActionIcon hit-inset (5dp) so the glyphs keep the bar's metrics.
        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.cd_close), onClick = onClose)
        Box(Modifier.weight(1f))
        // Download lands with M6 (LocalMediaProxy + downloads); the slot is design chrome until then.
        ActionIcon(Icons.Outlined.FileDownload, stringResource(Res.string.cd_download), enabled = false, onClick = {})
        SleepTimerButton(sleepMs) { player.setSleepTimer(it) }
        if (quality.options.isNotEmpty()) {
            QualityChip(
                label = quality.options.firstOrNull { it.quality == quality.actualQuality }?.let(::qualityLabel)
                    ?: quality.preferredQuality,
                options = quality.options.map { it.quality to qualityLabel(it) },
                onSelect = { player.selectVideoQuality(it) },
            )
        }
        PlayerMoreMenu(coordinator)
    }
}

@Composable
internal fun QualityChip(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(6.dp))
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

/** The ⋮ menu: audio-only toggle + audio-track selection. Captions live on the video CC control. */
@Composable
private fun PlayerMoreMenu(coordinator: PlaybackCoordinator) {
    val player = coordinator.player
    val config by player.config.collectAsState()
    val audioTracks by player.audioTracks.collectAsState()
    var open by remember { mutableStateOf(false) }

    Box {
        ActionIcon(Icons.Default.MoreVert, stringResource(Res.string.cd_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.audio_only)) },
                trailingIcon = { Switch(checked = config.audioOnly, onCheckedChange = null) },
                onClick = { player.setAudioOnly(!config.audioOnly) },
            )
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
@Composable
private fun SeekBar(player: ymusic.player.Player, positionMs: Long, durationMs: Long) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)) {
        SeekSlider(player, positionMs, durationMs, trackColor = DesignColors.OutlineSoft)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary)
            Text(formatMillis(positionMs), style = timeStyle)
            Text(if (durationMs > 0) formatMillis(durationMs) else "--:--", style = timeStyle)
        }
    }
}

/** The bare slider (3dp [trackColor] track, orange fill + thumb) — SeekBar's core, reused by the
 *  landscape video overlay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekSlider(player: ymusic.player.Player, positionMs: Long, durationMs: Long, trackColor: Color) {
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
            Box(Modifier.fillMaxWidth().height(3.dp).background(trackColor, RoundedCornerShape(2.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(state.value)
                        .height(3.dp)
                        .background(DesignColors.Accent, RoundedCornerShape(2.dp)),
                )
            }
        },
    )
}

/** rewind · prev · [play circle] · next · forward. Dark-on-white by default; the video overlay
 *  passes white glyphs on a translucent play circle, and drops the ±10s pair when compact. */
@Composable
private fun TransportRow(
    coordinator: PlaybackCoordinator,
    playing: Boolean,
    buffering: Boolean,
    iconTint: Color = DesignColors.InkStrong,
    playBackground: Color = DesignColors.InkStrong,
    playForeground: Color = DesignColors.OnInk,
    playSize: Dp = 64.dp,
    seekButtons: Boolean = true,
) {
    val player = coordinator.player
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            // SpaceEvenly inside a capped width: the gaps grow with the window (≈ the mockup's 26dp
            // at phone width) up to the cap, instead of a fixed cluster.
            Modifier
                .widthIn(max = if (seekButtons) 480.dp else 320.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (seekButtons) {
                ActionIcon(
                    Icons.Default.FastRewind,
                    stringResource(Res.string.cd_rewind10),
                    iconSize = 26.dp,
                    hitSize = 36.dp,
                    tint = iconTint,
                    onClick = { player.seekTo((player.progress.value.positionMs - 10_000).coerceAtLeast(0)) },
                )
            }
            ActionIcon(
                Icons.Default.SkipPrevious,
                stringResource(Res.string.cd_previous),
                iconSize = 26.dp,
                hitSize = 36.dp,
                tint = iconTint,
                onClick = { player.skipPrev() },
            )
            Box(
                Modifier.size(playSize).clip(CircleShape).background(playBackground).clickable { player.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = playForeground, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(Res.string.cd_play_pause),
                        Modifier.size(26.dp),
                        tint = playForeground,
                    )
                }
            }
            ActionIcon(
                Icons.Default.SkipNext,
                stringResource(Res.string.cd_next),
                iconSize = 26.dp,
                hitSize = 36.dp,
                tint = iconTint,
                onClick = { player.skipNext() },
            )
            if (seekButtons) {
                ActionIcon(
                    Icons.Default.FastForward,
                    stringResource(Res.string.cd_forward10),
                    iconSize = 26.dp,
                    hitSize = 36.dp,
                    tint = iconTint,
                    onClick = { player.seekTo(player.progress.value.positionMs + 10_000) },
                )
            }
        }
    }
}

/** "Up next" · "n / total" · [right:] repeat · shuffle. */
@Composable
private fun QueueHeader(coordinator: PlaybackCoordinator, currentIndex: Int, total: Int, shuffle: Boolean, loopMode: LoopMode) {
    val player = coordinator.player
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.up_next),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
        )
        Text(
            "${currentIndex + 1} / $total",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkFaint),
        )
        Box(Modifier.weight(1f))
        ActionIcon(
            if (loopMode == LoopMode.One) Icons.Default.RepeatOne else Icons.Default.Repeat,
            stringResource(Res.string.cd_repeat),
            iconSize = 20.dp,
            tint = if (loopMode != LoopMode.None) DesignColors.Accent else DesignColors.InkSoft,
            onClick = {
                player.setLoopMode(
                    when (loopMode) {
                        LoopMode.None -> LoopMode.All
                        LoopMode.All -> LoopMode.One
                        LoopMode.One -> LoopMode.None
                    },
                )
            },
        )
        ActionIcon(
            Icons.Default.Shuffle,
            stringResource(Res.string.cd_shuffle),
            iconSize = 20.dp,
            tint = if (shuffle) DesignColors.Accent else DesignColors.InkSoft,
            onClick = { player.setShuffle(!shuffle) },
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = DesignColors.Outline)
        Box(
            Modifier.size(48.dp).background(DesignColors.Placeholder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = DesignColors.PlaceholderIcon)
            ThumbImage(coordinator.metadataFor(item)?.thumbs.orEmpty(), 48.dp, RoundedCornerShape(6.dp))
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
            ActionIcon(
                Icons.Default.MoreVert,
                stringResource(Res.string.cd_more),
                tint = DesignColors.IconDots,
                onClick = { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.remove_from_queue)) },
                    onClick = { coordinator.player.removeAt(index); menuOpen = false },
                )
            }
        }
    }
}

@Composable
internal fun SleepTimerButton(sleepMs: Long?, onSet: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ActionIcon(
            Icons.Default.Bedtime,
            stringResource(Res.string.cd_sleep_timer),
            tint = if (sleepMs != null) DesignColors.Accent else DesignColors.InkMuted,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sleepMs?.let {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sleep_off_remaining, formatMillis(it))) },
                    onClick = { onSet(null); open = false },
                )
            }
            listOf(15, 30, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sleep_minutes, minutes)) },
                    onClick = { onSet(minutes * 60_000L); open = false },
                )
            }
        }
    }
}
