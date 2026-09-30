package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The app's slim seek bar, built from scratch: the M3 `Slider`'s internal layout does not center
 * its thumb/track slots inside a height-constrained row — the thumb clips into a floating dot and
 * the bar sits off the row's centerline. Here the track and thumb are children of ONE CenterStart
 * box, so everything sits on the centerline by construction, at any [barHeight].
 *
 * Tap seeks. A drag scrubs — the shown position holds locally so the live tick doesn't fight the
 * thumb — and [onSeek] fires ONCE on release (a cancelled drag discards), so scrubbing never
 * floods the engine with intermediate seeks.
 */
@Composable
fun SlimSeekBar(
    fraction: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    trackColor: Color,
    modifier: Modifier = Modifier,
    fillColor: Color = DesignColors.Accent,
    bufferedFraction: Float = 0f,
    bufferedColor: Color = fillColor.copy(alpha = 0.35f),
    thumbColor: Color = DesignColors.Accent,
    thumbSize: Dp = 13.dp,
    trackHeight: Dp = 3.dp,
    barHeight: Dp = 20.dp,
    thumbShadow: Dp = 1.dp,
    /** Live fraction while dragging. Null on release or cancel. */
    onScrub: ((Float?) -> Unit)? = null,
) {
    var widthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val buffered = bufferedFraction.coerceIn(0f, 1f)
    val seek = rememberUpdatedState(onSeek)
    val scrub = rememberUpdatedState(onScrub)
    Box(
        modifier
            .height(barHeight)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> seek.value((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = f
                        scrub.value?.invoke(f)
                        VideoOverlayNative.scrubbing = true
                        VideoOverlayNative.show()
                    },
                    onHorizontalDrag = { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragging = f
                        scrub.value?.invoke(f)
                    },
                    onDragEnd = {
                        dragging?.let { seek.value(it) }
                        dragging = null
                        scrub.value?.invoke(null)
                        VideoOverlayNative.scrubbing = false
                    },
                    onDragCancel = {
                        dragging = null
                        scrub.value?.invoke(null)
                        VideoOverlayNative.scrubbing = false
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(trackColor),
        ) {
            if (buffered > 0f) {
                Box(Modifier.fillMaxWidth(buffered).fillMaxHeight().background(bufferedColor))
            }
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(fillColor))
        }
        Box(
            Modifier
                .offset { IntOffset((shown * (widthPx - thumbSize.toPx())).roundToInt().coerceAtLeast(0), 0) }
                .size(thumbSize)
                .shadow(thumbShadow, CircleShape)
                .background(thumbColor, CircleShape),
        )
    }
}
