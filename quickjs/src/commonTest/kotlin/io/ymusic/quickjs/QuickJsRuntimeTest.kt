package io.ymusic.quickjs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pipe transport end to end. JS halves live in the test-bundle fixtures
 *  (engine-js/src/test/fixtures/runtime.ts, `test/rt/…` commands); the remaining load() snippets
 *  are pure JS or `__*`-natives only, which survive the ADVANCED bundle (natives are externed). */
class QuickJsRuntimeTest {

    private suspend fun Pipe.sendText(s: String) {
        val b = Buffer().writeUtf8(s)
        sink.send(b, b.size)
    }

    private suspend fun Pipe.sendBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) =
        sink.send(Buffer().write(bytes, offset, count), count.toLong())

    /** Read the whole pipe stream (until the peer closes) into one array. */
    private suspend fun Pipe.readAll(): ByteArray {
        val all = Buffer()
        while (source.receive(all, 256L) != -1L) { /* accumulate until EOF */ }
        return all.readByteArray()
    }

    private suspend fun Pipe.readText(): String = readAll().decodeToString()

    /** Run a self-reporting `test/rt/…` probe to EOF. */
    private suspend fun QuickJsRuntime.probe(name: String): String = openPipe("test/rt/$name").readText()

    /** Native `__parseJson` (JS_ParseJSON over raw UTF-8 bytes) — no giant JS string, astral-safe. */
    @Test
    fun native_json_parse_over_bytes() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("function", rt.load("typeof __parseJson"))
            // objects, arrays, nested, emoji; parsed straight from a Uint8Array of UTF-8 bytes.
            assertEquals(
                """{"title":"hi🎵","items":[1,2,3]}""",
                rt.load(
                    """
                    (function () {
                      const s = '{"title":"hi🎵","items":[1,2,3]}';
                      const out = __stringifyJson(__parseJson(__utf8Encode(s, 0, s.length)));
                      return __utf8Decode(out, 0, out.length);
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            rt.close()
        }
    }

    /**
     * The streaming parser is resumable across arbitrary chunk boundaries: feeding the SAME bytes
     * one at a time (worst case — every boundary lands mid-token) must equal the reference parser.
     * Covers nested containers, escapes, a surrogate pair split across pushes, and numbers.
     */
    @Test
    fun streaming_json_parse_byte_by_byte() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals(
                "OK",
                rt.load(
                    """
                    (function () {
                      const json =
                        '{"nested":{"x":[1,2,{"y":"}]"}]},"s":"a\\"b\\ud83c\\udfb5\\n",' +
                        '"n":-12.5e3,"items":[{"id":1},{"id":2}],"big":9007199254740991}';
                      const bytes = __utf8Encode(json, 0, json.length);
                      // feed one byte per push — exercises every possible suspend point
                      const p = __jsonNew();
                      const one = new Uint8Array(1);
                      for (let i = 0; i < bytes.length; i++) { one[0] = bytes[i]; __jsonPush(p, one, 0, 1); }
                      const got = JSON.stringify(__jsonEnd(p));
                      const want = JSON.stringify(JSON.parse(json));  // byte-by-byte must equal JSON.parse
                      return got === want ? "OK" : (got + " != " + want);
                    })()
                    """.trimIndent(),
                ),
            )
        } finally {
            rt.close()
        }
    }

    /** End-to-end: a JSON body dribbled over a pipe in small frames, parsed via buffered.readJsonObject. */
    @Test
    fun buffered_read_json_over_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        val body = """{"header":{"trackingParams":"junk"},"title":"hi🎵","items":[{"id":1},{"id":2}]}"""
        rt.onAccept("feed") { pipe ->
            val bytes = body.encodeToByteArray()
            var i = 0
            while (i < bytes.size) {
                val n = minOf(7, bytes.size - i)
                pipe.sendBytes(bytes, i, n)
                i += n
            }
            pipe.sink.close()
        }
        try {
            assertEquals(body, rt.probe("buffered-read-json"))
        } finally {
            rt.close()
        }
    }

    /** readJsonObject/readJsonArray validate the root type on the first byte and fail fast otherwise. */
    @Test
    fun read_json_object_and_array_enforce_root_type() = runTest {
        val rt = QuickJsRuntime.create()
        suspend fun run(kind: String, json: String): String {
            val p = rt.openPipe("test/rt/parse")
            p.sendText("$kind\n$json")
            p.sink.close()
            return p.readText()
        }
        try {
            // matching types parse fine
            assertEquals("""{"a":1}""", run("o", """{"a":1}"""))
            assertEquals("[1,2,3]", run("a", "[1,2,3]"))
            // wrong root type is rejected on the first byte
            assertEquals("ERR:expected a JSON object at position 0", run("o", "[1,2,3]"))
            assertEquals("ERR:expected a JSON array at position 0", run("a", """{"a":1}"""))
            assertEquals("ERR:expected a JSON object at position 0", run("o", "42"))
            // e.g. an HTML error page instead of JSON — caught immediately, not parsed
            assertEquals("ERR:expected a JSON object at position 0", run("o", "<html>oops</html>"))
        } finally {
            rt.close()
        }
    }

    /** readJson stops at the end of the value; trailing bytes stay readable ("{json}tail" on one pipe). */
    @Test
    fun buffered_read_json_leaves_tail() = runTest {
        val rt = QuickJsRuntime.create()
        // Body = a JSON object immediately followed by unrelated tail bytes, split across frames.
        rt.onAccept("feed") { pipe ->
            pipe.sendText("""{"a":1,"b":[2,3]}TA""")
            pipe.sendText("IL-🎵-DATA")
            pipe.sink.close()
        }
        try {
            assertEquals("""{"a":1,"b":[2,3]}|TAIL-🎵-DATA""", rt.probe("read-json-tail"))
        } finally {
            rt.close()
        }
    }

    /** A parser abandoned mid-parse (half-built container + pending key) must be freed cleanly by
     *  the class finalizer at runtime teardown — no leak, no crash. */
    @Test
    fun streaming_parser_abandoned_midway_frees_cleanly() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            // leave several parsers suspended mid-object, holding a kept container + pending key
            globalThis.__leak = [];
            for (let i = 0; i < 50; i++) {
              const p = __jsonNew();
              const s = '{"a":[1,2,';
              __jsonPush(p, __utf8Encode(s, 0, s.length), 0, 10);
              __leak.push(p);
            }
            """.trimIndent(),
        )
        rt.close() // finalizers must free the suspended parsers without error
    }

    /** A parse ERROR mid-stream leaves partial containers on the parser's stack; the class
     *  finalizer must still free them at teardown (native builds assert on a leaked gc object). */
    @Test
    fun streaming_parser_frees_memory_on_parse_error() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            globalThis.__failed = [];
            for (let i = 0; i < 50; i++) {
              const p = __jsonNew(1);  // expect object; nested containers get built
              let threw = false;
              try {
                // opens obj -> array -> obj -> array (partial stack), then an invalid token
                const s = '{"a":[1,2,{"b":[3, xBAD';
                const bytes = __utf8Encode(s, 0, s.length);
                __jsonPush(p, bytes, 0, bytes.length);
              } catch (e) { threw = true; }
              if (!threw) throw new Error("expected a parse error");
              __failed.push(p); // keep referenced: only the finalizer frees it, at rt.close()
            }
            """.trimIndent(),
        )
        rt.close() // finalizers must free the failed parsers (partial stacks) without error
    }

    /** Native `__stringifyJson` (JS_JSONStringify + native UTF-8 encode) — the encode mirror. */
    @Test
    fun native_json_stringify_to_bytes() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("function", rt.load("typeof __stringifyJson"))
            // value -> UTF-8 bytes; emoji survives the native UTF-16 -> UTF-8 encode
            assertEquals(
                """{"title":"hi🎵","items":[1,2,3]}""",
                rt.load(
                    """
                    (function () {
                      const out = __stringifyJson({title:"hi🎵",items:[1,2,3]});
                      return __utf8Decode(out, 0, out.length);
                    })()
                    """.trimIndent(),
                ),
            )
            // bytes round-trip straight back through the native parse
            assertEquals("hi🎵", rt.load("""__parseJson(__stringifyJson({t:"hi🎵"})).t"""))
            // no JSON representation -> throws
            assertFailsWith<QuickJsException> { rt.load("__stringifyJson(undefined)") }
        } finally {
            rt.close()
        }
    }

    @Test
    fun eval_sync_and_astral() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("3", rt.load("1 + 2"))
            assertEquals("a🎵b", rt.load("'a\\u{1F3B5}b'"))
        } finally {
            rt.close()
        }
    }

    @Test
    fun load_error_throws() {
        runTest {
            val rt = QuickJsRuntime.create()
            try {
                assertFailsWith<QuickJsException> { rt.load("throw new Error('boom')") }
            } finally {
                rt.close()
            }
        }
    }

    /** Kotlin is the client streaming bytes to a JS echo handler; emoji survives (astral over bytes). */
    @Test
    fun kotlin_streams_to_js_echo() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/echo")
            pipe.sendText("hi🎵")
            pipe.sink.close() // EOF our input → JS echo loop ends and closes
            assertEquals("hi🎵", pipe.readText())
        } finally {
            rt.close()
        }
    }

    @Test
    fun stream_is_binary_safe() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/echo")
            val payload = byteArrayOf(0, 127, -128, -1, -2, 1, 0)
            pipe.sendBytes(payload, 0, payload.size)
            pipe.sink.close()
            assertContentEquals(payload, pipe.readAll())
        } finally {
            rt.close()
        }
    }

    /** Send + offset/len: only the requested slice of a reused buffer is transmitted. */
    @Test
    fun send_respects_offset_and_len() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/echo")
            val buf = "XXXhelloYYY".encodeToByteArray()
            pipe.sendBytes(buf, 3, 5) // "hello"
            pipe.sink.close()
            assertEquals("hello", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** JS is the client here: while serving Kotlin's probe, JS opens its own "upper" pipe back to Kotlin. */
    @Test
    fun js_opens_pipe_kotlin_serves_nested() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("upper") { p ->
            val text = p.readText()
            p.sendText(text.uppercase())
            p.sink.close()
        }
        try {
            val pipe = rt.openPipe("test/rt/nested-upper")
            pipe.sendText("hello🎵")
            pipe.sink.close()
            assertEquals("HELLO🎵", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** The sink coalesces many small writes and auto-flushes when its fixed segment fills; across
     *  those flush boundaries the peer still sees one intact, in-order byte stream. */
    @Test
    fun buffered_sink_buffers_until_flush() = runTest {
        val rt = QuickJsRuntime.create()
        // Drain to EOF, then reply with the reassembled content on the same pipe.
        rt.onAccept("sink") { pipe ->
            val all = Buffer()
            while (pipe.source.receive(all, 8192L) != -1L) { /* accumulate until EOF */ }
            pipe.sendText(all.readUtf8())
            pipe.sink.close()
        }
        try {
            assertEquals("ABCDE\n".repeat(2000), rt.probe("sink-2000-lines"))
        } finally {
            rt.close()
        }
    }

    /** Large writes: writeUtf8 stream-encodes straight into the segment and writeBytes bypasses it
     *  entirely — content crosses many 8 KB flush boundaries and must arrive intact and in order.
     *  Verified by an independent length + additive checksum rather than by holding the whole body. */
    @Test
    fun buffered_sink_streams_large_writes() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            val b = Buffer()
            var len = 0L
            var sum = 0L
            while (pipe.source.receive(b, 8192L) != -1L) { // consume as we go — keeps the writer draining
                val chunk = b.readByteArray()
                len += chunk.size
                for (byte in chunk) sum += (byte.toInt() and 0xFF)
            }
            pipe.sendText("$len:$sum")
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("sink-large-writes"))
        } finally {
            rt.close()
        }
    }

    /** A writeUtf8 too big to stage flushes the segment and streams a one-shot C-encoded buffer —
     *  past the 64 KB pipe cap here, so the direct send itself parks and drains. Bytes must land
     *  strictly in call order around staged neighbors. */
    @Test
    fun write_utf8_direct_path_keeps_order_with_staged_writes() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("head|" + "x🎵".repeat(30000) + "|tail", rt.openPipe("test/rt/direct-order").readText())
        } finally {
            rt.close()
        }
    }

    /** writeObjectAsJson streams a value to the pipe token-by-token (no whole string / Uint8Array).
     *  Its output must match JSON.stringify exactly — escapes, NaN/Infinity->null, undefined omitted
     *  in objects / null in arrays, nesting, astral chars — and stay correct across flush boundaries. */
    @Test
    fun write_object_as_json_matches_stringify() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe -> // reassemble the streamed bytes and echo the text back
            val all = Buffer()
            while (pipe.source.receive(all, 8192L) != -1L) { /* accumulate until EOF */ }
            pipe.sendText(all.readUtf8())
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("json-stringify-match"))
        } finally {
            rt.close()
        }
    }

    /** setTimeout fires its callback after the delay, and doubles as an awaitable sleep. */
    @Test
    fun set_timeout_fires_after_delay() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("FIRED", rt.probe("timer-fires"))
        } finally {
            rt.close()
        }
    }

    /** clearTimeout before the delay elapses prevents the callback. */
    @Test
    fun clear_timeout_prevents_callback() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("CLEARED", rt.probe("timer-cleared"))
        } finally {
            rt.close()
        }
    }

    /** setInterval ticks repeatedly and clearInterval stops it (count frozen after clear). */
    @Test
    fun set_interval_ticks_until_cleared() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("OK", rt.probe("interval-ticks"))
        } finally {
            rt.close()
        }
    }

    /** Only one receive() may be outstanding per pipe; a concurrent second call rejects. */
    @Test
    fun concurrent_receive_rejects() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("silent") { /* never send: the first receive stays pending, pipe stays open */ }
        try {
            assertEquals("concurrent receive() on the same pipe", rt.probe("concurrent-receive"))
        } finally {
            rt.close()
        }
    }

    /** readUtf8Line(strict): a final line without '\n' is returned when non-strict, but strict
     *  (okio readUtf8LineStrict) requires the '\n' and throws at EOF. */
    @Test
    fun read_utf8_line_strict() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("feed") { pipe ->
            pipe.sendText("a\nbc")
            pipe.sink.close()
        } // "bc" unterminated
        suspend fun run(strict: Boolean): String {
            val p = rt.openPipe("test/rt/line-strict")
            p.sendText(if (strict) "1" else "0")
            p.sink.close()
            return p.readText()
        }
        try {
            assertEquals("a|bc", run(false)) // non-strict: trailing unterminated line returned
            assertEquals("a|THREW", run(true)) // strict: no newline before EOF -> throws
        } finally {
            rt.close()
        }
    }

    /** Buffered source/sink (okio-style): writeUtf8Line + flush out, readUtf8Line back. */
    @Test
    fun buffered_line_io() = runTest {
        val rt = QuickJsRuntime.create()
        // Kotlin echo server (JS opens toward Kotlin).
        rt.onAccept("echo") { pipe ->
            val b = Buffer()
            while (pipe.source.receive(b, 64L) != -1L) pipe.sink.send(b, b.size)
            pipe.sink.close()
        }
        try {
            assertEquals("hello🎵|world|EOF", rt.probe("line-io"))
        } finally {
            rt.close()
        }
    }

    /** A line split across several frames (with '\r'|'\n' split at a frame boundary) reads as one line. */
    @Test
    fun buffered_line_split_across_frames() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("lines") { pipe ->
            pipe.sendText("hel")
            pipe.sendText("lo🎵\r")
            pipe.sendText("\nsecond\n")
            pipe.sink.close()
        }
        try {
            assertEquals("hello🎵|second|EOF", rt.probe("line-split-frames"))
        } finally {
            rt.close()
        }
    }

    /** Stream JSON bytes over a pipe, parse them natively (`__parseJson`) — no giant string, emoji-safe. */
    @Test
    fun json_body_over_pipe_parsed_natively() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("fetch") { pipe ->
            pipe.sendText("""{"title":"hi🎵","items":[1,2,3]}""")
            pipe.sink.close()
        }
        try {
            assertEquals("hi🎵:3", rt.probe("native-parse-stream"))
        } finally {
            rt.close()
        }
    }

    /** A response streamed as several sends, read as one continuous stream. */
    @Test
    fun streams_multiple_sends_as_one_stream() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/stream")
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), pipe.readAll())
        } finally {
            rt.close()
        }
    }

    /** Reader-side cancel (Linux EPIPE): the JS handler closes its read end, so the Kotlin writer's
     *  next send throws [BrokenPipeException] — it can tell a producer "stop, I'm not reading". */
    @Test
    fun send_throws_broken_pipe_after_peer_closes_read() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/deaf") // openPipe pumps the handler, so closeRead has already run
            assertFailsWith<BrokenPipeException> { pipe.sendBytes(byteArrayOf(1, 2, 3)) }
        } finally {
            rt.close()
        }
    }

    /** After close() you've declared yourself done writing — a later send() is a misuse and throws
     *  [SendClosedException] (a self-caused IllegalStateException, distinct from EPIPE /
     *  [BrokenPipeException], which is the peer closing its read end). */
    @Test
    fun send_after_close_is_fatal() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/echo")
            pipe.sink.close()
            assertFailsWith<SendClosedException> { pipe.sendBytes(byteArrayOf(1, 2, 3)) }
        } finally {
            rt.close()
        }
    }

    /** The JS mirror: after io.sink.close(), an await-ed send rejects with "send() after close()". */
    @Test
    fun js_send_after_close_is_fatal() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("idle") { /* accepted, never used — the test only exercises the JS write end */ }
        try {
            assertEquals("send() after close()", rt.probe("send-after-close"))
        } finally {
            rt.close()
        }
    }

    /** After closeRead() you've declared yourself done reading — a later receive() is a misuse and
     *  throws (not a quiet EOF that would mask the bug). */
    @Test
    fun receive_after_close_read_is_fatal() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("idle") { /* accepted, never used — the test only exercises the JS read end */ }
        try {
            assertEquals("receive() after closeRead()", rt.probe("receive-after-close-read"))
        } finally {
            rt.close()
        }
    }

    /** The mirror: a Kotlin reader closes its read end, so the JS writer's await-ed send rejects with
     *  EPIPE. The JS loop stops and reports the error. */
    @Test
    fun js_send_rejects_with_epipe_after_kotlin_closes_read() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("up") { pipe -> pipe.source.close() } // refuse to read anything
        try {
            assertTrue(Regex("""EPIPE: broken pipe \[#\d+ up]""").matches(rt.probe("epipe-loop")))
        } finally {
            rt.close()
        }
    }

    /** Backpressured large transfer Kotlin -> JS: an awaited [Pipe.send] is paced by the JS reader
     *  (buffer bounded to ~64 KB, WRITABLE wakes the writer). The point is it neither deadlocks nor
     *  loses/reorders bytes — verified by an independent length + additive checksum. */
    @Test
    fun kotlin_to_js_large_transfer_is_backpressured_and_intact() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/down")
            val total = 500_000 // ~8x the 64 KB buffer, so the writer parks and resumes many times
            val chunk = ByteArray(4096) { (it and 0xFF).toByte() }
            var sent = 0
            var sum = 0L
            while (sent < total) {
                val n = minOf(chunk.size, total - sent)
                pipe.sendBytes(chunk, 0, n)
                for (i in 0 until n) sum += (chunk[i].toInt() and 0xFF)
                sent += n
            }
            pipe.sink.close()
            assertEquals("$total:$sum", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** The mirror: a backpressured large transfer JS -> Kotlin. Each awaited JS send is accepted
     *  only partially once the 64 KB Kotlin buffer fills; the writer parks and DRAIN notifies (fired
     *  as Kotlin reads) resume it. Verified by an independent length + additive checksum. */
    @Test
    fun js_to_kotlin_large_transfer_is_backpressured_and_intact() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("up-big") { pipe ->
            val b = Buffer()
            var len = 0L
            var sum = 0L
            while (pipe.source.receive(b, 4096L) != -1L) { // consume as we go — keeps the writer draining
                val chunk = b.readByteArray()
                len += chunk.size
                for (byte in chunk) sum += (byte.toInt() and 0xFF)
            }
            pipe.sendText("$len:$sum")
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("up-big"))
        } finally {
            rt.close()
        }
    }

    /** Opening a command nothing serves fails AT THE OPEN (ECONNREFUSED-style), instead of
     *  handing back a half-dead pipe that EPIPEs later — and leaves no registry entry behind. */
    @Test
    fun js_open_unknown_command_fails_at_open() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("""open("no-such-command") refused by host""", rt.probe("open-unknown"))
        } finally {
            rt.close()
        }
    }

    /** The mirror: Kotlin openPipe of a command no JS handler serves throws at the open. */
    @Test
    fun kotlin_open_unknown_command_fails_at_open() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val e = assertFailsWith<QuickJsException> { rt.openPipe("no-such-js-command") }
            assertEquals("""open("no-such-js-command") refused: no JS handler""", e.message)
        } finally {
            rt.close()
        }
    }

    /** A Kotlin RESET (close with a reason) surfaces in JS as a receive rejection carrying the
     *  reason — after any already-rendezvoused bytes; pending buffered data is discarded. */
    @Test
    fun kotlin_reset_surfaces_in_js_receive() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/reset-down")
            pipe.sendBytes(byteArrayOf(42))
            pipe.sink.close("boom: connection lost")
            assertEquals("1|boom: connection lost", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** A JS RESET (close with a reason) surfaces in Kotlin as [PipeResetException] with the reason —
     *  not as a clean EOF. */
    @Test
    fun js_reset_surfaces_as_pipe_reset_exception() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/reset-up")
            val e = assertFailsWith<PipeResetException> { pipe.source.receive(Buffer(), 8L) }
            assertEquals("kaboom mid-stream", e.reason)
        } finally {
            rt.close()
        }
    }

    /** END-path sink.close() whose final flush EPIPEs (the peer closed its read end) must still
     *  half-close — closeWrite() runs on the flush's rejection, not only its resolution, so the pipe
     *  reaps instead of leaking (audit B1). This pins that the EPIPE still propagates to the caller. */
    @Test
    fun end_close_propagates_epipe_when_the_final_flush_fails() = runTest {
        val rt = QuickJsRuntime.create()
        val result = CompletableDeferred<String>()
        rt.onAccept("staged-end-result") { p -> result.complete(p.readText()) }
        try {
            val pipe = rt.openPipe("test/rt/staged-end")
            pipe.source.close() // close our read -> the handler's flush will EPIPE
            pipe.sendText("go") // wake the handler's parked receive
            assertEquals("epipe", withContext(Dispatchers.Default) { withTimeout(5_000) { result.await() } })
        } finally {
            rt.close()
        }
    }

    /** A JS RESET whose reason won't string-coerce (a Symbol) must still surface as a
     *  [PipeResetException], never a clean EOF — a coercion failure that degraded to END would
     *  silently truncate the stream. The shim substitutes a fallback reason (audit S1). */
    @Test
    fun js_reset_with_uncoercible_reason_still_resets() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/reset-sym")
            val e = assertFailsWith<PipeResetException> { pipe.source.receive(Buffer(), 8L) }
            assertEquals("reason coercion failed", e.reason)
        } finally {
            rt.close()
        }
    }

    /** okio-style fixed-width integers cross the pipe both ways: Kotlin writes an int + long with
     *  okio, JS reads them via the buffered source's readInt/readLong and echoes them back through
     *  writeInt/writeLong, and okio reads them out unchanged. */
    @Test
    fun buffered_fixed_width_ints_round_trip() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/intecho")
            val out = Buffer().apply {
                writeInt(-42)
                writeLong(1234567890123L)
            }
            pipe.sink.send(out, out.size)
            val inb = Buffer()
            while (inb.size < 12L) {
                if (pipe.source.receive(inb, 12L) < 0L) break
            }
            assertEquals(-42, inb.readInt())
            assertEquals(1234567890123L, inb.readLong())
        } finally {
            rt.close()
        }
    }

    /** readLong reassembles a frame that arrives fragmented — one byte per write, so request(8) must
     *  park and resume on each byte rather than taking the all-buffered fast path. */
    @Test
    fun buffered_readLong_reassembles_a_fragmented_frame() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/longin")
            val value = 1234567890123L // within readLong's documented safe-integer (<2^53) range
            val bytes = Buffer().apply { writeLong(value) }.readByteArray()
            for (b in bytes) pipe.sink.send(Buffer().apply { writeByte(b.toInt()) }, 1L) // one byte per write
            val inb = Buffer()
            while (inb.size < 8L) {
                if (pipe.source.receive(inb, 8L) < 0L) break
            }
            assertEquals(value, inb.readLong())
        } finally {
            rt.close()
        }
    }

    /** writeLong throws on a BigInt that doesn't fit a signed 64-bit long, rather than silently
     *  wrapping it (DataView.setBigInt64 truncates mod 2^64). */
    @Test
    fun writeLong_rejects_out_of_range_bigint() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("threw: RangeError", rt.openPipe("test/rt/bigcheck").readText())
        } finally {
            rt.close()
        }
    }

    /** The sink allows ONE outstanding async write: while a write is parked on backpressure, any
     *  other write/flush throws instead of silently interleaving into the staging segment, and the
     *  sink is usable again once the parked write settles. */
    @Test
    fun concurrent_buffered_write_fails_fast() = runTest {
        val rt = QuickJsRuntime.create()
        val gate = CompletableDeferred<Unit>()
        val report = CompletableDeferred<String>()
        rt.onAccept("slowsink") { p ->
            gate.await()
            val b = Buffer()
            while (p.source.receive(b, 8192L) != -1L) { /* drain to EOF */ }
        }
        rt.onAccept("report") { p ->
            val b = Buffer()
            while (p.source.receive(b, 64L) != -1L) { /* to EOF */ }
            report.complete(b.readUtf8())
        }
        try {
            val trigger = rt.openPipe("test/rt/concurrent-write")
            gate.complete(Unit)
            assertEquals(
                "parked|concurrent write on the same pipe|concurrent write on the same pipe",
                report.await(),
            )
            trigger.sink.close()
            trigger.source.close()
        } finally {
            rt.close()
        }
    }

    /** close(reason) is an ABORT: it works mid-write (no concurrent-write throw), fails the parked
     *  send instead of leaving it hanging, and the peer sees the RESET reason. */
    @Test
    fun reset_close_aborts_a_parked_write() = runTest {
        val rt = QuickJsRuntime.create()
        val gate = CompletableDeferred<Unit>()
        val resetSeen = CompletableDeferred<String>()
        val report = CompletableDeferred<String>()
        rt.onAccept("stuck") { p ->
            gate.await()
            val r = runCatching { while (p.source.receive(Buffer(), 8192L) != -1L) { /* to RESET */ } }
            resetSeen.complete((r.exceptionOrNull() as? PipeResetException)?.reason ?: "no-reset")
        }
        rt.onAccept("report") { p ->
            val b = Buffer()
            while (p.source.receive(b, 64L) != -1L) { /* to EOF */ }
            report.complete(b.readUtf8())
        }
        try {
            val trigger = rt.openPipe("test/rt/reset-parked-write")
            gate.complete(Unit)
            assertEquals("none|send aborted: deliberate abort", report.await())
            assertEquals("deliberate abort", resetSeen.await())
            trigger.sink.close()
            trigger.source.close()
        } finally {
            rt.close()
        }
    }

    /** The KOTLIN mirror of [reset_close_aborts_a_parked_write]: a Kotlin sink.close() while our own
     *  send is parked at the cap must fail that send with [SendClosedException], not leave it hung
     *  forever (audit KM1). Self-caused (our close), so NOT EPIPE / [BrokenPipeException]. */
    @Test
    fun close_aborts_a_parked_kotlin_send() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            // The fixture never reads, so Kotlin's send fills the 64 KB cap and parks.
            val pipe = rt.openPipe("test/rt/blackhole")
            val big = Buffer().write(ByteArray(200_000))
            val send = async(Dispatchers.Default) { runCatching { pipe.sink.send(big, big.size) } }
            withContext(Dispatchers.Default) { delay(200) } // let the send park at the cap
            pipe.sink.close() // must fail the parked send, not hang
            val r = withContext(Dispatchers.Default) { withTimeout(5_000) { send.await() } }
            assertTrue(
                r.isFailure && r.exceptionOrNull() is SendClosedException,
                "the parked send must fail with SendClosedException, got $r",
            )
        } finally {
            rt.close()
        }
    }

    /** An END-close that has to interrupt a parked send must reach the peer as a RESET, never a
     *  clean EOF: the peer began receiving 200 KB and only 64 KB was delivered, so a clean EOF here
     *  would be silent truncation. */
    @Test
    fun end_close_of_a_parked_send_resets_the_peer() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/push")
            val e = assertFailsWith<PipeResetException> {
                val buf = Buffer()
                while (pipe.source.receive(buf, 8192L) != -1L) { /* to EOF or RESET */ }
            }
            assertEquals("closed while a send was in progress", e.reason)
        } finally {
            rt.close()
        }
    }

    /** A RESET is terminal for the read side: every receive() after the first re-throws
     *  [PipeResetException] (the KDoc contract), not a read-closed IllegalStateException. */
    @Test
    fun receive_after_reset_keeps_throwing_pipe_reset() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/reset-up")
            assertFailsWith<PipeResetException> { pipe.source.receive(Buffer(), 8L) }
            val again = assertFailsWith<PipeResetException> { pipe.source.receive(Buffer(), 8L) }
            assertEquals("kaboom mid-stream", again.reason)
        } finally {
            rt.close()
        }
    }

    /** A Kotlin handler that throws RESETs the pipe with the failure message, so the JS client's
     *  read throws instead of hanging or seeing a clean EOF. */
    @Test
    fun kotlin_handler_failure_resets_js_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("boom") { throw RuntimeException("handler blew up") }
        try {
            assertEquals("handler blew up", rt.probe("kotlin-boom"))
        } finally {
            rt.close()
        }
    }

    /** Closing the runtime while a caller is parked in receive() must FAIL that call — not leave
     *  the coroutine suspended forever once the engine thread is gone (a permanent leak + hang). */
    @Test
    fun runtime_close_fails_parked_receive() = runTest {
        val rt = QuickJsRuntime.create()
        val pipe = rt.openPipe("test/rt/silent-hs")
        assertEquals(1L, pipe.source.receive(Buffer(), 4L)) // handshake consumed; nothing more will come
        val parked = async(Dispatchers.Default) {
            runCatching { pipe.source.receive(Buffer(), 4L) }
        }
        withContext(Dispatchers.Default) { delay(200) } // real time: let the receive park on the engine thread
        rt.close()
        val result = parked.await()
        assertTrue(result.isFailure, "parked receive must fail when the runtime closes, got $result")
    }

    /** Sink writes are plain functions: on the uncontended path they complete synchronously and
     *  return undefined (no promise, no microtask hop) — awaiting stays optional. */
    @Test
    fun sink_writes_complete_synchronously_when_uncontended() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            pipe.sendText(pipe.readText())
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("sync-writes"))
        } finally {
            rt.close()
        }
    }

    /** The JSON token pump is iterative (explicit stack): a value nested thousands deep serializes
     *  without hitting a recursion limit, and its bytes are exact. */
    @Test
    fun write_object_as_json_has_no_depth_limit() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            pipe.sendText(pipe.readText())
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("json-depth"))
        } finally {
            rt.close()
        }
    }

    /** Rope strings (built by repeated concat) and Latin-1/UTF-16 storage widths all serialize
     *  byte-exactly — the encoder reads JSString storage directly and must linearize ropes. */
    @Test
    fun write_object_as_json_handles_ropes_and_string_widths() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            pipe.sendText(pipe.readText())
            pipe.sink.close()
        }
        try {
            assertEquals("OK", rt.probe("json-ropes"))
        } finally {
            rt.close()
        }
    }

    /** A circular value throws TypeError (like JSON.stringify) instead of emitting forever — the
     *  encoder's explicit stack makes the ancestry check cheap. */
    @Test
    fun write_object_as_json_rejects_cycles() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            pipe.sendText(pipe.readText())
            pipe.sink.close()
        }
        try {
            assertEquals("TYPE_ERROR", rt.probe("json-cycle"))
        } finally {
            rt.close()
        }
    }

    /** An encoder abandoned mid-emit (frames holding containers + a pending value + key enums)
     *  must be freed cleanly by the class finalizer at runtime teardown — the encoder analog of the
     *  parser abandonment tests (native builds assert on a leaked gc object / bad gc_mark). */
    @Test
    fun json_encoder_abandoned_midway_frees_cleanly() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            globalThis.__encs = [];
            const tiny = new Uint8Array(4); // force a partial fill: the walk stays suspended
            for (let i = 0; i < 50; i++) {
              const enc = __jsonEncNew({ a: [1, { b: "hello🎵", c: [2, 3] }], d: "tail" });
              __jsonEncFill(enc, tiny, 0);
              __encs.push(enc); // keep referenced: only the finalizer frees it, at rt.close()
            }
            """.trimIndent(),
        )
        rt.close() // finalizers must free the suspended encoders without error
    }

    /** A line longer than the 64 KB pipe cap must NOT deadlock (reader parked for more data vs
     *  writer parked for a DRAIN that requires consumption): the scanner spills scanned bytes into
     *  a reader-owned buffer, unblocking the writer. Covers a ~275 KB CRLF line (multibyte chars
     *  landing on spill boundaries), a normal line after it, and an over-cap unterminated final
     *  line at EOF. */
    @Test
    fun line_longer_than_pipe_cap_does_not_deadlock() = runTest {
        val rt = QuickJsRuntime.create()
        val big = buildString { repeat(25_000) { append("abcdefé🎵") } } // ~275 KB of UTF-8
        val tail = "T".repeat(100_000) // over-cap, no trailing '\n'
        rt.onAccept("lines") { pipe ->
            pipe.sendText("$big\r\nsecond🎵\n$tail")
            pipe.sink.close()
        }
        try {
            val s1 = big.sumOf { it.code.toLong() }
            val s3 = tail.sumOf { it.code.toLong() }
            assertEquals("${big.length}:$s1|second🎵|${tail.length}:$s3|EOF", rt.probe("long-line-over-cap"))
        } finally {
            rt.close()
        }
    }

    /** A line longer than one 8 KB pool segment: the newline scan resumes across arrivals without
     *  rescanning, and the decode assembles the spanning range once — content must be exact. */
    @Test
    fun long_line_spanning_segments_reads_intact() = runTest {
        val rt = QuickJsRuntime.create()
        val long = buildString { repeat(20_000) { append(('a' + (it % 26))) } }
        rt.onAccept("lines") { pipe ->
            pipe.sendText("$long\nsecond🎵\n") // one send -> several pool segments
            pipe.sink.close()
        }
        try {
            val sum = long.sumOf { it.code }
            assertEquals("${long.length}:$sum|second🎵|EOF", rt.probe("long-line-segments"))
        } finally {
            rt.close()
        }
    }

    /** writeUtf8Range encodes a substring straight from the source string — no .slice() copy. */
    @Test
    fun write_utf8_range_is_slice_free() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe ->
            pipe.sendText(pipe.readText())
            pipe.sink.close()
        }
        try {
            assertEquals("hello🎵", rt.probe("utf8-range"))
        } finally {
            rt.close()
        }
    }

    /** The mirror: a JS handler that throws RESETs the pipe with the error message, surfacing in
     *  Kotlin as [PipeResetException]. */
    @Test
    fun js_handler_failure_resets_kotlin_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            val pipe = rt.openPipe("test/rt/jsboom")
            val e = assertFailsWith<PipeResetException> { pipe.source.receive(Buffer(), 8L) }
            assertEquals("js blew up", e.reason)
        } finally {
            rt.close()
        }
    }

    /** onAccept returns a registration: remove() unregisters (host open then refuses), and a
     *  STALE handle — one replaced by a newer registration — must not remove the newer handler. */
    @Test
    fun js_accept_registration_can_be_removed() = runTest {
        val rt = QuickJsRuntime.create()
        suspend fun step(n: Char) {
            val p = rt.openPipe("test/rt/reg-step")
            p.sendText(n.toString())
            p.sink.close()
            assertEquals("done", p.readText())
        }
        try {
            step('1')
            assertEquals("one", rt.openPipe("svc").readText())
            step('2') // replace + stale remove — must not unregister the new handler
            assertEquals("two", rt.openPipe("svc").readText())
            step('3') // register-and-remove
            assertFailsWith<QuickJsException> { rt.openPipe("svc") }
        } finally {
            rt.close()
        }
    }

    @Test
    fun prefix_accept_routes_suffix_and_the_pipe_carries_args() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAcceptPrefix("host/main/") { pipe ->
            assertEquals("host/main/echo-suffix", pipe.command)
            val arg = pipe.readText()
            pipe.sendText("got:" + pipe.command.removePrefix("host/main/") + ":" + arg)
            pipe.sink.close()
        }
        try {
            assertEquals("got:echo-suffix:arg", rt.probe("prefix-main"))
        } finally {
            rt.close()
        }
    }

    /** The Kotlin mirror: onAccept's registration removes (JS open then refuses at the open), and
     *  a stale handle is a no-op. */
    @Test
    fun kotlin_accept_registration_can_be_removed() = runTest {
        val rt = QuickJsRuntime.create()
        val reg1 = rt.onAccept("svc") { pipe ->
            pipe.sendText("one")
            pipe.sink.close()
        }
        try {
            assertEquals("one", rt.probe("probe-svc"))
            val reg2 = rt.onAccept("svc") { pipe ->
                pipe.sendText("two")
                pipe.sink.close()
            }
            reg1.remove() // stale — replaced by reg2; must not unregister it
            assertEquals("two", rt.probe("probe-svc"))
            reg2.remove()
            assertEquals("ERR:open(\"svc\") refused by host", rt.probe("probe-svc"))
        } finally {
            rt.close()
        }
    }
}
