package io.ymusic.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AppKit.NSColor
import platform.AppKit.NSView
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable
import platform.CoreGraphics.CGRect
import platform.QuartzCore.CALayer

/** macOS local-player context: the shared host in which the engine owns its AVPlayerLayer root. */
actual class LocalPlayerContext(val videoContainer: NSView? = null)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer =
    AppleLocalPlayer { player -> context.videoContainer?.let { MacosApplePlayerSurface(it, player) } }

@OptIn(ExperimentalForeignApi::class)
private class MacosApplePlayerSurface(
    private val container: NSView,
    player: AVPlayer,
) : ApplePlayerSurface {
    private val root = MacosPlayerView(container.bounds, player)
    private val playerLayer get() = root.playerLayer

    init {
        root.setAutoresizingMask(NSViewWidthSizable or NSViewHeightSizable)
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
private class MacosPlayerView(frame: CValue<CGRect>, player: AVPlayer) : NSView(frame) {
    val playerLayer = AVPlayerLayer().apply {
        this.player = player
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        wantsLayer = true
        layer = CALayer().apply { backgroundColor = NSColor.blackColor.CGColor }
        layer?.addSublayer(playerLayer)
    }

    override fun layout() {
        super.layout()
        playerLayer.frame = bounds
    }
}
