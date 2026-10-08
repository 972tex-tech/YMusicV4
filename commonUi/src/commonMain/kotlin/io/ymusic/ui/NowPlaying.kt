package io.ymusic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.ymusic.player.LoopMode
import io.ymusic.player.PlayableItem
import io.ymusic.player.PlaybackState
import io.ymusic.player.PlaybackStatus
import io.ymusic.player.Player
import io.ymusic.player.Progress
import io.ymusic.player.QueueMore
import io.ymusic.player.QueueState
import io.ymusic.player.VideoQualityOption
import io.ymusic.player.VideoQualityState
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.audio_only
import io.ymusic.ui.resources.badge_live
import io.ymusic.ui.resources.captions_auto_translate
import io.ymusic.ui.resources.captions_off
import io.ymusic.ui.resources.cd_captions
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.cd_download
import io.ymusic.ui.resources.cd_drag_to_reorder
import io.ymusic.ui.resources.cd_exit_fullscreen
import io.ymusic.ui.resources.cd_fill_video
import io.ymusic.ui.resources.cd_fit_video
import io.ymusic.ui.resources.cd_forward10
import io.ymusic.ui.resources.cd_fullscreen
import io.ymusic.ui.resources.cd_more
import io.ymusic.ui.resources.cd_next
import io.ymusic.ui.resources.cd_play_pause
import io.ymusic.ui.resources.cd_previous
import io.ymusic.ui.resources.cd_repeat
import io.ymusic.ui.resources.cd_rewind10
import io.ymusic.ui.resources.cd_shuffle
import io.ymusic.ui.resources.cd_sleep_timer
import io.ymusic.ui.resources.ic_sponsor_block
import io.ymusic.ui.resources.playback_failed
import io.ymusic.ui.resources.queue_end
import io.ymusic.ui.resources.queue_load_failed
import io.ymusic.ui.resources.queue_load_more
import io.ymusic.ui.resources.queue_loading_more
import io.ymusic.ui.resources.remove_from_queue
import io.ymusic.ui.resources.retry
import io.ymusic.ui.resources.sleep_minutes
import io.ymusic.ui.resources.sleep_off_remaining
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_speed
import io.ymusic.ui.resources.sponsor_block_excluded
import io.ymusic.ui.resources.sponsor_block_included
import io.ymusic.ui.resources.up_next
import io.ymusic.ui.resources.video_details
import io.ymusic.ui.resources.video_quality_auto
import io.ymusic.ui.sponsorblock.SponsorBlockState
import kmpcommon.error.errorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal fun formatMillis(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/** True when the portrait title/seek/transport block (list item 0) has left the viewport. */
private fun inlineControlsOffscreen(listState: LazyListState): Boolean {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return false
    if (listState.firstVisibleItemIndex >= 1) return true
    val item0 = visible.find { it.index == 0 }
    return item0 == null || item0.offset + item0.size <= 0
}

/**
 * The docked mini-player (design/final/library.html `.mini`): tap to expand to Now Playing.
 *
 * [surface] is the shared video container rendered as the 36dp art tile — only passed on platforms
 * whose surface tolerates thumbnail size ([AppGraph.surfaceScalesDown]); it keeps picture playing
 * while Now Playing is closed.
 */
@Composable
fun MiniPlayer(
    coordinator: PlaybackCoordinator,
    surface: (@Composable (Modifier) -> Unit)? = null,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by coordinator.player.status.collectAsState()
    val progress by coordinator.player.progress.collectAsState()
    val queue by coordinator.player.queue.collectAsState()
    val item = queue.currentItem ?: return

    val duration = progress.durationMs
    val density = LocalDensity.current
    var colH by remember { mutableIntStateOf(0) }
    val tileH = with(density) { if (colH > 0) colH.toDp() else 48.dp }
    SwipeDownDismissBox(
        onDismiss = {
            coordinator.player.stop()
            coordinator.player.clearQueue()
        },
        modifier = modifier.fillMaxWidth(),
        commitFractionOfHeight = 1f / 3f,
        restingShadow = true,
        enabled = !desktopLayoutHole,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(tileH)
                    .aspectRatio(16f / 9f)
                    .then(if (desktopLayoutHole) Modifier else Modifier.background(Color.Black)),
                contentAlignment = Alignment.Center,
            ) {
                val audioOnly = showsAudioOnly(coordinator.player)
                if (surface != null) {
                    VideoHole(coordinator.player, surface, Modifier.fillMaxSize())
                }
                if (audioOnly || surface == null) {
                    AudioOnlyCover(item.displayThumbnailUrl, Modifier.fillMaxSize())
                }
                BufferingIndicator(status, size = 22.dp, strokeWidth = 2.dp)
                Box(Modifier.matchParentSize().clickable(onClick = onExpand))
            }
            Column(
                Modifier.weight(1f)
                    .background(DesignColors.SurfaceDim)
                    .onSizeChanged { colH = it.height },
            ) {
                LinearProgressIndicator(
                    progress = { if (duration > 0) progress.positionMs.toFloat() / duration else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = DesignColors.Accent,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.displayTitle,
                            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.displaySubtitle?.let {
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
    }
}

/**
 * Full Now Playing (design/final/now-playing.html). Layout follows the window's orientation:
 * portrait/tablet = the top-bar · video · meta · seek · transport · queue column; landscape
 * (width > height) = the split — video fills the left with the controls overlaid on it, and a fixed
 * right pane carries the top bar and the queue. Full screen overrides both: video only, edge to
 * edge, with the auto-hiding overlay.
 */
private val LocalOpenChannel = staticCompositionLocalOf<(url: String, title: String) -> Unit> { { _, _ -> } }

@Composable
fun NowPlayingScreen(
    graph: AppGraph,
    coordinator: PlaybackCoordinator,
    onClose: () -> Unit,
    onPeek: (Float) -> Unit = {},
    onOpenChannel: (url: String, title: String) -> Unit = { _, _ -> },
) {
    var fullscreen by remember { mutableStateOf(false) }
    DisposableEffect(fullscreen) {
        graph.pip.videoFullscreen.value = fullscreen
        if (!fullscreen) graph.videoFillScreen.value = false
        onDispose {
            graph.pip.videoFullscreen.value = false
            graph.videoFillScreen.value = false
        }
    }
    val playWhenReady = coordinator.player.status.collectAsState().value.playWhenReady
    val armed = VideoOverlayNative.armed
    val overlayVisible = VideoOverlayNative.visible
    val showGen = VideoOverlayNative.showGen
    val scrubbing = VideoOverlayNative.scrubbing
    val menusOpen = VideoOverlayNative.menusOpen
    LaunchedEffect(playWhenReady, armed, overlayVisible, showGen, scrubbing, menusOpen) {
        if (armed && overlayVisible && playWhenReady && !scrubbing && menusOpen == 0) {
            delay(OVERLAY_HIDE_DELAY_MS)
            VideoOverlayNative.visible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { VideoOverlayNative.armed = false }
    }
    val shell = @Composable {
        BoxWithConstraints {
            when {
                fullscreen -> FullscreenVideo(graph, coordinator, onExit = { fullscreen = false })
                maxWidth > maxHeight -> LandscapeNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
                else -> PortraitNowPlaying(graph, coordinator, onClose, onFullscreen = { fullscreen = true })
            }
        }
    }
    SwipeDownDismissBox(
        onDismiss = onClose,
        modifier = Modifier.then(if (fullscreen) Modifier else Modifier.statusBarsPadding()).fillMaxSize(),
        onPeek = onPeek,
        enabled = !desktopLayoutHole,
    ) {
        // No full-screen fill: the video hole is opaque (TextureView) and the chrome paints
        // Surface itself. A wrapping Surface under the picture is 1× overdraw on every pixel.
        CompositionLocalProvider(LocalOpenChannel provides onOpenChannel) {
            Box(Modifier.fillMaxSize(), content = { shell() })
        }
    }
}

/** The current item's artist line. A YouTube video's opens its channel — from the queue item, or
 *  once the player response names it. */
@Composable
private fun ArtistLine(current: PlayableItem?, status: PlaybackStatus, style: TextStyle, modifier: Modifier) {
    val video = current as? PlayableItem.YtMedia
    val details = status.details?.takeIf { video != null && it.videoId == video.videoId }
    val text = current?.displaySubtitle ?: details?.channelTitle ?: return
    val url = video?.channelUrl ?: details?.channelUrl
    val openChannel = LocalOpenChannel.current
    Text(
        text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (url != null) Modifier.clickable { openChannel(url, text) } else Modifier),
    )
}

/** The video alone, edge to edge, with the auto-hiding overlay; its full-screen button exits. */
@Composable
private fun FullscreenVideo(graph: AppGraph, coordinator: PlaybackCoordinator, onExit: () -> Unit) {
    val player = coordinator.player
    val status by player.status.collectAsState()
    val progress by player.progress.collectAsState()
    val queue by player.queue.collectAsState()
    val current = queue.currentItem

    SideEffect { VideoOverlayNative.armed = true }
    LaunchedEffect(Unit) { VideoOverlayNative.show() }
    SyncImmersiveSystemBars(immersive = true)
    SyncSensorRotation(enabled = true)
    val fillScreen by graph.videoFillScreen
    Box(Modifier.fillMaxSize()) {
        PlayerSurfaceHole(
            player,
            graph.playerSurface,
            current?.displayThumbnailUrl,
            Modifier.fillMaxSize(),
            fill = fillScreen,
            settings = graph.settings,
        )
        Box(Modifier.fillMaxSize()) {
            BufferingIndicator(status)
            AutoHidingOverlay {
                VideoOverlayControls(
                    coordinator, status, progress, current,
                    isFullscreen = true,
                    fillScreen = fillScreen,
                    onToggleFill = { graph.videoFillScreen.value = !graph.videoFillScreen.value },
                    onToggleFullscreen = onExit,
                )
            }
            PlayerCaptionOverlay(graph.player, VideoOverlayNative.showing, fill = fillScreen)
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
    val controlsOnVideo by remember {
        derivedStateOf { inlineControlsOffscreen(listState) }
    }
    // Do not onDispose armed=false here: Compose disposes Portrait after Landscape
    // has set armed=true, which left clicks ignored (poke requires armed).
    LaunchedEffect(listState) {
        snapshotFlow { inlineControlsOffscreen(listState) }.collect { off ->
            val becameArmed = off && !VideoOverlayNative.armed
            VideoOverlayNative.armed = off
            // Only when item 0 first leaves the viewport — not on later queue scrolls.
            if (becameArmed) VideoOverlayNative.show()
        }
    }
    // Queue rows sit after the controls item and the sticky header in this list.
    val dragState = rememberQueueDragState(listState, indexOffset = 2, player)

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .then(if (desktopLayoutHole) Modifier.background(Color.Transparent) else Modifier),
    ) {
        PlayerTopBar(coordinator, sleepMs, onClose)

        // Video surface — the shell's shared container; each engine shows/hides its own picture.
        // Black: HW overlay letterbox is transparent, so this fill is the bars.
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(if (desktopLayoutHole) Modifier else Modifier.background(Color.Black)),
        ) {
            PlayerSurfaceHole(
                player,
                graph.playerSurface,
                current?.displayThumbnailUrl,
                Modifier.fillMaxSize(),
                settings = graph.settings,
            )
            Box(Modifier.fillMaxSize()) {
                // Stay composed: remounting after scroll-up missed the next scroll-down
                // (chrome window kept a stale content lambda).
                BufferingIndicator(status)
                AutoHidingOverlay {
                    VideoOverlayControls(
                        coordinator, status, progress, current,
                        compact = true, onToggleFullscreen = onFullscreen,
                    )
                }
                val overlayOn = VideoOverlayNative.showing
                PlayerCaptionOverlay(player, overlayVisible = overlayOn)
                if (!overlayOn) {
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
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).background(DesignColors.Surface),
            overscrollEffect = null,
        ) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            current?.displayTitle.orEmpty(),
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkStrong),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        ArtistLine(
                            current,
                            status,
                            TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
                            Modifier.padding(top = 5.dp),
                        )
                        status.error?.let { PlaybackErrorRow(player, it) }
                    }
                    SeekBar(player, progress)
                    TransportRow(coordinator, status.playWhenReady)
                }
            }
            stickyHeader {
                // Pinned (= item 0 scrolled away, the same condition that overlays the controls):
                // the header lifts off the scrolling queue with a shadow and an elevated fill.
                val pinned = controlsOnVideo
                val headerShadow by animateDpAsState(if (pinned) 4.dp else 0.dp)
                val headerColor by animateColorAsState(
                    if (pinned) DesignColors.SurfaceElevated else DesignColors.Surface,
                )
                Column(
                    Modifier.fillMaxWidth().then(
                        if (pinned) Modifier.shadow(headerShadow).background(headerColor)
                        else Modifier,
                    ),
                ) {
                    QueueHeader(coordinator, queue.currentIndex, queue.displayTotal, queue.shuffle, queue.loopMode)
                    HorizontalDivider(color = DesignColors.Divider)
                }
            }
            itemsIndexed(queue.items) { index, item ->
                QueueRow(coordinator, index, item, current = index == queue.currentIndex, dragState)
            }
            queue.more?.let { more -> item { QueueMoreRow(player, more) } }
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
    SideEffect { VideoOverlayNative.armed = true }
    LaunchedEffect(Unit) { VideoOverlayNative.show() }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            PlayerSurfaceHole(
                player,
                graph.playerSurface,
                current?.displayThumbnailUrl,
                Modifier.fillMaxSize(),
                settings = graph.settings,
            )
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                BufferingIndicator(status)
                AutoHidingOverlay {
                    VideoOverlayControls(coordinator, status, progress, current, onToggleFullscreen = onFullscreen)
                }
                PlayerCaptionOverlay(player, VideoOverlayNative.showing)
            }
        }
        VerticalDivider(color = DesignColors.Divider)
        Column(
            Modifier.width(320.dp).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .background(DesignColors.Surface),
        ) {
            PlayerTopBar(coordinator, sleepMs, onClose)
            status.error?.let {
                Box(Modifier.padding(horizontal = 16.dp)) { PlaybackErrorRow(player, it) }
            }
            QueueHeader(coordinator, queue.currentIndex, queue.displayTotal, queue.shuffle, queue.loopMode)
            HorizontalDivider(color = DesignColors.Divider)
            val queueListState = rememberLazyListState()
            val dragState = rememberQueueDragState(queueListState, indexOffset = 0, player)
            LazyColumn(state = queueListState, modifier = Modifier.weight(1f)) {
                itemsIndexed(queue.items) { index, item ->
                    QueueRow(coordinator, index, item, current = index == queue.currentIndex, dragState)
                }
                queue.more?.let { more -> item { QueueMoreRow(player, more) } }
            }
        }
    }
}

/**
 * Hosts video-overlay [content] with player-style auto-hide: it fades out after a few seconds of
 * playback, and a tap/click on the video toggles it. Dragging the seek bar holds it visible.
 * While paused nothing auto-hides.
 */
@Composable
private fun BoxScope.AutoHidingOverlay(
    content: @Composable BoxScope.() -> Unit,
) {
    val armed = VideoOverlayNative.armed
    val visible = VideoOverlayNative.visible
    val show = armed && visible
    if (armed) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { VideoOverlayNative.poke() }
                },
        )
    }
    if (show) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

private const val OVERLAY_HIDE_DELAY_MS = 3_000L

private fun PlaybackStatus.isBufferingOrLoading(): Boolean = when (val s = state) {
    is PlaybackState.Prepared -> s.buffering
    is PlaybackState.Uninitialized -> s.isInitializing
    PlaybackState.Idle -> playWhenReady
    is PlaybackState.Error -> false
}

/** Stays on the video while overlay chrome auto-hides; transport play/pause stays tappable. */
@Composable
private fun BoxScope.BufferingIndicator(
    status: PlaybackStatus,
    size: Dp = 72.dp,
    strokeWidth: Dp = 3.dp,
) {
    if (!status.isBufferingOrLoading()) return
    CircularProgressIndicator(
        Modifier.align(Alignment.Center).size(size),
        color = Color.White,
        strokeWidth = strokeWidth,
    )
}

/** The controls drawn over the video: landscape always, portrait once the inline controls scroll
 *  away. [compact] hides ±10s seek buttons (the portrait overlay). */
@Composable
private fun BoxScope.VideoOverlayControls(
    coordinator: PlaybackCoordinator,
    status: PlaybackStatus,
    progress: io.ymusic.player.Progress,
    current: PlayableItem?,
    compact: Boolean = false,
    isFullscreen: Boolean = false,
    fillScreen: Boolean = false,
    onToggleFill: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
) {
    val player = coordinator.player
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.24f)))

    Column(
        Modifier.align(Alignment.TopStart)
            .then(if (isFullscreen) Modifier.keepBelowStatusBar() else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 11.dp),
    ) {
        Text(
            current?.displayTitle.orEmpty(),
            style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ArtistLine(
            current,
            status,
            TextStyle(fontSize = 11.sp, color = Color(0xFFDADADA)),
            Modifier.padding(top = 3.dp),
        )
    }

    Box(Modifier.align(Alignment.Center)) {
        TransportRow(
            coordinator,
            status.playWhenReady,
            iconTint = Color.White,
            playBackground = Color.Black.copy(alpha = 0.42f),
            playForeground = Color.White,
            playSize = 58.dp,
            seekButtons = !compact,
        )
    }

    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val labelProgress = scrubMs?.let { progress.copy(positionMs = it) } ?: progress
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val overlayStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEEEEEE))
            labelProgress.progressLabel?.let {
                Text(it, style = overlayStyle)
                Text(" / ", style = overlayStyle)
            }
            DurationLabel(player, progress, overlayStyle)
            Box(Modifier.weight(1f))
            CaptionToggle(player, offTint = Color(0xFFEEEEEE))
            if (onToggleFill != null) {
                ActionIcon(
                    if (fillScreen) Icons.Default.FitScreen else Icons.Default.CropFree,
                    stringResource(if (fillScreen) Res.string.cd_fit_video else Res.string.cd_fill_video),
                    tint = Color(0xFFEEEEEE),
                    onClick = onToggleFill,
                )
            }
            ActionIcon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                stringResource(if (isFullscreen) Res.string.cd_exit_fullscreen else Res.string.cd_fullscreen),
                tint = Color(0xFFEEEEEE),
                onClick = onToggleFullscreen,
            )
        }
        SeekSlider(
            player,
            progress,
            trackColor = Color.White.copy(alpha = 0.32f),
            bufferedColor = Color.White.copy(alpha = 0.55f),
            onScrubMs = { scrubMs = it },
        )
    }
}

/** Height of the video controls' bottom strip, which captions on the picture stay above while it shows. */
private val VideoControlsStrip = 72.dp

/**
 * Captions centered on the picture (the video's contain-fit rect, as [VideoHole] lays it out): in the bar
 * below it when that bar fits two rows clear of the controls strip, otherwise over the picture's bottom.
 */
@Composable
private fun BoxScope.PlayerCaptionOverlay(
    player: io.ymusic.player.Player,
    overlayVisible: Boolean,
    fill: Boolean = false,
) {
    val lines by player.captionLines.collectAsState()
    if (lines.isEmpty()) return
    val status by player.status.collectAsState()
    val audioOnly = showsAudioOnly(player)
    val controlsInset by animateDpAsState(if (overlayVisible) VideoControlsStrip else 12.dp)
    val twoRows = captionTwoRowHeight()
    BoxWithConstraints(Modifier.matchParentSize()) {
        val ratio = status.videoSize?.aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
        val pictureWidth: Dp
        val pictureHeight: Dp
        if (fill || audioOnly || maxWidth / maxHeight <= ratio) {
            pictureWidth = maxWidth
            pictureHeight = if (fill || audioOnly) maxHeight else maxWidth / ratio
        } else {
            pictureWidth = maxHeight * ratio
            pictureHeight = maxHeight
        }
        val bar = (maxHeight - pictureHeight) / 2
        if (bar >= twoRows + VideoControlsStrip + DesignSpacing.Space8) {
            CaptionOverlay(
                lines,
                Modifier.align(Alignment.TopCenter)
                    .padding(top = bar + pictureHeight + DesignSpacing.Space8)
                    .width(pictureWidth),
            )
        } else {
            CaptionOverlay(
                lines,
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = maxOf(bar + 12.dp, controlsInset))
                    .width(pictureWidth),
            )
        }
    }
}

/**
 * The video CC control: hidden when the source has no caption tracks. Tapping opens a menu of
 * Off + every available language (checkmark on the active one); the glyph is accent-filled while
 * captions are on. Picking a language turns captions on and selects it; Off hides them.
 */
@Composable
private fun CaptionToggle(player: io.ymusic.player.Player, offTint: Color) {
    val captions by player.captions.collectAsState()
    val config by player.config.collectAsState()
    if (captions.tracks.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var translateOpen by remember { mutableStateOf(false) }
    HoldVideoOverlay(open || translateOpen)
    val (translations, tracks) = captions.tracks.partition { it.kind == AUTO_TRANSLATE_KIND }
    fun isOn(track: io.ymusic.player.CaptionTrack) = config.captionVisible &&
        (track.id == captions.selectedLanguage || track.languageCode == captions.selectedLanguage)
    fun pick(track: io.ymusic.player.CaptionTrack) {
        player.selectCaption(track.id)
        player.setCaptionVisible(true)
        open = false
        translateOpen = false
    }
    Box {
        ActionIcon(
            if (config.captionVisible) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaptionOff,
            stringResource(Res.string.cd_captions),
            tint = if (config.captionVisible) DesignColors.Accent else offTint,
            onClick = { open = true },
        )
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                val off = !config.captionVisible
                DropdownMenuItem(
                    text = { Text(if (off) "✓ ${stringResource(Res.string.captions_off)}" else stringResource(Res.string.captions_off)) },
                    onClick = { player.setCaptionVisible(false); open = false },
                )
                tracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(if (isOn(track)) "✓ ${track.displayName}" else track.displayName) },
                        onClick = { pick(track) },
                    )
                }
                if (translations.isNotEmpty()) {
                    val label = stringResource(Res.string.captions_auto_translate)
                    val current = translations.firstOrNull { isOn(it) }
                    DropdownMenuItem(
                        text = { Text(if (current != null) "✓ $label · ${current.displayName}" else "$label ›") },
                        onClick = {
                            open = false
                            translateOpen = true
                        },
                    )
                }
            }
        }
        // ~150 languages: a lazy list, so opening it does not compose every row.
        if (translateOpen) {
            DropdownMenu(expanded = true, onDismissRequest = { translateOpen = false }) {
                LazyColumn(Modifier.size(width = 240.dp, height = 360.dp)) {
                    items(translations, key = { it.id }) { track ->
                        DropdownMenuItem(
                            text = { Text(if (isOn(track)) "✓ ${track.displayName}" else track.displayName) },
                            onClick = { pick(track) },
                        )
                    }
                }
            }
        }
    }
}

/** Engine track kind for YouTube auto-translate languages (clones of a source track, one per language). */
private const val AUTO_TRANSLATE_KIND = "asrt"

/** "Failed (code)" + a Retry action when the error is retryable. */
@Composable
private fun PlaybackErrorRow(player: io.ymusic.player.Player, error: io.ymusic.player.PlayerError) {
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

/** chevron-down · [right:] download · sponsorblock · sleep timer · quality chip · ⋮ (reload + tracks). */
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

    val queue by player.queue.collectAsState()
    val downloadLauncher = LocalDownloadLauncher.current
    val downloadable = coordinator.downloadable(queue.currentItem)
    val videoId = (queue.currentItem as? PlayableItem.YtMedia)?.videoId
    val sponsorBlock = coordinator.graph.sponsorBlock
    val sbChanges by sponsorBlock.changes.collectAsState()
    val sbEnabled by remember(coordinator.graph) {
        coordinator.graph.settings.flow(AppSettings.sponsorBlockEnabled)
    }.collectAsState()
    var sbState by remember { mutableStateOf(SponsorBlockState.Unavailable) }
    LaunchedEffect(videoId, sbChanges, sbEnabled) {
        sbState = if (videoId == null) {
            SponsorBlockState.Unavailable
        } else {
            sponsorBlock.load(videoId).state
        }
    }
    val scope = rememberCoroutineScope()
    val showSnackbar = LocalSnackbar.current
    val includedMsg = stringResource(Res.string.sponsor_block_included)
    val excludedMsg = stringResource(Res.string.sponsor_block_excluded)

    Row(
        // Paddings/gaps shave the ActionIcon hit-inset (5dp) so the glyphs keep the bar's metrics.
        Modifier.fillMaxWidth()
            .background(DesignColors.Surface)
            .padding(horizontal = 11.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.cd_close), onClick = onClose)
        Box(Modifier.weight(1f))
        // Only for a downloadable item (a non-live YouTube video) where there is a download service.
        if (downloadLauncher != null && downloadable != null) {
            ActionIcon(
                Icons.Outlined.FileDownload,
                stringResource(Res.string.cd_download),
                onClick = { downloadLauncher.launch(downloadable) },
            )
        }
        if (sbState != SponsorBlockState.Unavailable) {
            val included = sbState == SponsorBlockState.Available
            ActionIcon(
                painterResource(Res.drawable.ic_sponsor_block),
                if (included) includedMsg else excludedMsg,
                tint = if (included) DesignColors.Accent else DesignColors.InkMuted,
                onClick = {
                    val id = videoId ?: return@ActionIcon
                    showSnackbar(if (included) excludedMsg else includedMsg)
                    scope.launch {
                        if (included) sponsorBlock.addToWhitelist(id) else sponsorBlock.removeFromWhitelist(id)
                    }
                },
            )
        }
        SleepTimerButton(sleepMs) { player.setSleepTimer(it) }
        if (quality.options.isNotEmpty()) {
            QualityChip(
                label = quality.options.firstOrNull { it.quality == quality.actualQuality }?.let(::qualityLabel)
                    ?: when (quality.actualQuality) {
                        VideoQualityState.AUTO -> autoQualityLabel
                        VideoQualityState.AUDIO_ONLY -> audioOnlyLabel
                        else -> quality.actualQuality
                    },
                options = quality.options.map { it.quality to qualityLabel(it) },
                selectedId = quality.preferredQuality,
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
    selectedId: String? = null,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HoldVideoOverlay(open)
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
                val selected = id == selectedId
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = DesignColors.Accent) }
                    } else {
                        null
                    },
                    onClick = { onSelect(id); open = false },
                )
            }
        }
    }
}

/** The ⋮ menu: reload + audio-track selection. Captions live on the video CC control; audio-only
 *  is a quality-chip option, not a toggle here. */
@Composable
private fun PlayerMoreMenu(coordinator: PlaybackCoordinator) {
    val player = coordinator.player
    val audioTracks by player.audioTracks.collectAsState()
    var open by remember { mutableStateOf(false) }
    var playbackOpen by remember { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val video = player.queue.collectAsState().value.currentItem as? PlayableItem.YtMedia
    HoldVideoOverlay(open || playbackOpen || eqOpen || detailsOpen)

    Box {
        ActionIcon(Icons.Default.MoreVert, stringResource(Res.string.cd_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (video != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.video_details)) },
                    onClick = { detailsOpen = true; open = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sound_speed)) },
                onClick = { playbackOpen = true; open = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sound_eq)) },
                onClick = { eqOpen = true; open = false },
            )
            // Recovery/debug action — hardcoded label (adding a Res string here crosses the compose
            // accessor chunk boundary, whose second file isn't compiled; revisit once that's fixed).
            DropdownMenuItem(
                text = { Text("Reload player") },
                onClick = { player.reload(); open = false },
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
    if (playbackOpen) {
        PlaybackFxSheet(player, onDismiss = { playbackOpen = false })
    }
    if (detailsOpen && video != null) {
        val openChannel = LocalOpenChannel.current
        VideoDetailsSheet(
            coordinator.graph,
            video.videoId,
            onOpenChannel = { url, title -> detailsOpen = false; openChannel(url, title) },
            onDismiss = { detailsOpen = false },
        )
    }
    if (eqOpen) {
        EqualizerFxSheet(player, onDismiss = { eqOpen = false })
    }
}

/** 3dp track, orange fill, 13dp orange thumb; the m:ss / h:mm:ss labels underneath. */
@Composable
private fun SeekBar(player: io.ymusic.player.Player, progress: Progress) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val labelProgress = scrubMs?.let { progress.copy(positionMs = it) } ?: progress
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)) {
        SeekSlider(player, progress, trackColor = DesignColors.OutlineSoft, onScrubMs = { scrubMs = it })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val timeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary)
            Text(labelProgress.progressLabel.orEmpty(), style = timeStyle)
            Spacer(Modifier.weight(1f))
            DurationLabel(player, progress, timeStyle)
        }
    }
}

@Composable
private fun DurationLabel(
    player: io.ymusic.player.Player,
    progress: Progress,
    style: TextStyle,
) {
    val text = if (progress.durationIsLive) {
        stringResource(Res.string.badge_live)
    } else {
        progress.durationLabel ?: "--:--"
    }
    Text(
        text,
        style = if (progress.highlightDuration) style.copy(color = DesignColors.Accent) else style,
        modifier = if (progress.durationIsLive && progress.seekable) {
            Modifier.clickable { player.seekTo(progress.durationMs.coerceAtLeast(0L)) }
        } else {
            Modifier
        },
    )
}

/** The bare slider (3dp [trackColor] track, orange fill + thumb) — SeekBar's core, reused by the
 *  landscape video overlay. */
@Composable
private fun SeekSlider(
    player: io.ymusic.player.Player,
    progress: Progress,
    trackColor: Color,
    bufferedColor: Color = DesignColors.Accent.copy(alpha = 0.35f),
    onScrubMs: ((Long?) -> Unit)? = null,
) {
    val durationMs = progress.durationMs
    val skipSegments by player.skipSegments.collectAsState()
    val queue by player.queue.collectAsState()
    val streamed = queue.currentItem !is PlayableItem.LocalMedia
    SlimSeekBar(
        fraction = if (durationMs > 0) progress.positionMs.toFloat() / durationMs else 0f,
        bufferedFraction = if (streamed && durationMs > 0) progress.bufferedPositionMs.toFloat() / durationMs else 0f,
        bufferedColor = bufferedColor,
        enabled = progress.seekable && durationMs > 0,
        onSeek = { f -> player.seekTo((f * durationMs).toLong()) },
        onScrub = { f -> onScrubMs?.invoke(if (f == null || durationMs <= 0) null else (f * durationMs).toLong()) },
        trackColor = trackColor,
        skipFractions = skipSegmentFractions(skipSegments, durationMs),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** rewind · prev · [play circle] · next · forward. Dark-on-white by default; the video overlay
 *  passes white glyphs on a translucent play circle, and drops the ±10s pair when compact. */
@Composable
private fun TransportRow(
    coordinator: PlaybackCoordinator,
    playing: Boolean,
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
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(Res.string.cd_play_pause),
                    Modifier.size(26.dp),
                    tint = playForeground,
                )
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
/** The playlist's length while its queue is still loading, else the queue's own size. */
private val QueueState.displayTotal: Int
    get() = more?.totalCount?.coerceAtLeast(items.size) ?: items.size

/** The end of a growing queue: Load more, its progress, a failure with Retry, or the list's end. */
@Composable
private fun QueueMoreRow(player: Player, more: QueueMore) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space16),
        contentAlignment = Alignment.Center,
    ) {
        val noteStyle = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary)
        val error = more.error
        when {
            more.loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.queue_loading_more), style = noteStyle)
            }
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
            ) {
                Text(stringResource(Res.string.queue_load_failed), style = noteStyle)
                Text(
                    errorMessage(error.errorCode() ?: "E60063"),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkFaint),
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.padding(top = DesignSpacing.Space4)) {
                    PlayPill(Icons.Default.Refresh, stringResource(Res.string.retry), filled = false, onClick = player::loadMore)
                }
            }
            more.hasMore -> PlayPill(
                Icons.Default.ExpandMore,
                stringResource(Res.string.queue_load_more),
                filled = false,
                onClick = player::loadMore,
            )
            else -> Text(stringResource(Res.string.queue_end), style = noteStyle)
        }
    }
}

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

/**
 * Drag-to-reorder for the queue lists. A drag on a row's handle starts it; rows swap live through
 * `player.move` whenever the dragged row's center crosses a neighbour, and the list autoscrolls
 * while the row is held past either viewport edge. [indexOffset] maps queue indices to lazy-item
 * indices (the portrait list carries the controls item and the sticky header before the rows).
 */
private class QueueDragState(
    private val listState: LazyListState,
    private val indexOffset: Int,
    private val onMove: (Int, Int) -> Unit,
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** Pixels the dragged row overhangs the viewport — the autoscroll loop's speed input. */
    var autoscroll by mutableFloatStateOf(0f)
        private set

    fun start(index: Int) {
        draggingIndex = index
        dragOffset = 0f
    }

    fun drag(deltaY: Float) {
        dragOffset += deltaY
        swapIfCrossed()
        val info = draggedItem() ?: return
        val layout = listState.layoutInfo
        val top = info.offset + dragOffset
        val bottom = top + info.size
        autoscroll = when {
            bottom > layout.viewportEndOffset -> bottom - layout.viewportEndOffset
            top < layout.viewportStartOffset -> top - layout.viewportStartOffset
            else -> 0f
        }
    }

    /** The autoscroll loop reports what the list scrolled; keeps the row under the pointer. */
    fun scrolledBy(consumed: Float) {
        dragOffset += consumed
        swapIfCrossed()
    }

    /** The list hit its end — stop the loop until the next drag event re-evaluates. */
    fun stopAutoscroll() {
        autoscroll = 0f
    }

    fun end() {
        draggingIndex = null
        dragOffset = 0f
        autoscroll = 0f
    }

    private fun draggedItem() = draggingIndex?.let { dragged ->
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragged + indexOffset }
    }

    private fun swapIfCrossed() {
        val from = draggingIndex ?: return
        val info = draggedItem() ?: return
        val center = info.offset + dragOffset + info.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index != info.index && it.index >= indexOffset &&
                center >= it.offset && center < it.offset + it.size
        } ?: return
        onMove(from, target.index - indexOffset)
        // The moved row is laid out at the target's old slot next frame; fold the base-position
        // change into the translation so it stays under the pointer.
        dragOffset += info.offset - target.offset
        draggingIndex = target.index - indexOffset
    }
}

@Composable
private fun rememberQueueDragState(
    listState: LazyListState,
    indexOffset: Int,
    player: io.ymusic.player.Player,
): QueueDragState {
    val state = remember(listState, player) {
        QueueDragState(listState, indexOffset) { from, to -> player.move(from, to) }
    }
    LaunchedEffect(state) {
        while (true) {
            snapshotFlow { state.autoscroll }.first { it != 0f }
            while (state.autoscroll != 0f) {
                val step = (state.autoscroll * 0.12f).coerceIn(-24f, 24f)
                val consumed = listState.scrollBy(step)
                if (consumed == 0f) {
                    state.stopAutoscroll()
                    break
                }
                state.scrolledBy(consumed)
                withFrameNanos {}
            }
        }
    }
    return state
}

@Composable
private fun QueueRow(
    coordinator: PlaybackCoordinator,
    index: Int,
    item: io.ymusic.player.PlayableItem,
    current: Boolean,
    dragState: QueueDragState,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dragging = dragState.draggingIndex == index
    Row(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragState.dragOffset else 0f }
            .then(
                if (dragging) Modifier.shadow(4.dp).background(DesignColors.SurfaceElevated)
                else Modifier,
            )
            .clickable { coordinator.player.playAt(index) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.DragHandle,
            stringResource(Res.string.cd_drag_to_reorder),
            Modifier
                .align(Alignment.CenterVertically)
                .size(18.dp)
                .pointerInput(dragState, index) {
                    detectDragGestures(
                        onDragStart = { dragState.start(index) },
                        onDragEnd = dragState::end,
                        onDragCancel = dragState::end,
                    ) { change, amount ->
                        change.consume()
                        dragState.drag(amount.y)
                    }
                },
            tint = DesignColors.Outline,
        )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ArtImage(item.displayThumbnailUrl, RoundedCornerShape(6.dp)) {
                ThumbPlaceholder(Icons.Outlined.Image, 18.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.displayTitle,
                style = TextStyle(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) DesignColors.Accent else DesignColors.Ink,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(item.displayDuration, item.displaySubtitle).joinToString(" · ")
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
    HoldVideoOverlay(open)
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
