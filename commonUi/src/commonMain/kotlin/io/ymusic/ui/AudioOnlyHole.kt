package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.ymusic.imageloader.imageCacheKey
import io.ymusic.player.Player
import io.ymusic.player.VideoQualityState
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.overlay_fps
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

private const val WAVE_BARS = 40

/** STARTED while the window is on screen, focused or not; drops when minimized/backgrounded. */
@Composable
private fun windowVisible(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/** Audio-only by the user's pick, or because the source has no video to show. */
@Composable
internal fun showsAudioOnly(player: Player): Boolean {
    val config by player.config.collectAsState()
    val quality by player.videoQuality.collectAsState()
    return config.audioOnly || quality.actualQuality == VideoQualityState.AUDIO_ONLY
}

/** Cropped thumbnail filling the hole — mini player audio-only. */
@Composable
internal fun AudioOnlyCover(
    url: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.clip(RectangleShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ArtImage(url, RectangleShape)
    }
}

/**
 * 16:9 audio-only hole: blurred/darkened thumbnail fill, sharp square art on the left,
 * live PCM bars on the right. No badge.
 */
@Composable
internal fun AudioOnlyArtWave(
    player: Player,
    url: String?,
    modifier: Modifier = Modifier,
) {
    val status by player.status.collectAsState()
    val sampling = status.playWhenReady && windowVisible()
    val levels by produceState(FloatArray(WAVE_BARS), player, sampling) {
        val buf = FloatArray(WAVE_BARS)
        if (!sampling) {
            var cur = value
            var n = 0
            while (isActive && n < 12 && cur.any { it > 0.012f }) {
                cur = FloatArray(cur.size) { i -> cur[i] * 0.78f }
                value = cur
                n++
                delay(33)
            }
            if (isActive && cur.any { it > 0.012f }) value = FloatArray(WAVE_BARS)
            return@produceState
        }
        while (isActive) {
            val n = player.pcmLevels(buf)
            if (n > 0) {
                if (n < buf.size) buf.fill(0f, n, buf.size)
                value = buf.copyOf()
            }
            delay(33)
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        Box(Modifier.matchParentSize().blur(28.dp)) {
            ArtImage(url, RectangleShape)
        }
        val side = minOf(maxHeight * 0.86f, maxWidth * 0.32f)
        val sidePx = with(LocalDensity.current) { side.roundToPx() }
        val art = rememberArtTransition(url, IntSize(sidePx, sidePx))
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.36f)))
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(side)
                    .shadow(10.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                if (art.shading) ArtShade()
                ArtLayer(art)
            }
            Spacer(Modifier.width(14.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val n = levels.size.coerceAtLeast(1)
                val gap = size.width * 0.014f
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1.5f)
                val mid = size.height * 0.5f
                val maxH = minOf(size.height * 0.92f, side.toPx() * 2f)
                for (i in 0 until n) {
                    val h = (levels[i].coerceIn(0f, 1f) * maxH).coerceAtLeast(2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.92f),
                        topLeft = Offset(i * (barW + gap), mid - h * 0.5f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW * 0.45f, barW * 0.45f),
                    )
                }
            }
        }
    }
}

private const val ART_FADE_OUT_MS = 260
private const val ART_FADE_IN_MS = 420
private const val ART_MIN_SHADE_MS = 220L
private const val ART_SHIMMER_MS = 1300
private const val ART_RESIZE_SETTLE_MS = 400L

/**
 * Now Playing's square art. Every url change runs the same
 * sequence — the current picture fades out over the shade, the shade holds at least
 * [ART_MIN_SHADE_MS] (longer while the load runs), the new picture fades in — whatever the cache
 * holds. A view that grows only swaps in a sharper decode of the same picture, no transition.
 */
private class ArtTransition {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var url: String? = null
    var shading by mutableStateOf(false)
    val alpha = Animatable(0f)
}

@Composable
private fun rememberArtTransition(url: String?, decodeSize: IntSize): ArtTransition {
    val loader = LocalImageLoader.current
    val state = remember { ArtTransition() }
    val size by rememberUpdatedState(decodeSize)
    val ready = decodeSize.width > 0 && decodeSize.height > 0

    LaunchedEffect(url, loader, ready) {
        if (!ready || (state.url == url && state.bitmap != null)) return@LaunchedEffect
        coroutineScope {
            val next = async {
                if (url == null || loader == null) {
                    null
                } else {
                    val key = imageCacheKey(url)
                    loader.peek(url, size.width, size.height, cacheKey = key)
                        ?: loader.loadOrNull(url, size.width, size.height, cacheKey = key)
                }
            }
            val shadeFrom = TimeSource.Monotonic.markNow()
            state.shading = url != null
            if (state.bitmap != null) state.alpha.animateTo(0f, tween(ART_FADE_OUT_MS, easing = FastOutSlowInEasing))
            state.bitmap = null
            state.url = url
            val bitmap = next.await()
            val held = shadeFrom.elapsedNow().inWholeMilliseconds
            if (held < ART_FADE_OUT_MS + ART_MIN_SHADE_MS) delay(ART_FADE_OUT_MS + ART_MIN_SHADE_MS - held)
            if (bitmap == null) {
                state.shading = false
                return@coroutineScope
            }
            state.alpha.snapTo(0f)
            state.bitmap = bitmap
            state.alpha.animateTo(1f, tween(ART_FADE_IN_MS, easing = FastOutSlowInEasing))
            state.shading = false
        }
    }

    LaunchedEffect(decodeSize) {
        val shownUrl = state.url ?: return@LaunchedEffect
        if (state.bitmap == null || shownUrl != url || loader == null) return@LaunchedEffect
        delay(ART_RESIZE_SETTLE_MS)
        val key = imageCacheKey(shownUrl)
        val sharper = loader.peek(shownUrl, decodeSize.width, decodeSize.height, cacheKey = key)
            ?: loader.loadOrNull(shownUrl, decodeSize.width, decodeSize.height, cacheKey = key)
        if (sharper != null && state.url == shownUrl && state.bitmap != null) state.bitmap = sharper
    }
    return state
}

@Composable
private fun BoxScope.ArtLayer(art: ArtTransition, modifier: Modifier = Modifier) {
    val bitmap = art.bitmap ?: return
    Image(
        bitmap,
        contentDescription = null,
        modifier = modifier.matchParentSize().graphicsLayer { alpha = art.alpha.value },
        contentScale = ContentScale.Crop,
    )
}

/** A soft light band sweeping a dim square while the next picture is on its way; dark in every
 *  theme, since it sits on the dark hole between two pictures. */
@Composable
private fun BoxScope.ArtShade() {
    val sweep by rememberInfiniteTransition().animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(ART_SHIMMER_MS, easing = LinearEasing)),
    )
    Box(
        Modifier.matchParentSize().background(Color.White.copy(alpha = 0.07f)).drawWithCache {
            val band = size.width * 0.6f
            val x = sweep * size.width
            val brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x, size.height),
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}

/** Video hole plus the audio-only overlay when [showsAudioOnly]. */
@Composable
internal fun PlayerSurfaceHole(
    player: Player,
    surface: @Composable (Modifier) -> Unit,
    artUrl: String?,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    wave: Boolean = true,
    settings: SettingsStore? = null,
) {
    val audioOnly = showsAudioOnly(player)
    Box(modifier) {
        VideoHole(player, surface, Modifier.fillMaxSize(), fill = fill)
        if (audioOnly) {
            if (wave) AudioOnlyArtWave(player, artUrl, Modifier.fillMaxSize())
            else AudioOnlyCover(artUrl, Modifier.fillMaxSize())
        } else if (settings != null) {
            FpsBadge(player, settings, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

@Composable
private fun FpsBadge(player: Player, settings: SettingsStore, modifier: Modifier = Modifier) {
    val enabled by settings.flow(AppSettings.showFps).collectAsState()
    if (!enabled) return
    FpsBadgeActive(player, modifier)
}

@Composable
private fun FpsBadgeActive(player: Player, modifier: Modifier = Modifier) {
    val status by player.status.collectAsState()
    val sampling = status.playWhenReady && windowVisible()
    val fps by produceState(0, player, sampling) {
        if (!sampling) {
            value = 0
            return@produceState
        }
        var prev = player.presentedFrames()
        while (isActive) {
            delay(1_000)
            val n = player.presentedFrames()
            value = (n - prev).coerceAtLeast(0)
            prev = n
        }
    }
    if (fps <= 0) return
    Text(
        stringResource(Res.string.overlay_fps, fps),
        style = TextStyle(fontSize = 13.sp, color = Color.White),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
