package kmpcommon.io

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * writeAll(AsyncSource) must emit complete segments as it goes (okio's RealBufferedSink.writeAll
 * semantics). Without it the whole source accumulates in the sink's buffer until flush — a large
 * streamed upload is held entirely in RAM. Observable: the underlying sink receives bytes DURING
 * the copy, not only at the final flush.
 */
class WriteAllEmitTest {

    private class CountingSink : Sink {
        var received = 0L
        override fun write(source: Buffer, byteCount: Long) {
            received += byteCount
            source.skip(byteCount)
        }
        override fun flush() = Unit
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    private fun source(total: Long): AsyncSource {
        var remaining = total
        return object : AsyncSource {
            override suspend fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val n = minOf(byteCount, remaining)
                sink.write(ByteArray(n.toInt()))
                remaining -= n
                return n
            }
            override fun close() = Unit
        }
    }

    @Test
    fun writeAll_emits_segments_during_the_copy_not_only_at_flush() = runTest {
        val counting = CountingSink()
        val sink = counting.buffer()
        val total = 1024L * 1024 // 1 MiB, far above one 8 KiB segment

        val written = sink.writeAll(source(total))

        assertEquals(total, written)
        // The bulk must already have reached the underlying sink before any explicit flush —
        // only the last partial segment may still sit in the buffer.
        assertTrue(counting.received >= total - 8192, "expected streamed emission, got ${counting.received}")
    }
}
