package ymusic.playground

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import ymusic.player.LocalPlayer
import ymusic.player.localVideoSurface

@Composable
actual fun PlayerSurface(player: LocalPlayer, modifier: Modifier) {
    val surface = remember(player) { player.localVideoSurface() } ?: return
    val frame by surface.frame.collectAsState()
    val bitmap = remember(frame) { frame?.toComposeImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.background(Color.Black),
        )
    } else {
        Box(modifier.fillMaxSize().background(Color.Black))
    }
}
