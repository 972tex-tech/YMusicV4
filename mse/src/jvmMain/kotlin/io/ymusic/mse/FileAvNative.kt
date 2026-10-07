package io.ymusic.mse

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** JNI callbacks from the in-process libav file player (`file_av.c`). */
interface FileAvSink {
    @Keep fun onFrame(width: Int, height: Int, bgra: ByteBuffer?)

    @Keep fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int)

    @Keep fun onEvent(ended: Boolean, error: String?)

    @Keep fun onCaption(id: Int, startSec: Double, endSec: Double, text: String) {}
}

/**
 * Desktop file playback in the same `libmseffmpegjni` as leftover MSE. Separate C state
 * (`file_av_*`): `avformat_open_input` + PTS clock, not append/seek leftover.
 */
object FileAvNative {
    init {
        MseHostNative.vtable()
    }

    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun open(handle: Long, path: String): Int

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun play(handle: Long)

    @JvmStatic external fun pause(handle: Long)

    @JvmStatic external fun seek(handle: Long, seconds: Double)

    @JvmStatic external fun step(handle: Long): Int

    @JvmStatic external fun setSpeed(handle: Long, speed: Double)

    @JvmStatic external fun setVolume(handle: Long, volume: Double)

    @JvmStatic external fun pcmLevels(handle: Long, out: FloatArray): Int

    @JvmStatic external fun setPitch(handle: Long, pitch: Double)

    @JvmStatic external fun setEq(handle: Long, bands: FloatArray)

    @JvmStatic external fun setBass(handle: Long, db: Float)

    @JvmStatic external fun setVirtualizer(handle: Long, width: Float)

    @JvmStatic external fun setPreamp(handle: Long, db: Float)

    @JvmStatic external fun setAudioIndex(handle: Long, index: Int)

    /** -1 turns captions off. [index] is the file stream index (`streams()` kind=2). */
    @JvmStatic external fun setSubtitleIndex(handle: Long, index: Int)

    /** Every text cue on [index] as `startSec|endSec|text`. Empty if the track has no samples. */
    @JvmStatic external fun dumpCaptions(handle: Long, index: Int): Array<String>

    @JvmStatic external fun setVideoEnabled(handle: Long, on: Boolean)

    /** Cap presented height in pixels. 0 = native. */
    @JvmStatic external fun setBlitMax(handle: Long, maxHeight: Int)

    /** Fit the presented frame inside [w]×[h]. 0 on a side means unconstrained. */
    @JvmStatic external fun setBlitSize(handle: Long, w: Int, h: Int)

    /** macOS: overlay VideoToolbox on a heavyweight AWT Canvas. */
    @JvmStatic external fun setVideoComponent(handle: Long, component: java.awt.Component?): Boolean

    /** macOS: move the overlay to the Canvas screen rect. Safe from AWT during resize. */
    @JvmStatic external fun layoutVideo(x: Int, y: Int, w: Int, h: Int)

    /** Hide the overlay NSView without detaching the layer. */
    @JvmStatic external fun setOverlayHidden(hidden: Boolean)

    /** Place [below] immediately under [above] without making [below] key. macOS only. */
    @JvmStatic external fun orderWindowBelow(below: java.awt.Window, above: java.awt.Window)

    /** Enable NSWindowCollectionBehaviorFullScreenPrimary on the Compose window. */
    @JvmStatic external fun prepareFullscreen(primary: java.awt.Window)

    @JvmStatic external fun toggleFullScreen(primary: java.awt.Window)

    @Volatile @JvmStatic
    var onFullScreen: ((Boolean) -> Unit)? = null

    @Keep @JvmStatic
    fun onFullScreenChanged(on: Boolean) {
        onFullScreen?.invoke(on)
    }

    /** Last presented overlay frame as BGRA. Writes width/height into [wh] (length ≥ 2). */
    @JvmStatic external fun snapshotOverlay(wh: IntArray): ByteArray?

    /**
     * Overlay NSView mouse. [id] is `java.awt.event.MouseEvent` id; x/y are Java screen
     * (top-left). Desktop shell toggles chrome on mouse-up; do not copy events into Compose.
     */
    @Volatile @JvmStatic
    var overlayMouse: OverlayMouse? = null

    @JvmStatic
    fun onOverlayMouse(id: Int, xOnScreen: Int, yOnScreen: Int, modifiers: Int, button: Int, clickCount: Int, wheel: Int) {
        overlayMouse?.onMouse(id, xOnScreen, yOnScreen, modifiers, button, clickCount, wheel)
    }

    fun interface OverlayMouse {
        fun onMouse(id: Int, xOnScreen: Int, yOnScreen: Int, modifiers: Int, button: Int, clickCount: Int, wheel: Int)
    }

    /** memcpy [n] BGRA pixels into a `TYPE_INT_RGB` int array. */
    @JvmStatic external fun copyBgra(bgra: ByteBuffer, rgb: IntArray, n: Int)

    @JvmStatic external fun duration(handle: Long): Double

    @JvmStatic external fun position(handle: Long): Double

    @JvmStatic external fun ended(handle: Long): Boolean

    @JvmStatic external fun videoSize(handle: Long, outWh: IntArray): Boolean

    @JvmStatic external fun lastError(handle: Long): String

    @JvmStatic external fun streams(handle: Long): Array<String>

    @JvmStatic external fun setSink(handle: Long, sink: FileAvSink?)
}
