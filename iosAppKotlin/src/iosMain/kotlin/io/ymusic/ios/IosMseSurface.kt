package io.ymusic.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.mse.MsePlayer
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode

/**
 * Same VideoToolbox hole as `:playground` [io.ymusic.playground.MseNativeVideo]: Compose sizes
 * the view, [AVSampleBufferDisplayLayer] fills it, MSE C enqueues samples.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosMseVideo(player: MsePlayer, modifier: Modifier = Modifier) {
    var layerPtr by remember { mutableStateOf<COpaquePointer?>(null) }
    Box(modifier.onSizeChanged { player.setViewport(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }) {
        UIKitView(
            factory = {
                MseVtView().also { layerPtr = interpretCPointer<COpaque>(it.vtLayer.objcPtr()) }
            },
            modifier = Modifier.fillMaxSize(),
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
        val lp = layerPtr ?: return@LaunchedEffect
        player.setVideoSurface(MseVideoSurface().apply { layer = lp })
    }
    DisposableEffect(player) {
        onDispose { player.setVideoSurface(null) }
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
