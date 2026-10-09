package io.ymusic.playground

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.player.CaptionLine
import io.ymusic.dynamicengine.player.CaptionTrackInfo
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import projectcommon.json.boolOrNull
import projectcommon.json.intOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.objects
import projectcommon.json.stringOrNull
import kotlin.time.TimeSource

@Composable
fun YtMsePlayerScreen(modifier: Modifier = Modifier) {
    val surface = remember { MseVideoSurface() }
    val scope = rememberCoroutineScope()
    val closer = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var session by remember { mutableStateOf<MseSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var booting by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var preloading by remember { mutableStateOf(false) }
    var videoId by remember { mutableStateOf(defaultMseVideoId()) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var live by remember { mutableStateOf(false) }
    var seekable by remember { mutableStateOf(true) }
    var seekableStart by remember { mutableStateOf(0L) }
    var seekableEnd by remember { mutableStateOf(0L) }
    var paused by remember { mutableStateOf(true) }
    val sink = remember { MseVideoSink() }
    var frames by remember { mutableStateOf(0) }
    var frameLabel by remember { mutableStateOf("none") }
    var fpsText by remember { mutableStateOf("-- fps") }
    var overlayClicks by remember { mutableStateOf(0) }
    var scrubFrac by remember { mutableStateOf<Float?>(null) }
    var quality by remember { mutableStateOf("auto") }
    var audioOnly by remember { mutableStateOf(true) }
    var qualityChoices by remember { mutableStateOf(FALLBACK_QUALITY_CHOICES) }
    var reportedQuality by remember { mutableStateOf("") }
    var captionTracks by remember { mutableStateOf<List<CaptionTrackInfo>>(emptyList()) }
    var captionVss by remember { mutableStateOf<String?>(null) }
    var captionLines by remember { mutableStateOf<List<CaptionLine>>(emptyList()) }
    var holeSize by remember { mutableStateOf(IntSize.Zero) }
    var didLoad by remember { mutableStateOf(false) }
    var pictureW by remember { mutableStateOf(0) }
    var pictureH by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            val s = withContext(Dispatchers.Default) {
                bootMseSession(surface)
            }
            withContext(Dispatchers.Default) { s.player.init(s.log) }
            session = s
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            println("mse boot failed: $t")
            t.printStackTrace()
            booting = false
        }
    }
    LaunchedEffect(session, holeSize) {
        val s = session ?: return@LaunchedEffect
        val w = holeSize.width
        val h = holeSize.height
        if (w > 0 && h > 0) {
            withContext(Dispatchers.Default) { s.player.setViewport(w, h, s.log) }
        }
        if (didLoad) return@LaunchedEffect
        if (w <= 0 || h <= 0) return@LaunchedEffect
        var waited = 0
        while (!sink.surfaceReady && waited < 3000) {
            delay(16)
            waited += 16
        }
        if (!sink.surfaceReady) logMseLab("load without video surface after ${waited}ms")
        didLoad = true
        try {
            withContext(Dispatchers.Default) {
                s.player.load(
                    videoId,
                    s.log,
                    quality = quality,
                    width = w,
                    height = h,
                    audioOnly = audioOnly,
                )
                s.player.play(s.log)
            }
            captionTracks = withContext(Dispatchers.Default) { pullCaptionTracks(s) }
            qualityChoices = withContext(Dispatchers.Default) { pullQualityChoices(s) }
            captionVss = null
            captionLines = emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            println("mse boot failed: $t")
            t.printStackTrace()
        }
        booting = false
    }
    DisposableEffect(Unit) {
        onDispose {
            val s = session
            closer.launch {
                try {
                    s?.close()
                } finally {
                    closer.cancel()
                }
            }
        }
    }

    val ready = session != null && !booting && !loading
    val stateText = if (paused) "paused" else "playing"
    val windowStart = if (seekable) seekableStart.coerceAtLeast(0L) else 0L
    val windowEnd =
        if (seekable) {
            val end = if (seekableEnd > seekableStart) seekableEnd else duration
            end.coerceAtLeast(windowStart)
        } else {
            0L
        }
    val windowSpan = (windowEnd - windowStart).coerceAtLeast(1L)
    val canSeek = ready && seekable && windowEnd > windowStart
    /* Live DVR: bar is anchored at the live head (right = now). Displayed
       time is position − head, so at live it stays 0 / LIVE; seek back is −0:10. */
    val displayMediaMs =
        if (live && canSeek && scrubFrac != null) {
            windowStart + (scrubFrac!! * windowSpan).toLong()
        } else {
            position
        }
    val liveOffsetMs = displayMediaMs - windowEnd
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        val incoming = Channel<JsonObject>(Channel.CONFLATED)
        val job = launch(Dispatchers.Default) {
            try {
                s.player.collectStatus({ incoming.trySend(it) }, s.log)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    error = t.message ?: t.toString()
                    println("mse status session failed: $t")
                }
            } finally {
                incoming.close()
            }
        }
        try {
            for (st in incoming) {
                val clock = st.objectOrNull("progress")
                val state = st.objectOrNull("state")
                val picture = st.objectOrNull("picture")
                if (clock != null) {
                    val pos = clock.longOrNull("positionMs") ?: 0L
                    val skStart = clock.longOrNull("seekableStartMs") ?: 0L
                    val skEnd = clock.longOrNull("seekableEndMs") ?: 0L
                    position = pos
                    duration = clock.longOrNull("durationMs") ?: 0L
                    seekable = clock.boolOrNull("seekable") != false
                    seekableStart = skStart
                    seekableEnd = skEnd
                    val scrub = scrubFrac
                    if (scrub != null && skEnd > skStart) {
                        val target = skStart + (scrub * (skEnd - skStart)).toLong()
                        if (kotlin.math.abs(pos - target) < 1_000L)
                            scrubFrac = null
                    }
                }
                if (state != null) {
                    live = state.boolOrNull("live") == true
                    val newState = state.stringOrNull("newState")
                    val pwr = state.boolOrNull("playWhenReady") == true
                    paused = newState == "ended" || newState == "idle" || !pwr
                }
                if (picture != null) {
                    val pw = picture.intOrNull("videoWidth") ?: 0
                    val ph = picture.intOrNull("videoHeight") ?: 0
                    if (pw != pictureW) pictureW = pw
                    if (ph != pictureH) pictureH = ph
                }
            }
        } finally {
            job.cancel()
        }
    }
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        val incoming = Channel<JsonObject>(Channel.CONFLATED)
        val job = launch(Dispatchers.Default) {
            try {
                s.player.collectCatalog({ incoming.trySend(it) }, s.log)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    println("mse catalog session failed: $t")
                }
            } finally {
                incoming.close()
            }
        }
        try {
            for (st in incoming) {
                val qs = st.qualityChoices()
                if (qs.size > 2) qualityChoices = qs
                reportedQuality = st.stringOrNull("preferQuality")
                    ?: st.stringOrNull("quality")
                    ?: reportedQuality
            }
        } finally {
            job.cancel()
        }
    }
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        var fpsCount = sink.frameCount
        var fpsMark = TimeSource.Monotonic.markNow()
        while (true) {
            val n = surface.presentedFrames().takeIf { it > 0 } ?: sink.frameCount
            if (n != frames) {
                if (frames == 0 && n > 0) {
                    println("mse first frame ${sink.frameW}x${sink.frameH}")
                    logMseLab("first frame ${sink.frameW}x${sink.frameH}")
                }
                frames = n
                frameLabel = if (n > 0) "${sink.frameW}x${sink.frameH}" else "none"
            }
            val elapsed = fpsMark.elapsedNow().inWholeMilliseconds
            if (elapsed >= 500L) {
                val df = n - fpsCount
                fpsText = if (n <= 0) "-- fps" else "${(df * 1000.0 / elapsed).toInt()} fps"
                fpsCount = n
                fpsMark = TimeSource.Monotonic.markNow()
            }
            logMseLab("ok pos=$position dur=$duration frames=$frames $fpsText")
            delay(250)
        }
    }
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        val incoming = Channel<List<CaptionLine>>(Channel.CONFLATED)
        val job = launch(Dispatchers.Default) {
            try {
                s.player.collectCaptionLines({ incoming.trySend(it) }, s.log)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    error = t.message ?: t.toString()
                    println("mse caption session failed: $t")
                }
            } finally {
                incoming.close()
            }
        }
        try {
            for (lines in incoming) captionLines = lines
        } finally {
            job.cancel()
        }
    }

    val clockText = buildString {
        append(
            when {
                live && !seekable -> "LIVE"
                live && liveOffsetMs > -1_000L -> "LIVE"
                live -> "${formatClock(liveOffsetMs)} / LIVE"
                duration > 0L -> "${formatClock(position)} / ${formatClock(duration)}"
                else -> "${formatClock(position)} / --:--"
            },
        )
        append("  $fpsText  frames=$frames  frame=$frameLabel  q=$quality")
        if (reportedQuality.isNotEmpty() && reportedQuality != quality) append(" ($reportedQuality)")
    }
    val videoPlaceholder = when {
        booting -> "Starting…"
        loading -> "Loading video…"
        audioOnly -> "Audio only"
        else -> "Waiting for video…"
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 600.dp
        val videoHole: @Composable (Modifier) -> Unit = { holeMod ->
            MseVideoHole(
                surface = surface,
                player = session?.player,
                sink = sink,
                placeholder = videoPlaceholder,
                pictureW = pictureW,
                pictureH = pictureH,
                fpsText = fpsText,
                frames = frames,
                overlayClicks = overlayClicks,
                onOverlayClick = {
                    overlayClicks++
                    println("mse compose overlay click $overlayClicks")
                },
                captionLines = captionLines,
                onHoleSize = {
                    holeSize = it
                    if (it.width > 0 && it.height > 0) {
                        surface.setBlitSize(it.width, it.height)
                    }
                },
                modifier = holeMod,
            )
        }
        val chrome: @Composable () -> Unit = {
            PlaygroundChrome(
                ready = ready,
                booting = booting,
                loading = loading,
                preloading = preloading,
                error = error,
                videoId = videoId,
                onVideoId = { videoId = it.trim() },
                onLoad = {
                    val s = session ?: return@PlaygroundChrome
                    loading = true
                    error = null
                    qualityChoices = FALLBACK_QUALITY_CHOICES
                    val id = videoId
                    val q = quality
                    val ao = audioOnly
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                val w = holeSize.width.takeIf { it > 0 }
                                val h = holeSize.height.takeIf { it > 0 }
                                s.player.load(
                                    id,
                                    s.log,
                                    quality = q,
                                    width = w,
                                    height = h,
                                    audioOnly = ao,
                                )
                                s.player.play(s.log)
                            }
                            val tracks = withContext(Dispatchers.Default) { pullCaptionTracks(s) }
                            captionTracks = tracks
                            qualityChoices = withContext(Dispatchers.Default) { pullQualityChoices(s) }
                            if (captionVss != null && tracks.none { it.vssId == captionVss }) {
                                captionVss = null
                                captionLines = emptyList()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse load failed: $t")
                            t.printStackTrace()
                        }
                        loading = false
                    }
                },
                onPreload = {
                    val s = session ?: return@PlaygroundChrome
                    val id = videoId
                    if (id.isEmpty()) return@PlaygroundChrome
                    preloading = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.preload(id, s.log) }
                            println("mse preload ok $id")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse preload failed: $t")
                        }
                        preloading = false
                    }
                },
                stateText = stateText,
                clockText = clockText,
                quality = if (audioOnly) "audio" else quality,
                qualityChoices = qualityChoices,
                captionVss = captionVss,
                captionTracks = captionTracks,
                onQuality = { token ->
                    val s = session ?: return@PlaygroundChrome
                    if (token == "audio") {
                        if (audioOnly) return@PlaygroundChrome
                        audioOnly = true
                    } else {
                        if (!audioOnly && quality == token) return@PlaygroundChrome
                        quality = token
                        audioOnly = false
                    }
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                if (token == "audio") {
                                    s.player.setQuality(quality, true, s.log)
                                } else {
                                    s.player.setQuality(token, false, s.log)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse setQuality failed: $t")
                        }
                    }
                },
                onCaption = { vss ->
                    if (captionVss == vss) return@PlaygroundChrome
                    val s = session ?: return@PlaygroundChrome
                    captionVss = vss
                    if (vss == null) captionLines = emptyList()
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.setCaption(vss, s.log) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse setCaption failed: $t")
                        }
                    }
                },
            )
        }
        val transport: @Composable () -> Unit = {
            PlaygroundTransport(
                canSeek = canSeek,
                ready = ready,
                scrubFrac = scrubFrac,
                position = position,
                windowStart = windowStart,
                windowSpan = windowSpan,
                onScrub = { scrubFrac = it },
                onSeek = { ms ->
                    val clamped = ms.coerceIn(windowStart, windowEnd)
                    println(
                        "mse seek fire ms=$ms clamped=$clamped window=$windowStart..$windowEnd " +
                            "pos=$position scrub=$scrubFrac",
                    )
                    val s = session ?: run {
                        println("mse seek skipped: no session")
                        return@PlaygroundTransport
                    }
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                s.player.seekTo(clamped, s.log)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                },
                onPlay = {
                    val s = session ?: return@PlaygroundTransport
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.play(s.log) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse play failed: $t")
                        }
                    }
                },
                onPause = {
                    val s = session ?: return@PlaygroundTransport
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.pause(s.log) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse pause failed: $t")
                        }
                    }
                },
                onStop = {
                    val s = session ?: return@PlaygroundTransport
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.stop(s.log) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse stop failed: $t")
                        }
                    }
                },
            )
        }
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    FittedVideo(pictureW, pictureH) { videoHole(it) }
                }
                Column(
                    Modifier
                        .widthIn(min = 280.dp, max = 440.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    chrome()
                    transport()
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                chrome()
                FittedVideo(pictureW, pictureH, Modifier.fillMaxWidth()) { videoHole(it) }
                transport()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FittedVideo(
    pictureW: Int,
    pictureH: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val ratio = if (pictureW > 0 && pictureH > 0) pictureW.toFloat() / pictureH else 16f / 9f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val unbounded = maxHeight == Dp.Infinity || maxHeight == Dp.Unspecified
        val parentRatio = if (!unbounded && maxHeight > 0.dp) maxWidth / maxHeight else ratio
        val hole =
            if (unbounded || parentRatio <= ratio) {
                Modifier.fillMaxWidth().aspectRatio(ratio)
            } else {
                Modifier.fillMaxHeight().aspectRatio(ratio)
            }
        content(hole)
    }
}

@Composable
private fun MseVideoHole(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    pictureW: Int,
    pictureH: Int,
    fpsText: String,
    frames: Int,
    overlayClicks: Int,
    onOverlayClick: () -> Unit,
    captionLines: List<CaptionLine>,
    onHoleSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.onSizeChanged(onHoleSize)) {
        MseNativeVideo(
            surface = surface,
            player = player,
            sink = sink,
            placeholder = placeholder,
            modifier = Modifier.fillMaxSize(),
            pictureWidth = pictureW,
            pictureHeight = pictureH,
        )
        if (frames > 0) {
            Text(
                fpsText,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Button(onClick = onOverlayClick, modifier = Modifier.align(Alignment.Center)) {
            Text(if (overlayClicks == 0) "Compose click" else "Compose click ×$overlayClicks")
        }
        CaptionOverlay(
            lines = captionLines,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaygroundChrome(
    ready: Boolean,
    booting: Boolean,
    loading: Boolean,
    preloading: Boolean,
    error: String?,
    videoId: String,
    onVideoId: (String) -> Unit,
    onLoad: () -> Unit,
    onPreload: () -> Unit,
    stateText: String,
    clockText: String,
    quality: String,
    qualityChoices: List<Pair<String, String>>,
    captionVss: String?,
    captionTracks: List<CaptionTrackInfo>,
    onQuality: (String) -> Unit,
    onCaption: (String?) -> Unit,
) {
    Text("YMusic · MSE YouTube (engine-js + libav)", style = MaterialTheme.typography.titleMedium)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (booting) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Booting engine (homepage scrape + BotGuard + base.js)…")
        }
    } else if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Loading video…")
        }
    } else if (preloading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Preloading…")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = videoId,
            onValueChange = onVideoId,
            label = { Text("YouTube video id") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            enabled = ready,
        )
        Button(onClick = onLoad, enabled = ready && videoId.isNotEmpty() && !loading) { Text("Load") }
        Button(onClick = onPreload, enabled = ready && videoId.isNotEmpty() && !preloading) { Text("Preload") }
    }
    Text("State: $stateText", style = MaterialTheme.typography.bodyMedium)
    Text(clockText, style = MaterialTheme.typography.bodySmall)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        qualityChoices.forEach { (token, label) ->
            FilterChip(
                selected = quality == token,
                onClick = { onQuality(token) },
                enabled = ready,
                label = { Text(label) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = captionVss == null,
            onClick = { onCaption(null) },
            enabled = ready,
            label = { Text("CC off") },
        )
        captionTracks.forEach { track ->
            FilterChip(
                selected = captionVss == track.vssId,
                onClick = { onCaption(track.vssId) },
                enabled = ready,
                label = { Text(track.displayName) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaygroundTransport(
    canSeek: Boolean,
    ready: Boolean,
    scrubFrac: Float?,
    position: Long,
    windowStart: Long,
    windowSpan: Long,
    onScrub: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    /* Slider onValueChangeFinished is the same frame as a click's onValueChange.
       The scrubFrac parameter is still the previous composition, so a click
       would no-op and leave the thumb latched. Write the fraction to a
       holder onValueChange can see immediately. */
    val lastFrac = remember { arrayOf<Float?>(null) }
    val seek = rememberUpdatedState(onSeek)
    val start = rememberUpdatedState(windowStart)
    val span = rememberUpdatedState(windowSpan)
    val enabled = rememberUpdatedState(canSeek)
    Slider(
        value = scrubFrac ?: ((position - windowStart).toFloat() / windowSpan).coerceIn(0f, 1f),
        onValueChange = { frac ->
            lastFrac[0] = frac
            onScrub(frac)
        },
        onValueChangeFinished = {
            val frac = lastFrac[0]
            val ms = if (frac != null) start.value + (frac * span.value).toLong() else -1L
            println("mse seek slider-up frac=$frac ms=$ms canSeek=${enabled.value}")
            if (frac == null || !enabled.value) return@Slider
            seek.value(ms)
        },
        enabled = canSeek,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onPlay, enabled = ready) { Text("Play") }
        Button(onClick = onPause, enabled = ready) { Text("Pause") }
        Button(
            onClick = { onSeek(position - 10_000) },
            enabled = canSeek,
        ) { Text("−10s") }
        Button(
            onClick = { onSeek(position + 10_000) },
            enabled = canSeek,
        ) { Text("+10s") }
        Button(onClick = onStop, enabled = ready) { Text("Stop") }
    }
}

@Composable
private fun CaptionOverlay(lines: List<CaptionLine>, modifier: Modifier = Modifier) {
    val lineH = 28.dp
    val density = LocalDensity.current
    val ids = lines.map { it.id }
    val textHold = remember { mutableStateMapOf<Int, String>() }
    lines.forEach { textHold[it.id] = it.text }

    var restIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var columnIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    val offset = remember { Animatable(0f) }

    LaunchedEffect(ids) {
        val prev = restIds
        val step = with(density) { lineH.toPx() }
        val spec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
        if (ids == prev) return@LaunchedEffect
        if (ids.isEmpty()) {
            if (columnIds.isNotEmpty()) {
                offset.animateTo(offset.value - step, spec)
            }
            restIds = emptyList()
            columnIds = emptyList()
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val sequential = prev.isEmpty() || ids.any { it in prev }
        if (!sequential) {
            restIds = ids
            columnIds = ids
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        if (prev.isEmpty()) {
            restIds = ids
            columnIds = ids
            offset.snapTo(step)
            offset.animateTo(0f, spec)
            return@LaunchedEffect
        }
        val newTail = ids.filter { it !in prev }
        if (newTail.isEmpty()) {
            restIds = ids
            columnIds = ids
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val strip = prev + newTail
        val pad = (2 - prev.size).coerceAtLeast(0)
        columnIds = List(pad) { -1 - it } + strip
        offset.snapTo(0f)
        offset.animateTo(-step * newTail.size, spec)
        restIds = ids
        columnIds = ids
        offset.snapTo(0f)
    }

    Box(
        modifier = modifier.height(lineH * 2).padding(horizontal = 12.dp).clipToBounds(),
        contentAlignment = Alignment.TopStart,
    ) {
        val pad = (2 - columnIds.size).coerceAtLeast(0)
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = offset.value },
        ) {
            repeat(pad) { Spacer(Modifier.height(lineH)) }
            val wrap = columnIds.count { it >= 0 } <= 1
            columnIds.forEach { id ->
                key(id) {
                    val text = if (id < 0) "" else textHold[id].orEmpty()
                    CaptionLineRow(text, lineH, maxLines = if (wrap) 2 else 1)
                }
            }
        }
    }
}

@Composable
private fun CaptionLineRow(text: String, lineH: Dp, maxLines: Int) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .height(lineH * maxLines)
            .then(
                if (text.isNotEmpty()) Modifier.background(Color.Black.copy(alpha = 0.55f))
                else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private suspend fun pullCaptionTracks(s: MseSession): List<CaptionTrackInfo> {
    repeat(8) { i ->
        val tracks = s.player.captionTracks(s.log)
        if (tracks.isNotEmpty()) {
            println("mse caption tracks=${tracks.size} try=$i")
            return tracks
        }
        delay(250)
    }
    println("mse caption tracks=0")
    return emptyList()
}

private val FALLBACK_QUALITY_CHOICES = listOf(
    "audio" to "Audio",
    "auto" to "Auto",
)

private fun JsonObject.qualityChoices(): List<Pair<String, String>> {
    return objects("qualities").mapNotNull { o ->
        val q = o.stringOrNull("quality")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        q to (o.stringOrNull("label")?.takeIf { it.isNotEmpty() } ?: q)
    }.filter { it.first != "zero" }
}

private fun List<Pair<String, String>>.withAudioChip(): List<Pair<String, String>> =
    listOf("audio" to "Audio") + filter { it.first != "audio" }

private suspend fun pullQualityChoices(s: MseSession): List<Pair<String, String>> {
    repeat(8) { i ->
        val qs = s.player.status(s.log).qualityChoices().withAudioChip()
        if (qs.size > 2) {
            println("mse quality chips=${qs.joinToString { it.second }} try=$i")
            return qs
        }
        delay(250)
    }
    println("mse quality chips=fallback")
    return FALLBACK_QUALITY_CHOICES
}

private fun formatClock(ms: Long): String {
    val total = kotlin.math.abs(ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val body =
        if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
    return if (ms < 0) "-$body" else body
}
