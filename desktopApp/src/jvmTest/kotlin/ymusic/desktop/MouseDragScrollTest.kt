package ymusic.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test

/** Dev harness: does dragging (mouse vs touch) scroll a LazyColumn on desktop? */
class MouseDragScrollTest {

    private fun scrollAfterDrag(pointer: PointerType): Pair<Int, Int> {
        val state = LazyListState()
        ImageComposeScene(width = 400, height = 300) {
            LazyColumn(state = state) {
                items(50) {
                    Box(Modifier.fillMaxWidth().height(60.dp))
                }
            }
        }.use { scene ->
            scene.render()
            val start = Offset(200f, 250f)
            scene.sendPointerEvent(PointerEventType.Press, start, type = pointer)
            var t = 20L
            for (step in 1..10) {
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    start - Offset(0f, step * 20f),
                    timeMillis = t,
                    type = pointer,
                )
                t += 16
            }
            scene.sendPointerEvent(PointerEventType.Release, start - Offset(0f, 200f), timeMillis = t, type = pointer)
            scene.render()
        }
        return state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    }

    @Test
    fun report() {
        val (mi, mo) = scrollAfterDrag(PointerType.Mouse)
        println("mouse drag 200px up : firstVisible=$mi offset=$mo")
        val (ti, to) = scrollAfterDrag(PointerType.Touch)
        println("touch drag 200px up : firstVisible=$ti offset=$to")
    }
}
