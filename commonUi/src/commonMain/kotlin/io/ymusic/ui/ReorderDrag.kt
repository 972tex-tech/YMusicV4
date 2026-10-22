package io.ymusic.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

/**
 * Drag-to-reorder for a [LazyListState] list. A drag on a row's handle starts it; rows swap live through
 * `onMove` whenever the dragged row's center crosses a neighbour, and the list autoscrolls while the row
 * is held past either viewport edge. [indexOffset] maps row indices to lazy-item indices (items laid out
 * before the rows, e.g. a header).
 */
internal class ReorderDragState(
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
        // A keyed list keeps its scroll anchored to the first visible item's key; moving that item
        // would scroll the list along with it.
        val first = listState.firstVisibleItemIndex
        if (first == info.index || first == target.index) {
            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
        }
        onMove(from, target.index - indexOffset)
        // The moved row is laid out at the target's old slot next frame; fold the base-position
        // change into the translation so it stays under the pointer.
        dragOffset += info.offset - target.offset
        draggingIndex = target.index - indexOffset
    }
}

@Composable
internal fun rememberReorderDragState(
    listState: LazyListState,
    indexOffset: Int,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderDragState {
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState, indexOffset) {
        ReorderDragState(listState, indexOffset) { from, to -> currentOnMove(from, to) }
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
