package io.ymusic.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

/** iOS local-player context: the shared host in which the engine owns its AVPlayerLayer root. */
actual class LocalPlayerContext(val videoContainer: UIView? = null)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer =
    AppleLocalPlayer { player -> context.videoContainer?.let { IosApplePlayerSurface(it, player) } }

@OptIn(ExperimentalForeignApi::class)
private class IosApplePlayerSurface(
    private val container: UIView,
    player: AVPlayer,
) : ApplePlayerSurface {
    private val root = IosPlayerView(container.bounds, player)
    private val playerLayer get() = root.playerLayer

    init {
        root.backgroundColor = UIColor.blackColor
        root.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        root.hidden = true
        container.addSubview(root)
    }

    override fun setVisible(visible: Boolean) {
        root.hidden = !visible
        if (visible) container.addSubview(root)
    }

    override fun release() {
        playerLayer.player = null
        root.removeFromSuperview()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPlayerView(frame: CValue<CGRect>, player: AVPlayer) : UIView(frame) {
    val playerLayer = AVPlayerLayer().apply {
        this.player = player
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}
