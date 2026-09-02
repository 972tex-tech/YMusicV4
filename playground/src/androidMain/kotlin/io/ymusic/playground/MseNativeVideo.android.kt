package io.ymusic.playground

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * TextureView hole: C blits BGRA into an ANativeWindow. Compose chrome sits on top.
 */
@Composable
internal actual fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier,
) {
    var androidSurface by remember { mutableStateOf<Surface?>(null) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        androidSurface = Surface(st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        androidSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )

    LaunchedEffect(player, androidSurface) {
        val p = player
        val s = androidSurface
        if (p != null && s != null) p.setVideoSurface(MseVideoSurface(s))
        else p?.setVideoSurface(null)
    }
    DisposableEffect(player) {
        onDispose { player?.setVideoSurface(null) }
    }
    LaunchedEffect(surface) {
        val wh = IntArray(2)
        while (isActive) {
            val host = surface.host
            if (host != 0L) {
                sink.frameCount = MseHostNative.frames(host)
                if (MseHostNative.videoSize(host, wh)) {
                    sink.frameW = wh[0]
                    sink.frameH = wh[1]
                }
            }
            delay(16)
        }
    }
}
