package io.ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import io.ymusic.mse.NativeMseFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode

/**
 * UIKit paints BGRA; Compose does not recompose each frame.
 * `isInteractive = false` keeps touches in Compose (overlay button, slider).
 */
@Composable
internal actual fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier,
) {
    val view = remember(sink) { MseBlitView(sink) }
    LaunchedEffect(surface, player, view) {
        var lastGen = -1
        while (isActive) {
            val p = player
            val host = surface.host
            if (p != null && host != 0L) {
                val frame = p.mseCopyFrame(host, lastGen)
                if (frame != null) {
                    lastGen = frame.gen
                    view.present(frame)
                }
            }
            delay(16)
        }
    }
    UIKitView(
        factory = { view },
        modifier = modifier,
        update = { it.setPlaceholder(placeholder) },
        properties = UIKitInteropProperties(
            isInteractive = false,
            isNativeAccessibilityEnabled = false,
        ),
    )
}

@OptIn(ExperimentalForeignApi::class)
private class MseBlitView(private val sink: MseVideoSink) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val imageView = UIImageView()
    private val label = UILabel()

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        imageView.userInteractionEnabled = false
        imageView.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
        imageView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        imageView.setFrame(bounds)
        addSubview(imageView)
        label.textColor = UIColor.whiteColor.colorWithAlphaComponent(0.7)
        label.textAlignment = NSTextAlignmentCenter
        label.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        label.setFrame(bounds)
        addSubview(label)
    }

    fun setPlaceholder(text: String) {
        if (imageView.image == null) label.text = text
    }

    fun present(frame: NativeMseFrame) {
        val img = frame.toUIImage() ?: return
        imageView.image = img
        label.hidden = true
        sink.frameCount++
        sink.frameW = frame.width
        sink.frameH = frame.height
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NativeMseFrame.toUIImage(): UIImage? {
    val w = width
    val h = height
    val nbytes = w * h * 4
    if (w <= 0 || h <= 0 || nbytes > bgra.size) return null
    val copy = bgra.copyOf(nbytes)
    return copy.usePinned { pinned ->
        val cs = CGColorSpaceCreateDeviceRGB() ?: return@usePinned null
        val ctx = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = w.convert(),
            height = h.convert(),
            bitsPerComponent = 8u,
            bytesPerRow = (w * 4).convert(),
            space = cs,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little,
        )
        if (ctx == null) {
            CGColorSpaceRelease(cs)
            return@usePinned null
        }
        val cg = CGBitmapContextCreateImage(ctx)
        CGContextRelease(ctx)
        CGColorSpaceRelease(cs)
        if (cg == null) return@usePinned null
        val image = UIImage.imageWithCGImage(cg)
        CGImageRelease(cg)
        image
    }
}
