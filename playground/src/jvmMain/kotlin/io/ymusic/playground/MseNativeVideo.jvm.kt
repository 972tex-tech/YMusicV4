package io.ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.min
import java.awt.Color as AwtColor

/**
 * MSE preview: macOS overlays VideoToolbox on this panel (JAWT CALayer);
 * otherwise Swing paints BGRA. Compose does not recompose each frame.
 *
 * Compose Desktop's SwingPanel always installs a pointer-interop filter that
 * redispatches the click into this component via [javax.swing.SwingUtilities.getDeepestComponentAt]
 * and then **consumes** it. Returning false from [contains] makes that lookup
 * miss, so the event stays in Compose (popups, menus, overlays over the video).
 */
@Composable
internal actual fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier,
) {
    val panel = remember(sink) { MseVideoPanel(sink) }
    LaunchedEffect(surface, panel) {
        surface.frame.collect { panel.setImage(it) }
    }
    LaunchedEffect(surface, panel) {
        val wh = IntArray(2)
        while (isActive) {
            val host = surface.host
            if (host != 0L && surface.nativePresent) {
                sink.frameCount = MseHostNative.frames(host)
                if (MseHostNative.videoSize(host, wh)) {
                    sink.frameW = wh[0]
                    sink.frameH = wh[1]
                }
            }
            delay(16)
        }
    }
    DisposableEffect(surface, panel) {
        onDispose { surface.attach(null) }
    }
    SwingPanel(
        background = Color.Black,
        factory = { panel },
        update = {
            it.placeholder = placeholder
            it.nativePresent = surface.nativePresent
            if (it.isDisplayable && !it.attachTried && surface.host != 0L) {
                it.attachTried = true
                it.nativePresent = surface.attach(it)
            }
        },
        modifier = modifier,
    )
}

private class MseVideoPanel(private val sink: MseVideoSink) : JPanel() {
    @Volatile
    var placeholder: String = "Waiting for video…"
        set(value) {
            if (field == value) return
            field = value
            if (img == null) repaintOnEdt()
        }

    @Volatile
    var nativePresent: Boolean = false
    var attachTried: Boolean = false

    @Volatile
    private var img: BufferedImage? = null

    init {
        isOpaque = true
        background = AwtColor.BLACK
        isFocusable = false
        isEnabled = false
        isRequestFocusEnabled = false
    }

    /**
     * Always miss AWT hit-testing. Compose's interop filter uses this to decide
     * whether to consume the pointer event; a miss leaves the click in Compose.
     */
    override fun contains(x: Int, y: Int): Boolean = false

    fun setImage(image: BufferedImage?) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { setImage(image) }
            return
        }
        img = image
        if (image != null) {
            sink.frameCount++
            sink.frameW = image.width
            sink.frameH = image.height
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.color = AwtColor.BLACK
        g2.fillRect(0, 0, width, height)
        val image = img
        if (image == null) {
            g2.color = AwtColor(255, 255, 255, 180)
            val s = placeholder
            val fm = g2.fontMetrics
            g2.drawString(s, (width - fm.stringWidth(s)).coerceAtLeast(0) / 2, height / 2)
            return
        }
        val scale = min(width / image.width.toDouble(), height / image.height.toDouble())
        val dw = (image.width * scale).toInt().coerceAtLeast(1)
        val dh = (image.height * scale).toInt().coerceAtLeast(1)
        val x = (width - dw) / 2
        val y = (height - dh) / 2
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.drawImage(image, x, y, dw, dh, null)
    }

    private fun repaintOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) repaint()
        else SwingUtilities.invokeLater { repaint() }
    }
}
