package io.ymusic.playground

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    installStdoutTimestamps()
    System.setProperty("compose.interop.blending", "true")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "YMusic · MSE playground",
            state = rememberWindowState(size = DpSize(960.dp, 720.dp)),
        ) {
            MaterialTheme {
                Surface { YtMsePlayerScreen() }
            }
        }
    }
}
