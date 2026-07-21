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
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
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
 * Mouse, wheel, keyboard, and focus events are forwarded to CEF. This is required both for a usable
 * challenge/CAPTCHA page and for browser-integrity systems that expect genuine pointer activity.
 * The component is letterboxed, so event coordinates are translated back into [logicalSize].
 */
internal class ImageOsrBrowser(
    client: CefClient,
    url: String,
    private val surface: JcefOsrSurface,
) : CefBrowser_N(client, url, null, null, null, browserSettings()), CefRenderHandler {

    private val logicalSize: Dimension = surface.logicalSize

    @Volatile private var frame: BufferedImage? = null

    init {
        surface.bind(this)
    }

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
    }.apply {
        isFocusable = true
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                forwardMouse(e)
            }

            override fun mouseReleased(e: MouseEvent) = forwardMouse(e)
            override fun mouseClicked(e: MouseEvent) = forwardMouse(e)
            override fun mouseEntered(e: MouseEvent) = forwardMouse(e)
            override fun mouseExited(e: MouseEvent) = forwardMouse(e, clampToContent = true)

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val p = browserPoint(e.x, e.y) ?: return
                sendMouseWheelEvent(
                    MouseWheelEvent(
                        this@apply,
                        e.id,
                        e.`when`,
                        e.modifiersEx,
                        p.x,
                        p.y,
                        e.clickCount,
                        e.isPopupTrigger,
                        e.scrollType,
                        e.scrollAmount,
                        e.wheelRotation,
                    ),
                )
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = forwardMouse(e)
            override fun mouseDragged(e: MouseEvent) = forwardMouse(e)
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) = sendKeyEvent(e)
            override fun keyPressed(e: KeyEvent) = sendKeyEvent(e)
            override fun keyReleased(e: KeyEvent) = sendKeyEvent(e)
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = setFocus(true)
            override fun focusLost(e: FocusEvent) = setFocus(false)
        })
    }

    private fun forwardMouse(event: MouseEvent, clampToContent: Boolean = false) {
        val p = browserPoint(event.x, event.y, clampToContent) ?: return
        sendMouseEvent(
            MouseEvent(
                component,
                event.id,
                event.`when`,
                event.modifiersEx,
                p.x,
                p.y,
                event.clickCount,
                event.isPopupTrigger,
                event.button,
            ),
        )
    }

    /** Map the painted, letterboxed component coordinates into CEF's fixed logical viewport. */
    private fun browserPoint(x: Int, y: Int, clampToContent: Boolean = false): Point? {
        if (component.width <= 0 || component.height <= 0) return null
        val scale = minOf(
            component.width.toDouble() / logicalSize.width,
            component.height.toDouble() / logicalSize.height,
        )
        val paintedWidth = logicalSize.width * scale
        val paintedHeight = logicalSize.height * scale
        val left = (component.width - paintedWidth) / 2.0
        val top = (component.height - paintedHeight) / 2.0
        if (!clampToContent && (x < left || x >= left + paintedWidth || y < top || y >= top + paintedHeight)) return null
        return Point(
            ((x - left) / scale).toInt().coerceIn(0, logicalSize.width - 1),
            ((y - top) / scale).toInt().coerceIn(0, logicalSize.height - 1),
        )
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
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        // CEF hands BGRA bytes; little-endian int view of that IS ARGB, straight into the raster.
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, minOf(pixels.size, width * height))
        frame = img
        surface.publish(img)
        component.repaint()
    }

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {}
    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {}
    override fun addOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun setOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun removeOnPaintListener(listener: Consumer<CefPaintEvent>) {}
    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean {
        component.cursor = java.awt.Cursor(cursorType)
        return true
    }
    override fun startDragging(browser: CefBrowser, dragData: CefDragData, mask: Int, x: Int, y: Int): Boolean = false
    override fun updateDragCursor(browser: CefBrowser, operation: Int) {}

    override fun createScreenshot(nativeResolution: Boolean): CompletableFuture<BufferedImage> =
        CompletableFuture.failedFuture(UnsupportedOperationException("view-only OSR browser"))

    internal fun forwardMouse(id: Int, x: Int, y: Int, button: Int, modifiers: Int, clickCount: Int) {
        sendMouseEvent(MouseEvent(component, id, System.currentTimeMillis(), modifiers, x, y, clickCount, false, button))
    }

    internal fun forwardWheel(x: Int, y: Int, rotation: Int, modifiers: Int) {
        sendMouseWheelEvent(
            MouseWheelEvent(
                component,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                modifiers,
                x,
                y,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation,
            ),
        )
    }
}

private fun browserSettings() = CefBrowserSettings().apply { windowless_frame_rate = 30 }
