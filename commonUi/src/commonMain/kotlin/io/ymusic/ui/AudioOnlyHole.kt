package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val focused = LocalWindowInfo.current.isWindowFocused
    val sampling = status.playWhenReady && focused
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
                ArtImage(url, RoundedCornerShape(8.dp))
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
    val focused = LocalWindowInfo.current.isWindowFocused
    val sampling = status.playWhenReady && focused
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
