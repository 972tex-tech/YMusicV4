package org.cef.browser

import org.cef.CefBrowserSettings
import org.cef.CefClient
import org.cef.callback.CefDragData
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import javax.swing.JComponent

/**
 * An off-screen (windowless) JCEF browser that paints frames into a plain lightweight [JComponent]
 * instead of a native child window. Lives in `org.cef.browser` because [CefBrowser_N] is
 * package-private — the same-package subclass is the supported JCEF pattern for a custom
 * [CefRenderHandler] (JetBrains' JBCef does the same).
 *
 * Two properties the windowed browser can't give us:
 *  - **True z-order.** A native child window always paints over Compose; a lightweight component
 *    participates in normal painting (and blends under Compose layers with
 *    `compose.interop.blending`).
 *  - **A fixed logical viewport.** [getViewRect] reports [logicalSize] no matter how large or small
 *    the component is drawn, so the page (YouTube's SABR stream negotiation sizes streams to the
 *    viewport) never sees a thumbnail-sized window. The frame is letterboxed into the component.
 *
 * View-only by design: no mouse/keyboard forwarding — the host drives the page via JS, and stray
 * clicks into YouTube's embed UI are unwanted anyway.
 */
internal class ImageOsrBrowser(
    client: CefClient,
    url: String,
    private val logicalSize: Dimension,
) : CefBrowser_N(client, url, null, null, null, browserSettings()), CefRenderHandler {

    @Volatile private var frame: BufferedImage? = null

    private val component = object : JComponent() {
        override fun paintComponent(g: Graphics) {
            g.color = java.awt.Color.BLACK
            g.fillRect(0, 0, width, height)
            val img = frame ?: return
            // Letterbox: preserve the frame's aspect inside whatever bounds the UI gives us.
            val scale = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            g.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
        }
    }

    override fun createImmediately() {
        createBrowser(client, 0L, getUrl(), true, false, null, requestContext)
    }

    override fun getUIComponent(): JComponent = component

    override fun getRenderHandler(): CefRenderHandler = this

    override fun createDevToolsBrowser(
        client: CefClient,
        url: String,
        context: CefRequestContext?,
        parent: CefBrowser_N?,
        inspectAt: Point?,
    ): CefBrowser_N? = null

    override fun getViewRect(browser: CefBrowser): Rectangle = Rectangle(0, 0, logicalSize.width, logicalSize.height)

    override fun getScreenInfo(browser: CefBrowser, screenInfo: CefScreenInfo): Boolean {
        val rect = Rectangle(0, 0, logicalSize.width, logicalSize.height)
        screenInfo.Set(1.0, 32, 8, false, rect, rect)
        return true
    }

    override fun getScreenPoint(browser: CefBrowser, viewPoint: Point): Point {
        val origin = runCatching { component.locationOnScreen }.getOrNull() ?: return Point(viewPoint)
        return Point(origin.x + viewPoint.x, origin.y + viewPoint.y)
    }

    override fun onPaint(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRects: Array<Rectangle>,
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
    ) {
        if (popup) return
        val img = frame?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { frame = it }
        // CEF hands BGRA bytes; little-endian int view of that IS ARGB, straight into the raster.
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, minOf(pixels.size, width * height))
        component.repaint()
    }

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {}
    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {}
    override fun addOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun setOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun removeOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean = true
    override fun startDragging(browser: CefBrowser, dragData: CefDragData, mask: Int, x: Int, y: Int): Boolean = false
    override fun updateDragCursor(browser: CefBrowser, operation: Int) {}

    override fun createScreenshot(nativeResolution: Boolean): CompletableFuture<BufferedImage> =
        CompletableFuture.failedFuture(UnsupportedOperationException("view-only OSR browser"))
}

private fun browserSettings() = CefBrowserSettings().apply { windowless_frame_rate = 30 }
