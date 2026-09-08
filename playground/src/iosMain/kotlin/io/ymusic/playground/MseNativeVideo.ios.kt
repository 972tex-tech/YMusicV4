package io.ymusic.playground

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
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode

/**
 * UIKit hole: VideoToolbox (or software BGRA) enqueues into
 * [AVSampleBufferDisplayLayer]. Compose sizes the view to the picture
 * aspect; the layer fills that view. Touches stay in Compose.
 */
@OptIn(ExperimentalForeignApi::class)
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
    var layerPtr by remember { mutableStateOf<COpaquePointer?>(null) }
    remember {
        sink.surfaceReady = false
        true
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val hole =
            if (pictureWidth > 0 && pictureHeight > 0) {
                Modifier.aspectRatio(pictureWidth.toFloat() / pictureHeight.toFloat())
            } else {
                Modifier.fillMaxSize()
            }
        UIKitView(
            factory = {
                MseVtView().also { layerPtr = interpretCPointer<COpaque>(it.vtLayer.objcPtr()) }
            },
            modifier = hole,
            update = { view ->
                layerPtr = interpretCPointer<COpaque>(view.vtLayer.objcPtr())
            },
            properties = UIKitInteropProperties(
                isInteractive = false,
                isNativeAccessibilityEnabled = false,
            ),
        )
    }

    LaunchedEffect(player, layerPtr) {
        val p = player ?: return@LaunchedEffect
        val lp = layerPtr
        if (lp != null) {
            MseHostNative.log("[ios-mse] attach vt layer")
            p.setVideoSurface(MseVideoSurface().apply { layer = lp })
            sink.surfaceReady = true
        } else {
            MseHostNative.log("[ios-mse] detach vt layer")
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

@OptIn(ExperimentalForeignApi::class)
private class MseVtView : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)) {
    val vtLayer = AVSampleBufferDisplayLayer()

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        opaque = true
        contentMode = UIViewContentMode.UIViewContentModeScaleToFill
        vtLayer.videoGravity = AVLayerVideoGravityResize
        layer.addSublayer(vtLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        vtLayer.frame = bounds
        CATransaction.commit()
    }
}
