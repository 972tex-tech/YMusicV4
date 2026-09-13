package io.ymusic.mse

import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class FileAvNativeTest {
    @Test
    fun opensFixtureAndReportsDuration() {
        val path = fixture() ?: return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        try {
            val r = FileAvNative.open(h, path)
            assertTrue(r == 0, FileAvNative.lastError(h))
            assertTrue(FileAvNative.duration(h) > 0.0, "duration")
            assertTrue(FileAvNative.streams(h).isNotEmpty(), "streams")
        } finally {
            FileAvNative.destroy(h)
        }
    }

    @Test
    fun playsAndSeeksDjiHevc4kHeadless() {
        val path = DJI_4K
        assertTrue(java.io.File(path).isFile, "missing $path")
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        val frames = AtomicInteger(0)
        val lastPos = AtomicReference(0.0)
        val err = AtomicReference<String?>(null)
        FileAvNative.setSink(
            h,
            object : FileAvSink {
                override fun onFrame(width: Int, height: Int, bgra: ByteBuffer?) {
                    frames.incrementAndGet()
                }

                override fun onPcm(rate: Int, channels: Int, s16: ByteArray) = Unit

                override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) {
                    lastPos.set(positionSec)
                }

                override fun onEvent(ended: Boolean, error: String?) {
                    if (error != null && error.isNotEmpty()) err.set(error)
                }
            },
        )
        try {
            FileAvNative.setVideoEnabled(h, true)
            FileAvNative.setBlitMax(h, 1920)
            val tOpen = System.nanoTime()
            val r = FileAvNative.open(h, path)
            val openMs = (System.nanoTime() - tOpen) / 1_000_000
            assertTrue(r == 0, "open ${FileAvNative.lastError(h)}")
            val dur = FileAvNative.duration(h)
            val streams = FileAvNative.streams(h).toList()
            println("open ${openMs}ms dur=$dur streams=$streams")
            assertTrue(dur > 10.0, "duration $dur")
            assertTrue(streams.any { it.startsWith("1|") }, "no video stream $streams")

            FileAvNative.play(h)
            pump(h, 2000)
            val f1 = frames.get()
            val p1 = lastPos.get()
            println("after 2s frames=$f1 pos=$p1 err=${err.get()}")
            assertTrue(err.get() == null, "error ${err.get()}")
            assertTrue(f1 > 1, "stuck at $f1 frame(s)")

            val tSeek = System.nanoTime()
            FileAvNative.seek(h, 10.0)
            FileAvNative.play(h)
            val seekMs = (System.nanoTime() - tSeek) / 1_000_000
            println("seek 10s took ${seekMs}ms")
            assertTrue(seekMs < 3000, "seek blocked ${seekMs}ms")
            pump(h, 1500)
            val f2 = frames.get()
            val p2 = lastPos.get()
            println("after seek frames=$f2 pos=$p2")
            assertTrue(f2 > f1, "no frames after seek ($f1 -> $f2)")
            assertTrue(p2 > 5.0, "position after seek $p2")
        } finally {
            FileAvNative.destroy(h)
        }
    }

    @Test
    fun audioOnlyOpenThenEnableVideoDoesNotEndDji() {
        val path = DJI_4K
        assertTrue(java.io.File(path).isFile, "missing $path")
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        val frames = AtomicInteger(0)
        val endCount = AtomicInteger(0)
        val err = AtomicReference<String?>(null)
        FileAvNative.setSink(
            h,
            object : FileAvSink {
                override fun onFrame(width: Int, height: Int, bgra: ByteBuffer?) {
                    frames.incrementAndGet()
                }

                override fun onPcm(rate: Int, channels: Int, s16: ByteArray) = Unit

                override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

                override fun onEvent(ended: Boolean, error: String?) {
                    if (error != null && error.isNotEmpty()) err.set(error)
                    if (ended) endCount.incrementAndGet()
                }
            },
        )
        try {
            FileAvNative.setVideoEnabled(h, false)
            assertTrue(FileAvNative.open(h, path) == 0, FileAvNative.lastError(h))
            FileAvNative.play(h)
            pump(h, 800)
            assertTrue(err.get() == null, "error ${err.get()}")
            assertTrue(endCount.get() == 0, "audio-only ended the video-only file")
            assertTrue(frames.get() == 0, "presented while audio-only")
            val pos = FileAvNative.position(h)

            FileAvNative.setVideoEnabled(h, true)
            FileAvNative.setBlitSize(h, 1280, 720)
            FileAvNative.play(h)
            pump(h, 400)
            assertTrue(err.get() == null, "error after enable ${err.get()}")
            assertTrue(endCount.get() == 0, "enable-video ended the track")
            assertTrue(frames.get() > 1, "no frames after enable (${frames.get()})")
            val pos2 = FileAvNative.position(h)
            assertTrue(pos2 + 0.15 >= pos, "audio→video jumped back $pos -> $pos2")
        } finally {
            FileAvNative.destroy(h)
        }
    }

    @Test
    fun presentsVtOnHeavyweightCanvasWithoutBgra() {
        val path = H264_4K
        if (!java.io.File(path).isFile) return
        if (GraphicsEnvironment.isHeadless()) return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        val hw = AtomicInteger(0)
        val cpu = AtomicInteger(0)
        val err = AtomicReference<String?>(null)
        FileAvNative.setSink(
            h,
            object : FileAvSink {
                override fun onFrame(width: Int, height: Int, bgra: ByteBuffer?) {
                    if (bgra == null) hw.incrementAndGet() else cpu.incrementAndGet()
                }

                override fun onPcm(rate: Int, channels: Int, s16: ByteArray) = Unit

                override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit

                override fun onEvent(ended: Boolean, error: String?) {
                    if (error != null && error.isNotEmpty()) err.set(error)
                }
            },
        )
        val frame = AtomicReference<Frame?>(null)
        val attached = AtomicBoolean(false)
        try {
            SwingUtilities.invokeAndWait {
                val f = Frame("file-av-jawt")
                val c = Canvas()
                f.add(c)
                f.setSize(640, 360)
                f.isVisible = true
                attached.set(FileAvNative.setVideoComponent(h, c))
                frame.set(f)
            }
            assertTrue(attached.get(), "JAWT attach")
            FileAvNative.setVideoEnabled(h, true)
            FileAvNative.setBlitSize(h, 640, 360)
            assertTrue(FileAvNative.open(h, path) == 0, FileAvNative.lastError(h))
            FileAvNative.play(h)
            pump(h, 800)
            println("jawt hw=${hw.get()} cpu=${cpu.get()} err=${err.get()}")
            assertTrue(err.get() == null, "error ${err.get()}")
            assertTrue(hw.get() > 1, "no layer frames hw=${hw.get()} cpu=${cpu.get()}")
            assertTrue(cpu.get() == 0, "CPU blit still ran cpu=${cpu.get()}")
        } finally {
            FileAvNative.destroy(h)
            SwingUtilities.invokeAndWait {
                frame.get()?.dispose()
            }
        }
    }

    @Test
    fun overlayForwardsMousePressToCanvas() {
        val path = H264_4K
        if (!java.io.File(path).isFile) return
        if (GraphicsEnvironment.isHeadless()) return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        val clicks = AtomicInteger(0)
        val frame = AtomicReference<Frame?>(null)
        val canvas = AtomicReference<Canvas?>(null)
        try {
            SwingUtilities.invokeAndWait {
                val f = Frame("file-av-mouse")
                val c = Canvas()
                c.addMouseListener(
                    object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            clicks.incrementAndGet()
                        }
                    },
                )
                f.add(c)
                f.setSize(640, 360)
                f.isVisible = true
                f.toFront()
                FileAvNative.setVideoComponent(h, c)
                frame.set(f)
                canvas.set(c)
            }
            FileAvNative.setVideoEnabled(h, true)
            FileAvNative.setBlitSize(h, 640, 360)
            assertTrue(FileAvNative.open(h, path) == 0, FileAvNative.lastError(h))
            FileAvNative.play(h)
            pump(h, 400)
            SwingUtilities.invokeAndWait {
                val c = canvas.get()!!
                val loc = c.locationOnScreen
                val robot = Robot()
                robot.autoDelay = 40
                robot.mouseMove(loc.x + c.width / 2, loc.y + c.height / 2)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
            Thread.sleep(300)
            println("overlay mouse clicks=${clicks.get()}")
            assertTrue(clicks.get() > 0, "overlay swallowed mouse clicks=${clicks.get()}")
        } finally {
            FileAvNative.destroy(h)
            SwingUtilities.invokeAndWait { frame.get()?.dispose() }
        }
    }

    private fun pump(handle: Long, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            FileAvNative.step(handle)
        }
    }

    private fun fixture(): String? {
        val roots = listOf(
            "../kmpFfmpeg/testdata/sample_with_metadata.m4a",
            "kmpFfmpeg/testdata/sample_with_metadata.m4a",
        )
        return roots.map { java.io.File(it).canonicalFile }.firstOrNull { it.isFile }?.absolutePath
    }

    companion object {
        const val DJI_4K = "/path/to/4kvideo/hevc_4k60P_main_dji_mavic3.mov"
        const val H264_4K = "/path/to/4kvideo/4K_UHD_60_FPS_1.mp4"
    }
}
