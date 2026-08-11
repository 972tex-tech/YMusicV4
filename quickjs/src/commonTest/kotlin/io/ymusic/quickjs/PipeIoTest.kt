package io.ymusic.quickjs

import kmpcommon.io.BufferedAsyncSource
import kmpcommon.io.readUtf8String
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Pipe.asAsyncSource]/[Pipe.asAsyncSink] — the kmpcommon.io buffered APIs over a pipe, driven
 *  against the test-bundle fixtures (engine-js/src/test/fixtures/transport.ts). */
class PipeIoTest {

    @Test
    fun buffered_source_reads_lines_across_chunks() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/lines")
            val source = BufferedAsyncSource(pipe.source.asAsyncSource())
            assertEquals("alpha", source.readUtf8LineStrict())
            assertEquals("beta🎵", source.readUtf8LineStrict())
            assertEquals("gamma", source.readUtf8LineStrict())
            assertTrue(source.exhausted())
        } finally {
            rt.close()
        }
    }

    @Test
    fun source_reads_directly_into_okio_segments() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/blob")
            val source = pipe.source.asAsyncSource()
            val all = Buffer()
            while (true) {
                if (source.read(all, 64 * 1024) == -1L) break
            }
            assertEquals(200L * 1024, all.size)
            val bytes = all.readByteArray()
            for (i in bytes.indices) {
                check(bytes[i] == (i and 0xFF).toByte()) { "byte $i corrupted" }
            }
        } finally {
            rt.close()
        }
    }

    @Test
    fun sink_streams_a_buffer_through_backpressure() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            // The fixture checksums via chunked receives — the 300 KB write must park and resume.
            val pipe = rt.openPipe("test/sum")
            val total = 300 * 1024
            val payload = Buffer()
            var expectedSum = 0L
            for (i in 0 until total) {
                val b = (i * 31) and 0xFF
                payload.writeByte(b)
                expectedSum = (expectedSum + b) and 0xFFFFFFFFL
            }
            val sink = pipe.sink.asAsyncSink()
            sink.write(payload, payload.size)
            assertEquals(0L, payload.size) // fully consumed
            pipe.sink.close()
            assertEquals("$total:$expectedSum", pipe.source.asAsyncSource().readUtf8String())
        } finally {
            rt.close()
        }
    }

    @Test
    fun reset_surfaces_through_the_source() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/boom")
            val source = BufferedAsyncSource(pipe.source.asAsyncSource())
            val e = assertFailsWith<PipeResetException> { source.readUtf8LineStrict() }
            assertEquals("engine exploded", e.reason)
        } finally {
            rt.close()
        }
    }

    @Test
    fun epipe_surfaces_through_the_sink() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/deaf")
            val payload = Buffer().writeUtf8("anyone listening?")
            assertFailsWith<BrokenPipeException> { pipe.sink.asAsyncSink().write(payload, payload.size) }
        } finally {
            rt.close()
        }
    }
}
