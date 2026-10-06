package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.ymusic.player.CaptionLine

/**
 * YouTube-style caption box, bottom-anchored: each [CaptionLine] is one cue whose `\n`s are hard breaks and
 * whose long rows wrap — never truncated. The box is at least two rows tall and grows to fit the newest cue.
 * A cue that joins an overlapping window (roll-up) scrolls in when it is a single row; anything taller swaps.
 */
@Composable
fun CaptionOverlay(lines: List<CaptionLine>, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val strip = remember { CaptionStrip() }
    val frame = strip.advance(lines)
    val scroll = remember(frame.generation) { Animatable(if (frame.tailCount > 0) 1f else 0f) }
    LaunchedEffect(frame.generation) {
        scroll.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }

    Layout(
        content = {
            frame.entries.forEach { entry ->
                key(entry.id) { CaptionCue(entry.text, style) }
            }
        },
        modifier = modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val rowPx = style.lineHeight.roundToPx() + (DesignSpacing.Space2 * 2).roundToPx()
        val left = (constraints.maxWidth * 0.2f).toInt()
        val cueWidth = (constraints.maxWidth - left - DesignSpacing.Space16.roundToPx()).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = cueWidth)) }
        val boxHeight = maxOf(rowPx * 2, placeables.lastOrNull()?.height ?: 0)
        val tailHeight = placeables.takeLast(frame.tailCount).sumOf { it.height }
        val rollUp = if (tailHeight < rowPx * 3 / 2) tailHeight else 0
        layout(constraints.maxWidth, boxHeight) {
            var y = boxHeight - placeables.sumOf { it.height } + (scroll.value * rollUp).toInt()
            placeables.forEach { placeable ->
                placeable.place(left, y)
                y += placeable.height
            }
        }
    }
}

@Composable
private fun CaptionCue(text: String, style: TextStyle) {
    Text(
        text = text,
        color = Color.White,
        style = style,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space2),
    )
}

private class CaptionFrame(val entries: List<CaptionLine>, val tailCount: Int, val generation: Int)

/** Keeps cues that just left the window long enough to scroll out above the box. Idempotent per input. */
private class CaptionStrip {
    private val held = mutableMapOf<Int, String>()
    private var lastIds: List<Int> = emptyList()
    private var entryIds: List<Int> = emptyList()
    private var tailCount = 0
    private var generation = 0

    fun advance(lines: List<CaptionLine>): CaptionFrame {
        val ids = lines.map { it.id }
        if (ids != lastIds) {
            val sequential = lastIds.isNotEmpty() && ids.any { it in lastIds }
            if (sequential) {
                val tail = ids.filter { it !in entryIds }
                entryIds = (entryIds.filter { it !in tail } + tail).takeLast(ids.size + 1)
                tailCount = tail.size
            } else {
                entryIds = ids
                tailCount = 0
            }
            lastIds = ids
            generation++
        }
        val texts = lines.associate { it.id to it.text }
        val entries = entryIds.mapNotNull { id ->
            texts[id]?.let { CaptionLine(id, it) } ?: held[id]?.let { CaptionLine(id, it) }
        }
        held.clear()
        entries.forEach { held[it.id] = it.text }
        return CaptionFrame(entries, tailCount, generation)
    }
}
