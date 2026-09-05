package io.ymusic.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.ymusic.player.CaptionLine

/** YouTube-style 2-line roll-up. Paint-on updates text on a stable [CaptionLine.id] without moving the row. */
@Composable
fun CaptionOverlay(lines: List<CaptionLine>, modifier: Modifier = Modifier) {
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
            if (columnIds.isNotEmpty()) offset.animateTo(offset.value - step, spec)
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
