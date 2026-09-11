package io.ymusic.mse

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
                override fun onFrame(width: Int, height: Int, bgra: ByteBuffer) {
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

    private fun pump(handle: Long, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            FileAvNative.step(handle)
            Thread.sleep(4)
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
    }
}
