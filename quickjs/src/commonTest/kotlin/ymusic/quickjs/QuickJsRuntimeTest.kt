package ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuickJsRuntimeTest {

    private suspend fun Pipe.sendText(s: String) = send(s.encodeToByteArray())

    /** Read the whole pipe stream (until the peer closes) into one array. */
    private suspend fun Pipe.readAll(): ByteArray {
        val chunks = ArrayList<ByteArray>()
        val buf = ByteArray(256)
        while (true) {
            val n = receive(buf, 0, buf.size)
            if (n < 0) break
            if (n > 0) chunks.add(buf.copyOfRange(0, n))
        }
        val out = ByteArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) { c.copyInto(out, p); p += c.size }
        return out
    }

    private suspend fun Pipe.readText(): String = readAll().decodeToString()

    // A JS echo handler: stream bytes straight back, then close.
    private val echoJs = """
        ymix.onAccept("echo", async (pipe) => {
          const buf = new Uint8Array(64);
          for (;;) { const n = await pipe.receive(buf, 0, buf.length); if (n < 0) break; pipe.send(buf, 0, n); }
          pipe.close();
        });
    """.trimIndent()

    /** Native `__parseJson` (JS_ParseJSON over raw UTF-8 bytes) — no giant JS string, astral-safe. */
    @Test
    fun native_json_parse_over_bytes() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            assertEquals("function", rt.load("typeof __parseJson"))
            // objects, arrays, nested, emoji; parsed straight from a Uint8Array of UTF-8 bytes.
            assertEquals(
                """{"title":"hi🎵","items":[1,2,3]}""",
                rt.load("""JSON.stringify(__parseJson(ymix.utf8Encode('{"title":"hi🎵","items":[1,2,3]}')))"""),
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
                      const bytes = ymix.utf8Encode(json);
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
                pipe.send(bytes, i, n)
                i += n
            }
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("feed"));
              const data = await io.readJsonObject();
              pipe.send(ymix.utf8Encode(JSON.stringify(data)));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals(body, rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** readJsonObject/readJsonArray validate the root type on the first byte and fail fast otherwise. */
    @Test
    fun read_json_object_and_array_enforce_root_type() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            // "parse": Kotlin sends [kind byte 'o'|'a'][json...]; JS parses with the matching typed
            // reader and returns either JSON.stringify(value) or "ERR:" + the thrown message.
            ymix.onAccept("parse", async (pipe) => {
              const io = ymix.buffered(pipe);
              const kind = await io.readUtf8Line();       // "o" or "a"
              try {
                const data = kind === "o" ? await io.readJsonObject() : await io.readJsonArray();
                pipe.send(ymix.utf8Encode(JSON.stringify(data)));
              } catch (e) { pipe.send(ymix.utf8Encode("ERR:" + e.message)); }
              pipe.close();
            });
            """.trimIndent(),
        )
        suspend fun run(kind: String, json: String): String {
            val p = rt.openPipe("parse")
            p.send("$kind\n$json".encodeToByteArray())
            p.close()
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
            pipe.send("""{"a":1,"b":[2,3]}TA""".encodeToByteArray())
            pipe.send("IL-🎵-DATA".encodeToByteArray())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("feed"));
              const data = await io.readJsonObject();   // stops right after '}'
              const tail = await io.readUtf8();         // everything after the JSON value
              pipe.send(ymix.utf8Encode(JSON.stringify(data) + "|" + tail));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("""{"a":1,"b":[2,3]}|TAIL-🎵-DATA""", rt.openPipe("run").readText())
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
              __jsonPush(p, ymix.utf8Encode('{"a":[1,2,'), 0, 10);
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
                const bytes = ymix.utf8Encode('{"a":[1,2,{"b":[3, xBAD');
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
                rt.load("""ymix.utf8Decode(__stringifyJson({title:"hi🎵",items:[1,2,3]}))"""),
            )
            // bytes round-trip straight back through the native parse
            assertEquals("hi🎵", rt.load("""__parseJson(__stringifyJson({t:"hi🎵"})).t"""))
            // no JSON representation -> throws
            assertFailsWith<QuickJsException> { rt.load("__stringifyJson(undefined)") }
        } finally {
            rt.close()
        }
    }

    /** utf8Encode is variadic: multiple strings encode concatenated (no JS string concat first). */
    @Test
    fun utf8_encode_is_variadic() = runTest {
        val rt = QuickJsRuntime.create()
        try {
            // three args concatenated, astral char intact, round-trips through decode
            assertEquals("hi🎵\n", rt.load("""ymix.utf8Decode(ymix.utf8Encode("hi", "🎵", "\n"))"""))
            // equals the single-arg encode of the concatenation
            assertEquals(
                "true",
                rt.load(
                    """
                    (function () {
                      const a = ymix.utf8Encode("hello", "\n");
                      const b = ymix.utf8Encode("hello\n");
                      return a.length === b.length && a.every((v, i) => v === b[i]);
                    })()
                    """.trimIndent(),
                ),
            )
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
        rt.load(echoJs)
        try {
            val pipe = rt.openPipe("echo")
            pipe.sendText("hi🎵")
            pipe.close()                 // EOF our input → JS echo loop ends and closes
            assertEquals("hi🎵", pipe.readText())
        } finally {
            rt.close()
        }
    }

    @Test
    fun stream_is_binary_safe() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(echoJs)
        try {
            val pipe = rt.openPipe("echo")
            val payload = byteArrayOf(0, 127, -128, -1, -2, 1, 0)
            pipe.send(payload, 0, payload.size)
            pipe.close()
            assertContentEquals(payload, pipe.readAll())
        } finally {
            rt.close()
        }
    }

    /** Send + offset/len: only the requested slice of a reused buffer is transmitted. */
    @Test
    fun send_respects_offset_and_len() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(echoJs)
        try {
            val pipe = rt.openPipe("echo")
            val buf = "XXXhelloYYY".encodeToByteArray()
            pipe.send(buf, 3, 5) // "hello"
            pipe.close()
            assertEquals("hello", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** The command is a routing name only; arguments stream in and are read in the handler. */
    @Test
    fun command_reads_args_from_stream() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("youtube_fetch_feed", async (pipe) => {
              const args = ymix.utf8Decode(await ymix.readAll(pipe));   // "home 20"
              pipe.send(ymix.utf8Encode("feed:" + args));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("youtube_fetch_feed")
            pipe.sendText("home 20")
            pipe.close()
            assertEquals("feed:home 20", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** JS is the client here: while serving Kotlin's "run", JS opens its own "upper" pipe back to Kotlin. */
    @Test
    fun js_opens_pipe_kotlin_serves_nested() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("upper") { p ->
            val text = p.readText()
            p.send(text.uppercase().encodeToByteArray())
            p.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const input = await ymix.readAll(pipe);
              const inner = ymix.open("upper");
              inner.send(input, 0, input.length);
              inner.close();
              const res = await ymix.readAll(inner);
              pipe.send(res, 0, res.length);
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("run")
            pipe.sendText("hello🎵")
            pipe.close()
            assertEquals("HELLO🎵", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** The sink accumulates many writes and sends them on close() — all content arrives intact,
     *  and (with the buffer growing past its initial size) it need not fit the initial 4 KB. */
    @Test
    fun buffered_sink_buffers_until_flush() = runTest {
        val rt = QuickJsRuntime.create()
        // Drain to EOF, then reply with the reassembled content on the same pipe.
        rt.onAccept("sink") { pipe ->
            val buf = ByteArray(8192)
            val all = StringBuilder()
            while (true) {
                val n = pipe.receive(buf, 0, buf.size)
                if (n < 0) break
                all.append(buf.decodeToString(0, n))
            }
            pipe.send(all.toString().encodeToByteArray())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("sink"));
              // 1000 lines "ABCDE\n" = 6000 bytes — well past the 4 KB initial sink buffer, so this
              // exercises the buffer growing while nothing is flushed until close().
              for (let i = 0; i < 1000; i++) io.writeUtf8Line("ABCDE");
              io.close();
              pipe.send(ymix.utf8Encode(await io.readUtf8()));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("ABCDE\n".repeat(1000), rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** Only one receive() may be outstanding per pipe; a concurrent second call rejects. */
    @Test
    fun concurrent_receive_rejects() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("silent") { /* never send: the first receive stays pending, pipe stays open */ }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.open("silent");
              const buf = new Uint8Array(8);
              io.receive(buf, 0, 8);                 // first: pends (no data arrives)
              let err = "no-error";
              try { await io.receive(buf, 0, 8); } catch (e) { err = e.message; }
              pipe.send(ymix.utf8Encode(err));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("concurrent receive() on the same pipe", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** readUtf8Line(strict): a final line without '\n' is returned when non-strict, but strict
     *  (okio readUtf8LineStrict) requires the '\n' and throws at EOF. */
    @Test
    fun read_utf8_line_strict() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("feed") { pipe -> pipe.send("a\nbc".encodeToByteArray()); pipe.close() } // "bc" unterminated
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const strict = (await ymix.readAll(pipe))[0] === 49; // '1' -> strict
              const io = ymix.buffered(ymix.open("feed"));
              const l1 = await io.readUtf8Line(strict);            // "a" — terminated by '\n'
              let l2;
              try { l2 = await io.readUtf8Line(strict); if (l2 === null) l2 = "NULL"; }
              catch (e) { l2 = "THREW"; }
              pipe.send(ymix.utf8Encode(l1 + "|" + l2));
              pipe.close();
            });
            """.trimIndent(),
        )
        suspend fun run(strict: Boolean): String {
            val p = rt.openPipe("run")
            p.send((if (strict) "1" else "0").encodeToByteArray())
            p.close()
            return p.readText()
        }
        try {
            assertEquals("a|bc", run(false))     // non-strict: trailing unterminated line returned
            assertEquals("a|THREW", run(true))   // strict: no newline before EOF -> throws
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
            val buf = ByteArray(64)
            while (true) {
                val n = pipe.receive(buf, 0, buf.size)
                if (n < 0) break
                pipe.send(buf, 0, n)
            }
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("echo"));
              io.writeUtf8Line("hello🎵");
              io.writeUtf8Line("world");
              io.flush();
              io.close();                                // done sending → echo returns the rest and ends
              const l1 = await io.readUtf8Line();
              const l2 = await io.readUtf8Line();
              const l3 = await io.readUtf8Line();        // null at EOF
              pipe.send(ymix.utf8Encode(l1 + "|" + l2 + "|" + (l3 === null ? "EOF" : l3)));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("run")
            assertEquals("hello🎵|world|EOF", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** A line split across several frames (with '\r'|'\n' split at a frame boundary) reads as one line. */
    @Test
    fun buffered_line_split_across_frames() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("lines") { pipe ->
            pipe.send("hel".encodeToByteArray())
            pipe.send("lo🎵\r".encodeToByteArray())
            pipe.send("\nsecond\n".encodeToByteArray())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("lines"));
              const l1 = await io.readUtf8Line();
              const l2 = await io.readUtf8Line();
              const l3 = await io.readUtf8Line();
              pipe.send(ymix.utf8Encode(l1 + "|" + l2 + "|" + (l3 === null ? "EOF" : l3)));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("run")
            assertEquals("hello🎵|second|EOF", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** Stream JSON bytes over a pipe, parse them natively (`__parseJson`) — no giant string, emoji-safe. */
    @Test
    fun json_body_over_pipe_parsed_natively() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("fetch") { pipe ->
            pipe.send("""{"title":"hi🎵","items":[1,2,3]}""".encodeToByteArray())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const body = ymix.open("fetch");
              body.close();
              const data = __parseJson(await ymix.readAll(body));   // native parse of the streamed bytes
              pipe.send(ymix.utf8Encode(data.title + ":" + data.items.length));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("run")
            assertEquals("hi🎵:3", pipe.readText())
        } finally {
            rt.close()
        }
    }

    /** A response streamed as several sends, read as one continuous stream. */
    @Test
    fun streams_multiple_sends_as_one_stream() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("stream", async (pipe) => {
              pipe.send(Uint8Array.of(1, 2, 3));
              pipe.send(Uint8Array.of(4, 5));
              pipe.send(Uint8Array.of(6));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("stream")
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), pipe.readAll())
        } finally {
            rt.close()
        }
    }
}
