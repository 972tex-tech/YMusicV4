package ymusic.quickjs

import kmpcommon.io.BufferedAsyncSource
import kmpcommon.io.readUtf8String
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Pipe.asAsyncSource]/[Pipe.asAsyncSink] — the kmpcommon.io buffered APIs over a pipe. */
class PipeIoTest {

    @Test
    fun buffered_source_reads_lines_across_chunks() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            // Writer sends CRLF/LF lines fragmented so line breaks land across receive boundaries.
            rt.load(
                """
                ymix.onAccept("lines", function (pipe) {
                  var bytes = ymix.utf8Encode("alpha\r\nbeta🎵\ngamma\r\n");
                  var i = 0;
                  function step() {
                    if (i >= bytes.length) { pipe.sink.close(); return; }
                    var n = Math.min(3, bytes.length - i); // 3-byte fragments split the \r\n pairs
                    var p = pipe.sink.send(bytes, i, n);
                    i += n;
                    if (p) p.then(step); else step();
                  }
                  step();
                });
                """.trimIndent(),
            )
            val pipe = rt.openPipe("lines")
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
            // 200 KB deterministic pattern — many segments, many receive calls.
            rt.load(
                """
                ymix.onAccept("blob", function (pipe) {
                  var chunk = new Uint8Array(8192);
                  var sent = 0, total = 200 * 1024;
                  function step() {
                    if (sent >= total) { pipe.sink.close(); return; }
                    var n = Math.min(chunk.length, total - sent);
                    for (var i = 0; i < n; i++) chunk[i] = (sent + i) & 0xFF;
                    var p = pipe.sink.send(chunk, 0, n);
                    sent += n;
                    if (p) p.then(step); else step();
                  }
                  step();
                });
                """.trimIndent(),
            )
            val pipe = rt.openPipe("blob")
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
            // Consumer checksums slowly (chunked receive) — the 300 KB write must park and resume.
            rt.load(
                """
                ymix.onAccept("sum", async (pipe) => {
                  var buf = new Uint8Array(4096);
                  var sum = 0, count = 0;
                  for (;;) {
                    var r = pipe.source.receive(buf, 0, buf.length);
                    var n = typeof r === "number" ? r : await r;
                    if (n < 0) break;
                    for (var i = 0; i < n; i++) sum = (sum + buf[i]) >>> 0;
                    count += n;
                  }
                  pipe.sink.send(ymix.utf8Encode(count + ":" + sum));
                  pipe.sink.close();
                });
                """.trimIndent(),
            )
            val pipe = rt.openPipe("sum")
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
            rt.load(
                """
                ymix.onAccept("boom", function (pipe) {
                  pipe.sink.send(ymix.utf8Encode("partial"));
                  pipe.sink.close("engine exploded");
                });
                """.trimIndent(),
            )
            val pipe = rt.openPipe("boom")
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
            rt.load("""ymix.onAccept("deaf", function (pipe) { pipe.source.close(); });""")
            val pipe = rt.openPipe("deaf")
            val payload = Buffer().writeUtf8("anyone listening?")
            assertFailsWith<BrokenPipeException> { pipe.sink.asAsyncSink().write(payload, payload.size) }
        } finally {
            rt.close()
        }
    }
}
