package ymusic.playground

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ymusic.player.LocalPlayerContext
import ymusic.player.createLocalPlayer
import java.awt.FileDialog
import java.awt.Frame

/**
 * Desktop (JVM) playground app. Plays a local file through the VLCJ backend of :player. Requires
 * libVLC to be discoverable (VLC installed) — otherwise the player construction throws E60020.
 */
fun main() = application {
    val player = remember { createLocalPlayer(LocalPlayerContext()) }
    Window(
        onCloseRequest = {
            player.release()
            exitApplication()
        },
        title = "YMusic · LocalPlayer",
        state = rememberWindowState(size = DpSize(520.dp, 520.dp)),
    ) {
        MaterialTheme {
            Surface {
                PlayerScreen(player = player, filePicker = { pickMediaFile() })
            }
        }
    }
}

/** Native AWT open dialog — blocking/modal, runs on the Compose main (event) thread. */
private fun pickMediaFile(): String? {
    val dialog = FileDialog(null as Frame?, "Choose a media file", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return dir + file
}
