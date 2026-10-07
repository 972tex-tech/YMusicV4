package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource

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
            TransitionArt(url, blurWhileLoading = false)
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.36f)))
        val side = minOf(maxHeight * 0.86f, maxWidth * 0.32f)
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
                TransitionArt(url, blurWhileLoading = true)
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

private const val ART_FADE_MS = 450
private const val ART_PULSE_MS = 1100
private val ArtLoadingBlur = 18.dp

/**
 * Now Playing's art, never blank between tracks: the previous picture stays (blurred when
 * [blurWhileLoading]) until the new one is decoded, which then fades in over it. A cached copy at
 * another size (the mini player's) shows at once and the sharp decode fades over it; with nothing
 * shown yet, a pulsing placeholder stands in.
 */
@Composable
private fun BoxScope.TransitionArt(url: String?, blurWhileLoading: Boolean) {
    val loader = LocalImageLoader.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Seeded from memory on the first frame, so an open with the art already cached never blinks.
    var shown by remember {
        mutableStateOf(if (url != null) loader?.peekAnySize(imageCacheKey(url))?.let { ShownArt(it, url) } else null)
    }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(url != null && shown == null) }
    val fade = remember { Animatable(1f) }
    val stale = loading && shown?.url != url
    val previousBlur by animateDpAsState(if (blurWhileLoading && stale) ArtLoadingBlur else 0.dp)

    LaunchedEffect(url, sizePx, loader) {
        if (url == null) {
            shown = null
            loading = false
            return@LaunchedEffect
        }
        if (loader == null || sizePx == IntSize.Zero) return@LaunchedEffect
        val key = imageCacheKey(url)
        suspend fun reveal(next: ImageBitmap) {
            if (shown == null) {
                shown = ShownArt(next, url)
                return
            }
            fade.snapTo(0f)
            incoming = next
            try {
                fade.animateTo(1f, tween(ART_FADE_MS, easing = FastOutSlowInEasing))
            } finally {
                shown = ShownArt(next, url)
                incoming = null
            }
        }
        val exact = loader.peek(url, sizePx.width, sizePx.height, cacheKey = key)
        if (exact != null) {
            loading = false
            if (shown?.url == url) shown = ShownArt(exact, url) else reveal(exact)
            return@LaunchedEffect
        }
        loading = true
        if (shown?.url != url) loader.peekAnySize(key)?.let { reveal(it) }
        val sharp = loader.loadOrNull(url, sizePx.width, sizePx.height, cacheKey = key)
        loading = false
        if (sharp != null) reveal(sharp)
    }

    Box(Modifier.matchParentSize().onSizeChanged { sizePx = it }) {
        val base = shown
        if (base == null) {
            if (loading) ArtLoadingPulse()
        } else {
            Image(
                base.bitmap,
                contentDescription = null,
                modifier = Modifier.matchParentSize().blur(previousBlur),
                contentScale = ContentScale.Crop,
            )
        }
        incoming?.let { next ->
            Image(
                next,
                contentDescription = null,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = fade.value },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private class ShownArt(val bitmap: ImageBitmap, val url: String)

@Composable
private fun BoxScope.ArtLoadingPulse() {
    val alpha by rememberInfiniteTransition().animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(ART_PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    Box(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha }.background(DesignColors.Placeholder))
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
