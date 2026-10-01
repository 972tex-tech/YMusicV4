package io.ymusic.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.cinterop.mse_ios_park_video
import io.ymusic.mse.cinterop.mse_ios_recover_playback
import io.ymusic.player.mse.MsePlayer
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode

/**
 * One [AVSampleBufferDisplayLayer] for the process. MiniPlayer and Now Playing reparent this
 * view (same as Android's TextureView). Creating a new layer on each hole swap flushes VT
 * and after a few open/close cycles the picture dies (broken frames, then black).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosMseHole {
    val view: MseVtView = MseVtView()

    fun layerPtr(): COpaquePointer? =
        interpretCPointer<COpaque>(view.vtLayer.objcPtr())
}

/** Swift [iOSApp] calls this on scenePhase == .background / .active. */
@OptIn(ExperimentalForeignApi::class)
fun iosMseWentBackground() {
    mse_ios_park_video()
}

@OptIn(ExperimentalForeignApi::class)
fun iosMseBecameActive() {
    mse_ios_recover_playback()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosMseVideo(hole: IosMseHole, player: MsePlayer, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                if (size.width > 1 && size.height > 1) player.setViewport(size.width, size.height)
            },
    ) {
        UIKitView(
            factory = {
                UIView().also { host ->
                    host.backgroundColor = UIColor.blackColor
                    host.opaque = true
                    adopt(hole.view, host)
                    iosNotifyPipPrepare()
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { host ->
                host.backgroundColor = UIColor.blackColor
                host.opaque = true
                adopt(hole.view, host)
                if (hole.view.window != null) iosNotifyPipPrepare()
            },
            onRelease = { host ->
                if (hole.view.superview === host) hole.view.removeFromSuperview()
            },
            properties = UIKitInteropProperties(
                isInteractive = false,
                isNativeAccessibilityEnabled = false,
            ),
        )
    }
    LaunchedEffect(player) {
        val lp = hole.layerPtr() ?: return@LaunchedEffect
        player.setVideoSurface(MseVideoSurface().apply { layer = lp })
        iosNotifyPipPrepare()
        kotlinx.coroutines.coroutineScope {
            launch { player.status.collect { iosNotifyPipPrepare() } }
            launch { player.videoQuality.collect { iosNotifyPipPrepare() } }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun adopt(child: UIView, host: UIView) {
    if (child.superview !== host) {
        child.removeFromSuperview()
        host.addSubview(child)
    }
    child.setFrame(host.bounds)
    child.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
}

@OptIn(ExperimentalForeignApi::class)
internal class MseVtView : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)) {
    val vtLayer = AVSampleBufferDisplayLayer()

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        opaque = true
        contentMode = UIViewContentMode.UIViewContentModeScaleToFill
        vtLayer.videoGravity = AVLayerVideoGravityResize
        vtLayer.backgroundColor = UIColor.blackColor.CGColor
        layer.addSublayer(vtLayer)
        setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        vtLayer.frame = bounds
        CATransaction.commit()
    }
}
