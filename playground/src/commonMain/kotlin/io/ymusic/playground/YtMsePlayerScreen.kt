package io.ymusic.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    var paused by remember { mutableStateOf(true) }
    val sink = remember { MseVideoSink() }
    var frames by remember { mutableStateOf(0) }
    var frameLabel by remember { mutableStateOf("none") }
    var fpsText by remember { mutableStateOf("-- fps") }
    var overlayClicks by remember { mutableStateOf(0) }
    var scrubFrac by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        try {
            val s = withContext(Dispatchers.Default) {
                bootMseSession(surface)
            }
            withContext(Dispatchers.Default) { s.player.init(s.log) }
            session = s
            withContext(Dispatchers.Default) {
                s.player.load(videoId, s.log)
                s.player.play(s.log)
            }
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
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        var fpsCount = sink.frameCount
        var fpsMark = TimeSource.Monotonic.markNow()
        while (true) {
            val st = withContext(Dispatchers.Default) { s.player.status(s.log) }
            position = st.longField("positionMs")
            duration = st.longField("durationMs")
            paused = (st["paused"] as? JsonPrimitive)?.content != "false"
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
            if (scrub != null && duration > 0L) {
                val target = (scrub * duration).toLong()
                if (kotlin.math.abs(position - target) < 1_000L)
                    scrubFrac = null
            }
            delay(250)
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
                                s.player.load(id, s.log)
                                s.player.play(s.log)
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
            "${formatClock(position)} / ${if (duration > 0L) formatClock(duration) else "--:--"}  " +
                "$fpsText  frames=$frames  frame=$frameLabel",
            style = MaterialTheme.typography.bodySmall,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            MseNativeVideo(
                surface = surface,
                player = session?.player,
                sink = sink,
                placeholder = when {
                    booting -> "Starting…"
                    loading -> "Loading video…"
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
        }

        Slider(
            value = scrubFrac ?: if (duration > 0L) position.toFloat() / duration else 0f,
            onValueChange = { frac -> scrubFrac = frac },
            onValueChangeFinished = {
                val frac = scrubFrac ?: return@Slider
                val s = session ?: return@Slider
                if (duration > 0L) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) {
                                s.player.seekTo((frac * duration).toLong(), s.log)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                }
            },
            enabled = ready && duration > 0L,
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
                                s.player.seekTo((position - 10_000).coerceAtLeast(0L), s.log)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                },
                enabled = ready,
            ) { Text("−10s") }
            Button(
                onClick = {
                    val s = session ?: return@Button
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { s.player.seekTo(position + 10_000, s.log) }
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                            println("mse seek failed: $t")
                        }
                    }
                },
                enabled = ready,
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

private fun JsonObject.longField(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

private fun formatClock(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
