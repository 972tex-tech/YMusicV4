package io.ymusic.mse

import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
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

                override fun onCaption(id: Int, startSec: Double, endSec: Double, text: String) = Unit
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
    fun listsJackSubtitleStream() {
        val path = jackPath() ?: return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        try {
            assertTrue(FileAvNative.open(h, path) == 0, FileAvNative.lastError(h))
            val streams = FileAvNative.streams(h).toList()
            println("jack streams=$streams")
            assertTrue(streams.any { it.startsWith("2|") && it.contains("mov_text") }, "no text stream $streams")
        } finally {
            FileAvNative.destroy(h)
        }
    }

    @Test
    fun decodesMovTextCaptions() {
        val path = generateCaptionMp4() ?: return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        FileAvNative.setSink(
            h,
            object : FileAvSink {
                override fun onFrame(width: Int, height: Int, bgra: ByteBuffer?) = Unit
                override fun onPcm(rate: Int, channels: Int, s16: ByteArray) = Unit
                override fun onProgress(positionSec: Double, durationSec: Double, width: Int, height: Int) = Unit
                override fun onEvent(ended: Boolean, error: String?) = Unit
                override fun onCaption(id: Int, startSec: Double, endSec: Double, text: String) {
                    lines.add(text)
                }
            },
        )
        try {
            FileAvNative.setVideoEnabled(h, true)
            assertTrue(FileAvNative.open(h, path) == 0, FileAvNative.lastError(h))
            val streams = FileAvNative.streams(h).toList()
            val text = streams.first { it.startsWith("2|") }
            val idx = text.split('|')[1].toInt()
            FileAvNative.setSubtitleIndex(h, idx)
            FileAvNative.play(h)
            pump(h, 1500)
            println("caption lines=$lines")
            assertTrue(lines.any { it.contains("Hello") }, "no Hello in $lines")
        } finally {
            FileAvNative.destroy(h)
            java.io.File(path).delete()
        }
    }

    private fun generateCaptionMp4(): String? {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"))
        val srt = java.io.File(dir, "fileav_cap.srt")
        val mp4 = java.io.File(dir, "fileav_cap.mp4")
        srt.writeText("1\n00:00:00,000 --> 00:00:02,500\nHello JACK\n")
        val pb = ProcessBuilder(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=15",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
            "-i", srt.absolutePath,
            "-map", "0:v", "-map", "1:a", "-map", "2:s",
            "-c:v", "libx264", "-c:a", "aac", "-c:s", "mov_text",
            "-metadata:s:s:0", "language=eng",
            mp4.absolutePath,
        )
        val r = runCatching { pb.start().waitFor() }.getOrDefault(1)
        srt.delete()
        if (r != 0 || !mp4.isFile) return null
        return mp4.absolutePath
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

    private fun jackPath(): String? =
        java.io.File("/path/to/4kvideo").listFiles()
            ?.firstOrNull { it.name.contains("boKJ5XDs_mY") }
            ?.absolutePath
}
