package io.ymusic.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kmpcommon.WorkerThread
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

internal val isLinux = "linux" in System.getProperty("os.name").orEmpty().lowercase()

/** WM_CLASS of every AWT window: XToolkit derives it from the main class name. */
private const val LINUX_WM_CLASS = "io-ymusic-desktop-MainKt"

/** A [px]-square raster of the painter drawn at [scale] × [px], centered (overflow cropped). */
internal fun Painter.rasterize(px: Int, scale: Float = 1f): BufferedImage {
    val bitmap = ImageBitmap(px, px)
    val drawn = px * scale
    val offset = (px - drawn) / 2
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        translate(offset, offset) {
            with(this@rasterize) { draw(Size(drawn, drawn)) }
        }
    }
    return bitmap.toAwtImage()
}

/**
 * Hack: the XEmbed frame hosting our TrayIcon. The tray host resizes it (GNOME: 16×16) but the
 * peer keeps painting its fixed 24px canvas, so the image must be sized to this frame to fit.
 */
internal fun linuxTrayEmbedFrame(): Window? =
    Window.getWindows().lastOrNull { it.javaClass.name.endsWith("XTrayIconEmbeddedFrame") }

/**
 * Hack: AWT routes every pointer event to a pressed window until a release that frees all buttons
 * (XBaseWindow.isFullRelease). GNOME's tray synthesizes the release with an empty button state, so
 * that routing never ends and hover/clicks on the mini player land on the tray icon instead.
 */
internal fun clearAwtPointerAutoGrab() {
    Class.forName("sun.awt.X11.XAwtState")
        .getDeclaredMethod("setAutoGrabWindow", Class.forName("sun.awt.X11.XBaseWindow"))
        .apply { isAccessible = true }
        .invoke(null, null)
}

/**
 * GNOME Shell takes window icons only from a .desktop entry matched by StartupWMClass, so a hidden
 * (NoDisplay) entry maps our windows to the logo. Rewritten only when its contents change.
 */
@WorkerThread
internal fun installLinuxDesktopEntry(icon: BufferedImage) {
    val iconFile = File(ymusicDataDir().apply { mkdirs() }, "ymusic.png")
    val png = ByteArrayOutputStream().also { ImageIO.write(icon, "png", it) }.toByteArray()
    if (!iconFile.exists() || !iconFile.readBytes().contentEquals(png)) iconFile.writeBytes(png)

    val exec = System.getProperty("jpackage.app-path")
        ?: ProcessHandle.current().info().command().orElse("ymusic")
    val entry = """
        |[Desktop Entry]
        |Type=Application
        |Name=YMusic
        |Exec="$exec"
        |Icon=${iconFile.absolutePath}
        |StartupWMClass=$LINUX_WM_CLASS
        |NoDisplay=true
        |
    """.trimMargin()
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    val entryFile = File(File(dataHome, "applications").apply { mkdirs() }, "io.ymusic.desktop.desktop")
    if (!entryFile.exists() || entryFile.readText() != entry) entryFile.writeText(entry)
}
