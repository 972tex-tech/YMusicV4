package io.ymusic.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test

/** Dev harness: does press → tiny mouse move → release still count as a click? */
class ClickSlopTest {

    private fun clicksAfterJitter(inLazyList: Boolean, movePx: Float): Int {
        var clicks = 0
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            if (inLazyList) {
                LazyColumn {
                    items(5) { i ->
                        Box(
                            Modifier.fillMaxWidth().height(60.dp).clickable { clicks++ },
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(300.dp).clickable { clicks++ })
            }
        }
        ImageComposeScene(width = 400, height = 300, content = content).use { scene ->
            scene.render()
            val start = Offset(200f, 30f)
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse)
            if (movePx > 0f) {
                scene.sendPointerEvent(PointerEventType.Move, start + Offset(movePx, 0f), type = PointerType.Mouse)
            }
            scene.sendPointerEvent(PointerEventType.Release, start + Offset(movePx, 0f), type = PointerType.Mouse)
            scene.render()
        }
        return clicks
    }

    private fun clicksInsideDrawer(movePx: Float, gestures: Boolean): Int {
        var clicks = 0
        ImageComposeScene(width = 400, height = 300) {
            androidx.compose.material3.ModalNavigationDrawer(
                drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed),
                gesturesEnabled = gestures,
                drawerContent = {},
            ) {
                Box(Modifier.fillMaxWidth().height(300.dp).clickable { clicks++ })
            }
        }.use { scene ->
            scene.render()
            val start = Offset(200f, 150f)
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse)
            if (movePx > 0f) {
                scene.sendPointerEvent(PointerEventType.Move, start + Offset(movePx, 0f), type = PointerType.Mouse)
            }
            scene.sendPointerEvent(PointerEventType.Release, start + Offset(movePx, 0f), type = PointerType.Mouse)
            scene.render()
        }
        return clicks
    }

    @Test
    fun report() {
        println("plain box,  no move : clicks=" + clicksAfterJitter(inLazyList = false, movePx = 0f))
        println("plain box,  1px move: clicks=" + clicksAfterJitter(inLazyList = false, movePx = 1f))
        println("plain box,  5px move: clicks=" + clicksAfterJitter(inLazyList = false, movePx = 5f))
        println("lazy list,  no move : clicks=" + clicksAfterJitter(inLazyList = true, movePx = 0f))
        println("lazy list,  1px move: clicks=" + clicksAfterJitter(inLazyList = true, movePx = 1f))
        println("lazy list,  5px move: clicks=" + clicksAfterJitter(inLazyList = true, movePx = 5f))
        println("drawer,     no move : clicks=" + clicksInsideDrawer(movePx = 0f, gestures = true))
        println("drawer,     1px move: clicks=" + clicksInsideDrawer(movePx = 1f, gestures = true))
        println("drawer off, 1px move: clicks=" + clicksInsideDrawer(movePx = 1f, gestures = false))
    }
}
