package io.ymusic.playground

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * TextureView hole: MediaCodec renders into the Surface at coded size;
 * TextureView GPU-scales that buffer to the view. Size the view to the
 * picture aspect so that scale is uniform (letterbox in the hole).
 */
@Composable
internal actual fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier,
    pictureWidth: Int,
    pictureHeight: Int,
) {
    var androidSurface by remember { mutableStateOf<Surface?>(null) }
    remember {
        sink.surfaceReady = false
        true
    }

    fun bindTexture(tv: TextureView) {
        val st = tv.surfaceTexture
        if (!tv.isAvailable || st == null) return
        val cur = androidSurface
        if (cur != null && cur.isValid) {
            sink.surfaceReady = true
            return
        }
        androidSurface = Surface(st)
        sink.surfaceReady = true
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val tvMod =
            if (pictureWidth > 0 && pictureHeight > 0) {
                Modifier.aspectRatio(pictureWidth.toFloat() / pictureHeight.toFloat())
            } else {
                Modifier.fillMaxSize()
            }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            Log.i("mse", "texture available ${w}x$h")
                            androidSurface = Surface(st)
                            sink.surfaceReady = true
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            Log.i("mse", "texture size ${w}x$h")
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            Log.i("mse", "texture destroyed")
                            androidSurface = null
                            sink.surfaceReady = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    post { bindTexture(this) }
                }
            },
            update = { bindTexture(it) },
            modifier = tvMod,
        )
    }

    LaunchedEffect(player, androidSurface) {
        val p = player ?: return@LaunchedEffect
        val s = androidSurface
        if (s != null && s.isValid) {
            Log.i("mse", "attach video surface")
            p.setVideoSurface(MseVideoSurface(s))
            sink.surfaceReady = true
        } else {
            Log.i("mse", "detach video surface")
            p.setVideoSurface(null)
            sink.surfaceReady = false
        }
    }
    DisposableEffect(player) {
        onDispose {
            player?.setVideoSurface(null)
            sink.surfaceReady = false
        }
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
