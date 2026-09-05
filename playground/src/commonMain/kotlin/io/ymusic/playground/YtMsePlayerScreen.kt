package io.ymusic.playground

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.player.CaptionLine
import io.ymusic.dynamicengine.player.CaptionTrackInfo
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    var reportedQuality by remember { mutableStateOf("") }
    var captionTracks by remember { mutableStateOf<List<CaptionTrackInfo>>(emptyList()) }
    var captionVss by remember { mutableStateOf<String?>(null) }
    var captionLines by remember { mutableStateOf<List<CaptionLine>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            val s = withContext(Dispatchers.Default) {
                bootMseSession(surface)
            }
            withContext(Dispatchers.Default) { s.player.init(s.log) }
            session = s
            withContext(Dispatchers.Default) {
                s.player.load(videoId, s.log, quality)
                s.player.play(s.log)
            }
            captionTracks = withContext(Dispatchers.Default) { pullCaptionTracks(s) }
            captionVss = null
            captionLines = emptyList()
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
        var fpsCount = sink.frameCount
        var fpsMark = TimeSource.Monotonic.markNow()
        while (true) {
            val st = withContext(Dispatchers.Default) { s.player.status(s.log) }
            position = st.longField("positionMs")
            duration = st.longField("durationMs")
            live = (st["live"] as? JsonPrimitive)?.content == "true"
            seekable = (st["seekable"] as? JsonPrimitive)?.content != "false"
            seekableStart = st.longField("seekableStartMs")
            seekableEnd = st.longField("seekableEndMs")
            paused = (st["paused"] as? JsonPrimitive)?.content != "false"
            reportedQuality = (st["preferQuality"] as? JsonPrimitive)?.content
                ?: (st["quality"] as? JsonPrimitive)?.content
                ?: ""
            val n = sink.frameCount
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
            val scrub = scrubFrac
            if (scrub != null && windowEnd > windowStart) {
                val target = windowStart + (scrub * (windowEnd - windowStart)).toLong()
                if (kotlin.math.abs(position - target) < 1_000L)
                    scrubFrac = null
            }
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

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = videoId,
                onValueChange = { videoId = it.trim() },
                label = { Text("YouTube video id") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = ready,
            )
            Button(
                onClick = {
                    val s = session ?: return@Button
                    loading = true
                    error = null
                    val id = videoId
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                s.player.load(id, s.log, quality)
                                s.player.play(s.log)
                            }
                            val tracks = withContext(Dispatchers.Default) { pullCaptionTracks(s) }
                            captionTracks = tracks
                            if (captionVss != null && tracks.none { it.vssId == captionVss }) {
                                captionVss = null
                                captionLines = emptyList()
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse load failed: $t")
                            t.printStackTrace()
                        }
                        loading = false
                    }
                },
                enabled = ready && videoId.isNotEmpty(),
            ) { Text("Load") }
        }

        Text("State: $stateText", style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
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
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUALITY_CHOICES.forEach { (token, label) ->
                FilterChip(
                    selected = quality == token,
                    onClick = {
                        if (quality == token) return@FilterChip
                        val s = session ?: return@FilterChip
                        quality = token
                        scope.launch {
                            try {
                                withContext(Dispatchers.Default) { s.player.setQuality(token, s.log) }
                            } catch (t: Throwable) {
                                error = t.message ?: t.toString()
                                println("mse setQuality failed: $t")
                            }
                        }
                    },
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
                onClick = {
                    if (captionVss == null) return@FilterChip
                    val s = session ?: return@FilterChip
                    captionVss = null
                    captionLines = emptyList()
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.setCaption(null, s.log) }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse setCaption failed: $t")
                        }
                    }
                },
                enabled = ready,
                label = { Text("CC off") },
            )
            captionTracks.forEach { track ->
                FilterChip(
                    selected = captionVss == track.vssId,
                    onClick = {
                        if (captionVss == track.vssId) return@FilterChip
                        val s = session ?: return@FilterChip
                        captionVss = track.vssId
                        scope.launch {
                            try {
                                withContext(Dispatchers.Default) { s.player.setCaption(track.vssId, s.log) }
                            } catch (t: Throwable) {
                                error = t.message ?: t.toString()
                                println("mse setCaption failed: $t")
                            }
                        }
                    },
                    enabled = ready,
                    label = { Text(track.displayName) },
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            MseNativeVideo(
                surface = surface,
                player = session?.player,
                sink = sink,
                placeholder = when {
                    booting -> "Starting…"
                    loading -> "Loading video…"
                    quality == "zero" -> "Audio only"
                    else -> "Waiting for video…"
                },
                modifier = Modifier.fillMaxSize(),
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
            Button(
                onClick = {
                    overlayClicks++
                    println("mse compose overlay click $overlayClicks")
                },
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(if (overlayClicks == 0) "Compose click" else "Compose click ×$overlayClicks")
            }
            CaptionOverlay(
                lines = captionLines,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        Slider(
            value = scrubFrac
                ?: ((position - windowStart).toFloat() / windowSpan).coerceIn(0f, 1f),
            onValueChange = { frac -> scrubFrac = frac },
            onValueChangeFinished = {
                val frac = scrubFrac ?: return@Slider
                val s = session ?: return@Slider
                if (canSeek) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                val t = windowStart + (frac * windowSpan).toLong()
                                s.player.seekTo(t.coerceIn(windowStart, windowEnd), s.log)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                }
            },
            enabled = canSeek,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.play(s.log) }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse play failed: $t")
                        }
                    }
                },
                enabled = ready,
            ) { Text("Play") }
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.pause(s.log) }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse pause failed: $t")
                        }
                    }
                },
                enabled = ready,
            ) { Text("Pause") }
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                s.player.seekTo((position - 10_000).coerceIn(windowStart, windowEnd), s.log)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                },
                enabled = canSeek,
            ) { Text("−10s") }
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                s.player.seekTo((position + 10_000).coerceIn(windowStart, windowEnd), s.log)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                },
                enabled = canSeek,
            ) { Text("+10s") }
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.stop(s.log) }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse stop failed: $t")
                        }
                    }
                },
                enabled = ready,
            ) { Text("Stop") }
        }
        Spacer(Modifier.height(4.dp))
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
            columnIds.forEach { id ->
                key(id) {
                    val text = if (id < 0) "" else textHold[id].orEmpty()
                    CaptionLineRow(text, lineH)
                }
            }
        }
    }
}

@Composable
private fun CaptionLineRow(text: String, lineH: Dp) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .height(lineH)
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

private val QUALITY_CHOICES = listOf(
    "zero" to "Audio",
    "auto" to "Auto",
    "tiny" to "144p",
    "small" to "240p",
    "medium" to "360p",
    "large" to "480p",
    "hd720" to "720p",
    "hd1080" to "1080p",
    "hd1440" to "1440p",
)

private fun JsonObject.longField(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

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
