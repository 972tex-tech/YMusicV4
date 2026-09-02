package io.ymusic.playground

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry: the same Compose [YtMsePlayerScreen] as the desktop MSE tab.
 * Video is a UIKit hole ([MseNativeVideo]); chrome stays in Compose.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            YtMsePlayerScreen()
        }
    }
}
