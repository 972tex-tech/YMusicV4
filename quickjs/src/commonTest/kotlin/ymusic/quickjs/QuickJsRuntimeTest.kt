package ymusic.quickjs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuickJsRuntimeTest {

    private suspend fun Pipe.sendText(s: String) {
        val b = Buffer().writeUtf8(s)
        send(b, b.size)
    }

    private suspend fun Pipe.sendBytes(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) =
        send(Buffer().write(bytes, offset, count), count.toLong())

    /** Read the whole pipe stream (until the peer closes) into one array. */
    private suspend fun Pipe.readAll(): ByteArray {
        val all = Buffer()
        while (receive(all, 256L) != -1L) { /* accumulate until EOF */ }
        return all.readByteArray()
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
                pipe.sendBytes(bytes, i, n)
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
            p.sendText("$kind\n$json")
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
            pipe.sendText("""{"a":1,"b":[2,3]}TA""")
            pipe.sendText("IL-🎵-DATA")
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
            pipe.sendBytes(payload, 0, payload.size)
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
            pipe.sendBytes(buf, 3, 5) // "hello"
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
              const args = await ymix.buffered(pipe).readUtf8();   // "home 20"
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
            p.sendText(text.uppercase())
            p.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(pipe);
              const input = await io.readUtf8();
              const inner = ymix.buffered(ymix.open("upper"));
              let p = inner.writeUtf8(input); if (p) await p;
              p = inner.close(); if (p) await p;
              p = io.writeUtf8(await inner.readUtf8()); if (p) await p;
              p = io.close(); if (p) await p;
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

    /** The sink coalesces many small writes and auto-flushes when its fixed segment fills; across
     *  those flush boundaries the peer still sees one intact, in-order byte stream. */
    @Test
    fun buffered_sink_buffers_until_flush() = runTest {
        val rt = QuickJsRuntime.create()
        // Drain to EOF, then reply with the reassembled content on the same pipe.
        rt.onAccept("sink") { pipe ->
            val all = Buffer()
            while (pipe.receive(all, 8192L) != -1L) { /* accumulate until EOF */ }
            pipe.sendText(all.readUtf8())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("sink"));
              // 2000 lines "ABCDE\n" = 12000 bytes > the 8 KB segment, so the sink auto-flushes at
              // least once mid-write; the reassembled stream must still be identical.
              for (let i = 0; i < 2000; i++) await io.writeUtf8Line("ABCDE");
              await io.close();
              pipe.send(ymix.utf8Encode(await io.readUtf8()));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("ABCDE\n".repeat(2000), rt.openPipe("run").readText())
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
            while (pipe.receive(b, 8192L) != -1L) { // consume as we go — keeps the writer draining
                val chunk = b.readByteArray()
                len += chunk.size
                for (byte in chunk) sum += (byte.toInt() and 0xFF)
            }
            pipe.sendText("$len:$sum")
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("sink"));
              // A multibyte+astral string repeated so codepoints land across many segment boundaries,
              // then a >segment byte array that bypasses the buffer and is streamed in slices.
              const s = "héllo🎵\n".repeat(30000);   // stream-encoded into the segment
              const b = new Uint8Array(400000);        // >= segment -> bypass, sliced to the pipe
              for (let i = 0; i < b.length; i++) b[i] = i & 0xFF;
              // Expected length + additive checksum, computed independently from the same source.
              const sbytes = ymix.utf8Encode(s);
              let len = sbytes.length + b.length, sum = 0;
              for (let i = 0; i < sbytes.length; i++) sum += sbytes[i];
              for (let i = 0; i < b.length; i++) sum += b[i];
              await io.writeUtf8(s);
              await io.writeBytes(b);
              await io.close();
              const got = await io.readUtf8(), want = len + ":" + sum;
              pipe.send(ymix.utf8Encode(got === want ? "OK" : ("MISMATCH want=" + want + " got=" + got)));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** writeObjectAsJson streams a value to the pipe token-by-token (no whole string / Uint8Array).
     *  Its output must match JSON.stringify exactly — escapes, NaN/Infinity->null, undefined omitted
     *  in objects / null in arrays, nesting, astral chars — and stay correct across flush boundaries
     *  (the big array pushes the output past the 8 KB segment). */
    @Test
    fun write_object_as_json_matches_stringify() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("sink") { pipe -> // reassemble the streamed bytes and echo the text back
            val all = Buffer()
            while (pipe.receive(all, 8192L) != -1L) { /* accumulate until EOF */ }
            pipe.sendText(all.readUtf8())
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              var big = []; for (var i = 0; i < 5000; i++) big.push(i); // pushes output past a segment
              var value = {
                s: "q\"uote back\\slash nl\n cr\r tab\t bell slash/ emoji🎵 unicodé",
                n: 42, f: 3.5, neg: -7, exp: 1e21, nan: NaN, inf: Infinity, neginf: -Infinity,
                t: true, f2: false, nil: null,
                arr: [1, "two", null, [3, 4], { x: 5 }, undefined, function(){}, Symbol("s")], // -> null
                obj: { a: 1, skip: undefined, fn: function(){}, sym: Symbol("k"), c: 3 }, // omitted
                empty: {}, emptyArr: [], date: new Date(0),           // toJSON honored
                big: big
              };
              const io = ymix.buffered(ymix.open("sink"));
              await io.writeObjectAsJson(value);
              await io.close();
              const got = await io.readUtf8(), want = JSON.stringify(value);
              let msg;
              if (got === want) msg = "OK";
              else {
                let k = 0; while (k < got.length && k < want.length && got[k] === want[k]) k++;
                msg = "MISMATCH@" + k + " want[..." + want.slice(Math.max(0,k-15), k+15) +
                      "...] got[..." + got.slice(Math.max(0,k-15), k+15) + "...]";
              }
              pipe.send(ymix.utf8Encode(msg));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** setTimeout fires its callback after the delay, and doubles as an awaitable sleep. */
    @Test
    fun set_timeout_fires_after_delay() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
              let fired = false;
              setTimeout(() => { fired = true; }, 30);
              await sleep(90);                 // outlast the 30ms timer
              pipe.send(ymix.utf8Encode(fired ? "FIRED" : "NOT"));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("FIRED", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** clearTimeout before the delay elapses prevents the callback. */
    @Test
    fun clear_timeout_prevents_callback() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
              let fired = false;
              const t = setTimeout(() => { fired = true; }, 30);
              clearTimeout(t);
              await sleep(90);
              pipe.send(ymix.utf8Encode(fired ? "FIRED" : "CLEARED"));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("CLEARED", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** setInterval ticks repeatedly and clearInterval stops it (count frozen after clear). */
    @Test
    fun set_interval_ticks_until_cleared() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
              let n = 0;
              const t = setInterval(() => { n++; }, 20);
              await sleep(110);                 // ~5 ticks
              clearInterval(t);
              const after = n;
              await sleep(70);                  // stays frozen (allow at most one in-flight tick)
              const ok = after >= 3 && (n - after) <= 1;
              pipe.send(ymix.utf8Encode(ok ? "OK" : ("BAD:" + after + "/" + n)));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
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
        rt.onAccept("feed") { pipe -> pipe.sendText("a\nbc"); pipe.close() } // "bc" unterminated
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const flag = new Uint8Array(1);
              const strict = (await pipe.receive(flag, 0, 1)) === 1 && flag[0] === 49; // '1' -> strict
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
            p.sendText((if (strict) "1" else "0"))
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
            val b = Buffer()
            while (pipe.receive(b, 64L) != -1L) pipe.send(b, b.size)
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const io = ymix.buffered(ymix.open("echo"));
              await io.writeUtf8Line("hello🎵");
              await io.writeUtf8Line("world");
              await io.close();                          // done sending → echo returns the rest and ends
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
            pipe.sendText("hel")
            pipe.sendText("lo🎵\r")
            pipe.sendText("\nsecond\n")
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
            pipe.sendText("""{"title":"hi🎵","items":[1,2,3]}""")
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (pipe) => {
              const body = ymix.open("fetch");
              body.close();
              // the one-shot __parseJson wants the raw bytes whole — accumulate them here
              let buf = new Uint8Array(4096), size = 0;
              for (;;) {
                if (size === buf.length) { const g = new Uint8Array(buf.length * 2); g.set(buf); buf = g; }
                const n = await body.receive(buf, size, buf.length - size);
                if (n < 0) break;
                size += n;
              }
              const data = __parseJson(buf.subarray(0, size));   // native parse of the streamed bytes
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

    /** Reader-side cancel (Linux EPIPE): the JS handler closes its read end, so the Kotlin writer's
     *  next send throws [BrokenPipeException] — it can tell a producer "stop, I'm not reading". */
    @Test
    fun send_throws_broken_pipe_after_peer_closes_read() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load("""ymix.onAccept("sink", async (pipe) => { pipe.closeRead(); });""")
        try {
            val pipe = rt.openPipe("sink") // openPipe pumps the handler, so closeRead has already run
            assertFailsWith<BrokenPipeException> { pipe.sendBytes(byteArrayOf(1, 2, 3)) }
        } finally {
            rt.close()
        }
    }

    /** After close() you've declared yourself done writing — a later send() is a misuse and throws
     *  IllegalStateException (distinct from EPIPE, which is the peer closing its read end). */
    @Test
    fun send_after_close_is_fatal() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(echoJs)
        try {
            val pipe = rt.openPipe("echo")
            pipe.close()
            assertFailsWith<IllegalStateException> { pipe.sendBytes(byteArrayOf(1, 2, 3)) }
        } finally {
            rt.close()
        }
    }

    /** The JS mirror: after io.close(), an await-ed send rejects with "send() after close()". */
    @Test
    fun js_send_after_close_is_fatal() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("idle") { /* accepted, never used — the test only exercises the JS write end */ }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.open("idle");
              io.close();
              let err = "none";
              try { await io.send(ymix.utf8Encode("x")); } catch (e) { err = (e && e.message) || String(e); }
              p.send(ymix.utf8Encode(err));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("send() after close()", rt.openPipe("run").readText())
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
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.open("idle");
              io.closeRead();
              let err = "none";
              try { await io.receive(new Uint8Array(4), 0, 4); } catch (e) { err = (e && e.message) || String(e); }
              p.send(ymix.utf8Encode(err));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("receive() after closeRead()", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** The mirror: a Kotlin reader closes its read end, so the JS writer's await-ed send rejects with
     *  EPIPE. The JS loop stops and reports the error. */
    @Test
    fun js_send_rejects_with_epipe_after_kotlin_closes_read() = runTest {
        val rt = QuickJsRuntime.create()
        rt.onAccept("up") { pipe -> pipe.closeRead() } // refuse to read anything
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.open("up");
              let err = "none";
              try { for (let i = 0; i < 1000000; i++) await io.send(ymix.utf8Encode("x")); }
              catch (e) { err = (e && e.message) || String(e); }
              p.send(ymix.utf8Encode(err));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            val out = rt.openPipe("run").readText()
            assertEquals("EPIPE: broken pipe", out)
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
        rt.load(
            """
            ymix.onAccept("down", async (pipe) => {
              const buf = new Uint8Array(4096);
              let len = 0, sum = 0;
              for (;;) { const n = await pipe.receive(buf, 0, buf.length); if (n < 0) break;
                         len += n; for (let i = 0; i < n; i++) sum += buf[i]; }
              pipe.send(ymix.utf8Encode(len + ":" + sum));
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("down")
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
            pipe.close()
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
            while (pipe.receive(b, 4096L) != -1L) { // consume as we go — keeps the writer draining
                val chunk = b.readByteArray()
                len += chunk.size
                for (byte in chunk) sum += (byte.toInt() and 0xFF)
            }
            pipe.sendText("$len:$sum")
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.open("up-big");
              const chunk = new Uint8Array(4096);
              for (let i = 0; i < chunk.length; i++) chunk[i] = i & 0xFF;
              let sum = 0;
              for (let i = 0; i < chunk.length; i++) sum += chunk[i];
              const rounds = 125; // 500 KB, ~8x the 64 KB buffer -> many partial writes + parks
              for (let r = 0; r < rounds; r++) await io.send(chunk, 0, chunk.length);
              io.close();
              const got = await ymix.buffered(io).readUtf8();
              const want = (rounds * chunk.length) + ":" + (rounds * sum);
              p.send(ymix.utf8Encode(got === want ? "OK" : ("MISMATCH want=" + want + " got=" + got)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** Opening a command nothing serves fails AT THE OPEN (ECONNREFUSED-style), instead of
     *  handing back a half-dead pipe that EPIPEs later — and leaves no registry entry behind. */
    @Test
    fun js_open_unknown_command_fails_at_open() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              let msg = "no-throw";
              try { ymix.open("no-such-command"); } catch (e) { msg = (e && e.message) || String(e); }
              p.send(ymix.utf8Encode(msg));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("""open("no-such-command") refused by host""", rt.openPipe("run").readText())
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
        rt.load(
            """
            ymix.onAccept("reset-down", async (pipe) => {
              const buf = new Uint8Array(16);
              const n = await pipe.receive(buf, 0, buf.length);   // the byte sent before the reset
              let err = "none";
              try { await pipe.receive(buf, 0, buf.length); } catch (e) { err = (e && e.message) || String(e); }
              pipe.send(ymix.utf8Encode(n + "|" + err));          // our write end is still open
              pipe.close();
            });
            """.trimIndent(),
        )
        try {
            val pipe = rt.openPipe("reset-down")
            pipe.sendBytes(byteArrayOf(42))
            pipe.close("boom: connection lost")
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
        rt.load("""ymix.onAccept("reset-up", async (pipe) => { pipe.close("kaboom mid-stream"); });""")
        try {
            val pipe = rt.openPipe("reset-up")
            val e = assertFailsWith<PipeResetException> { pipe.receive(Buffer(), 8L) }
            assertEquals("kaboom mid-stream", e.reason)
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
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.open("boom");
              let err = "none";
              try { await io.receive(new Uint8Array(8), 0, 8); } catch (e) { err = (e && e.message) || String(e); }
              p.send(ymix.utf8Encode(err));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("handler blew up", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** Closing the runtime while a caller is parked in receive() must FAIL that call — not leave
     *  the coroutine suspended forever once the engine thread is gone (a permanent leak + hang). */
    @Test
    fun runtime_close_fails_parked_receive() = runTest {
        val rt = QuickJsRuntime.create()
        // The handler sends one byte (a handshake proving it ran), then keeps the pipe open forever.
        rt.load("""ymix.onAccept("silent", async (pipe) => { pipe.send(Uint8Array.of(1)); });""")
        val pipe = rt.openPipe("silent")
        assertEquals(1L, pipe.receive(Buffer(), 4L)) // handshake consumed; nothing more will come
        val parked = async(Dispatchers.Default) {
            runCatching { pipe.receive(Buffer(), 4L) }
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.buffered(ymix.open("sink"));
              const value = { a: [1, "two🎵"], b: null };
              const rs = [
                io.writeUtf8("hello"),
                io.writeUtf8Line("x", "y"),
                io.writeObjectAsJson(value),
                io.flush(),
              ];
              const allSync = rs.every((r) => r === undefined);
              const c = io.close(); if (c) await c;
              const got = await io.readUtf8();
              const want = "hello" + "xy\n" + JSON.stringify(value);
              p.send(ymix.utf8Encode(!allSync ? "NOT-SYNC" : got === want ? "OK" : ("MISMATCH got=" + got)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const depth = 5000; // deep enough to blow a recursive walker's stack
              let v = 1;
              for (let i = 0; i < depth; i++) v = [v];
              const io = ymix.buffered(ymix.open("sink"));
              const w = io.writeObjectAsJson(v); if (w) await w;
              const c = io.close(); if (c) await c;
              const got = await io.readUtf8();
              const want = "[".repeat(depth) + "1" + "]".repeat(depth);
              p.send(ymix.utf8Encode(got === want ? "OK" : ("MISMATCH len=" + got.length + " want=" + want.length)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              let rope = "";                                    // repeated concat -> a rope string
              for (let i = 0; i < 400; i++) rope += "seg" + i + "-héllo🎵|";  // wide + Latin-1 mix
              let latin = "";                                   // Latin-1-only rope (narrow storage)
              for (let i = 0; i < 300; i++) latin += "café-æß" + i; // all < U+0100 -> narrow storage
              const value = { rope: rope, latin: latin, esc: rope + "\n\t\"" };
              const io = ymix.buffered(ymix.open("sink"));
              const w = io.writeObjectAsJson(value); if (w) await w;
              const c = io.close(); if (c) await c;
              const got = await io.readUtf8();
              const want = JSON.stringify(value);
              p.send(ymix.utf8Encode(got === want ? "OK" : ("MISMATCH len=" + got.length + " want=" + want.length)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("OK", rt.openPipe("run").readText())
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const a = { name: "a" };
              a.self = { deeper: [1, a] };  // cycle through two levels
              const io = ymix.buffered(ymix.open("sink"));
              let msg = "no-throw";
              try { const w = io.writeObjectAsJson(a); if (w) await w; }
              catch (e) { msg = (e instanceof TypeError) ? "TYPE_ERROR" : ("WRONG:" + e); }
              io.close("abort");
              p.send(ymix.utf8Encode(msg));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("TYPE_ERROR", rt.openPipe("run").readText())
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
        val tail = "T".repeat(100_000)                                    // over-cap, no trailing '\n'
        rt.onAccept("lines") { pipe ->
            pipe.sendText("$big\r\nsecond🎵\n$tail")
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.buffered(ymix.open("lines"));
              const l1 = await io.readUtf8Line();
              const l2 = await io.readUtf8Line();
              const l3 = await io.readUtf8Line();
              const l4 = await io.readUtf8Line();
              let s1 = 0; for (let i = 0; i < l1.length; i++) s1 += l1.charCodeAt(i);
              let s3 = 0; for (let i = 0; i < l3.length; i++) s3 += l3.charCodeAt(i);
              p.send(ymix.utf8Encode(l1.length + ":" + s1 + "|" + l2 + "|" + l3.length + ":" + s3 + "|" + (l4 === null ? "EOF" : l4)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            val s1 = big.sumOf { it.code.toLong() }
            val s3 = tail.sumOf { it.code.toLong() }
            assertEquals("${big.length}:$s1|second🎵|${tail.length}:$s3|EOF", rt.openPipe("run").readText())
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.buffered(ymix.open("lines"));
              const l1 = await io.readUtf8Line();
              const l2 = await io.readUtf8Line();
              const l3 = await io.readUtf8Line();
              // report a checksum of l1 rather than echoing 20 KB back
              let sum = 0; for (let i = 0; i < l1.length; i++) sum += l1.charCodeAt(i);
              p.send(ymix.utf8Encode(l1.length + ":" + sum + "|" + l2 + "|" + (l3 === null ? "EOF" : l3)));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            val sum = long.sumOf { it.code }
            assertEquals("${long.length}:$sum|second🎵|EOF", rt.openPipe("run").readText())
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
            pipe.close()
        }
        rt.load(
            """
            ymix.onAccept("run", async (p) => {
              const io = ymix.buffered(ymix.open("sink"));
              await io.writeUtf8Range("XXhello🎵YY", 2, 9);   // "hello🎵" (surrogate pair = 2 units)
              await io.close();
              p.send(ymix.utf8Encode(await io.readUtf8()));
              p.close();
            });
            """.trimIndent(),
        )
        try {
            assertEquals("hello🎵", rt.openPipe("run").readText())
        } finally {
            rt.close()
        }
    }

    /** The mirror: a JS handler that throws RESETs the pipe with the error message, surfacing in
     *  Kotlin as [PipeResetException]. */
    @Test
    fun js_handler_failure_resets_kotlin_pipe() = runTest {
        val rt = QuickJsRuntime.create()
        rt.load("""ymix.onAccept("jsboom", async (pipe) => { throw new Error("js blew up"); });""")
        try {
            val pipe = rt.openPipe("jsboom")
            val e = assertFailsWith<PipeResetException> { pipe.receive(Buffer(), 8L) }
            assertEquals("js blew up", e.reason)
        } finally {
            rt.close()
        }
    }
}
