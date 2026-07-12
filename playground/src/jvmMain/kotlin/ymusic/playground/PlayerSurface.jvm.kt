package ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import ymusic.player.LocalPlayer
import ymusic.player.awtVideoComponent

@Composable
actual fun PlayerSurface(player: LocalPlayer, modifier: Modifier) {
    val component = remember(player) { player.awtVideoComponent() }
    if (component != null) {
        SwingPanel(background = Color.Black, factory = { component }, modifier = modifier)
    }
}
