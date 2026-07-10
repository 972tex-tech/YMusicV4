package ymusic.dynamicengine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.IOException
import ymusic.quickjs.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DynamicEngineTest {

    /** Drain a pipe to a string. */
    private suspend fun Pipe.readText(): String {
        val all = Buffer()
        while (source.receive(all, 4096L) != -1L) { /* accumulate until EOF */ }
        return all.readUtf8()
    }

    /**
     * Run [js] (an async body that `return`s a value) inside the engine and get its result as text.
     * The body is wrapped in a `ymix.onAccept("run", …)` handler, driven by opening a "run" pipe —
     * mirrors how a real scraper is invoked. Errors come back as `"ERR:<message>"`.
     */
    private suspend fun DynamicEngine.eval(js: String): String {
        val handler =
            "ymix.onAccept('run', async (p) => {" +
                "  try {" +
                "    const r = await (async () => {" + js + "})();" +
                "    p.sink.send(ymix.utf8Encode(r === undefined ? 'undefined' : String(r)));" +
                "  } catch (e) { p.sink.send(ymix.utf8Encode('ERR:' + ((e && e.message) || e))); }" +
                "  p.sink.close();" +
                "});"
        load(handler)
        return openPipe("run").readText()
    }

    /** create() evals the frozen `versions` global before the bundle — all three host-owned ints
     *  (bootstrap, engine, app) in one snippet, for the bundle's backward-compat branching. */
    @Test
    fun versions_are_exposed_to_js() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("")), 42)
        try {
            assertEquals(
                "1|1|42",
                e.eval("return versions.bootstrap + '|' + versions.engine + '|' + versions.app;"),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun get_reads_text_body() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("Hello, world!", headers = listOf("Content-Type" to "text/plain")),
        )
        val e = DynamicEngine.create(client, 1)
        try {
            val text = e.eval(
                """
                const res = await HttpRequest.get("https://example.com").build().execute();
                return (res.ok && res.status === 200) ? await res.text() : "FAIL";
                """.trimIndent(),
            )
            assertEquals("Hello, world!", text)
        } finally {
            e.close()
        }
    }

    @Test
    fun text_reassembles_utf8_split_across_chunks() = runTest {
        // 2- and 3-byte code points split across 3-byte transport chunks; text() decodes the whole
        // body at once, so boundaries reassemble. (Astral chars now survive too — no JNI string
        // bridge; bytes cross the pipe raw.)
        val full = "café — 世界 αβγ smörgåsbord 🎵"
        val client = FakeHttpClient(CannedResponse.ofText(full, chunkSize = 3))
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                full,
                e.eval("""return await (await HttpRequest.get("https://x/feed").build().execute()).text();"""),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun json_parses_response_body() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText(
                """{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}""",
                headers = listOf("Content-Type" to "application/json"),
                chunkSize = 5,
            ),
        )
        val e = DynamicEngine.create(client, 1)
        try {
            val count = e.eval(
                """
                const body = await (await HttpRequest.get("https://example.com/api").build().execute()).jsonObject();
                return body.items.length;
                """.trimIndent(),
            )
            assertEquals("3", count)
        } finally {
            e.close()
        }
    }

    /** jsonArray() validates a `[…]` root and parses it (readJsonObject would reject the array). */
    @Test
    fun json_array_parses_a_top_level_array() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("""["a","b","c"]""", chunkSize = 4)), 1)
        try {
            val len = e.eval(
                """
                const a = await (await HttpRequest.get("https://x/").build().execute()).jsonArray();
                return a.length;
                """.trimIndent(),
            )
            assertEquals("3", len)
        } finally {
            e.close()
        }
    }

    /** jsonObject() streams the body then asserts the tail is blank to EOF — a misframed body (data
     *  after the value) is a SyntaxError (commandHandler classifies it "parse"), never silently eaten. */
    @Test
    fun json_rejects_non_whitespace_after_the_body() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("""{"id":"a"}JUNK""")), 1)
        try {
            val r = e.eval(
                """
                try {
                  await (await HttpRequest.get("https://x/").build().execute()).jsonObject();
                  return "no-throw";
                } catch (err) { return err.name + " " + err.message; }
                """.trimIndent(),
            )
            assertTrue(r.startsWith("SyntaxError"), r) // -> commandHandler classifies SyntaxError as "parse"
            assertTrue(r.contains("0x4a") && r.contains("'J'"), "must name the offending byte: $r")
        } finally {
            e.close()
        }
    }

    /** Trailing whitespace after the JSON value is fine (matches JSON.parse) — the body still parses. */
    @Test
    fun json_tolerates_trailing_whitespace() = runTest {
        val e = DynamicEngine.create(FakeHttpClient(CannedResponse.ofText("{\"id\":\"a\"}\n  \n")), 1)
        try {
            val id = e.eval(
                """
                const b = await (await HttpRequest.get("https://x/").build().execute()).jsonObject();
                return b.id;
                """.trimIndent(),
            )
            assertEquals("a", id)
        } finally {
            e.close()
        }
    }

    @Test
    fun response_headers_are_case_insensitive() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("x", headers = listOf("Content-Type" to "application/json; charset=utf-8")),
        )
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "application/json; charset=utf-8",
                e.eval(
                    """return (await HttpRequest.get("https://example.com").build().execute()).headers.get("content-type");""",
                ),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun post_forwards_method_headers_body_and_content_length() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req -> // the streamed body only exists while the exchange lives
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                await HttpRequest.post("https://example.com/post")
                    .setHeader("X-Test", "1")
                    .setHeader("Accept", "text/html")
                    .body("payload")
                    .build().execute();
                """.trimIndent(),
            )
            val req = client.lastRequest!!
            assertEquals("https://example.com/post", req.url.toString())
            assertEquals("POST", req.method)
            assertEquals("payload", body?.decodeToString())
            assertEquals("1", req.header("X-Test"))
            assertEquals("text/html", req.header("Accept"))
            assertEquals("7", req.header("Content-Length")) // "payload" is 7 UTF-8 bytes, counted without encoding
        } finally {
            e.close()
        }
    }

    @Test
    fun json_body_sets_content_type_and_serializes() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req ->
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                await HttpRequest.post("https://example.com/api")
                    .json({ q: "term", n: 3 })
                    .build().execute();
                """.trimIndent(),
            )
            assertEquals("""{"q":"term","n":3}""", body?.decodeToString())
            assertEquals("application/json", client.lastRequest!!.header("Content-Type"))
        } finally {
            e.close()
        }
    }

    @Test
    fun body_callback_writes_to_the_sink() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req ->
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = DynamicEngine.create(client, 1)
        try {
            // The callback owns the body: streams two writes, no Content-Length (chunk-framed).
            e.eval(
                """
                await HttpRequest.post("https://example.com/upload")
                    .body(async (io) => { await io.writeUtf8("chunk-"); await io.writeUtf8("data"); })
                    .build().execute();
                """.trimIndent(),
            )
            assertEquals("chunk-data", body?.decodeToString())
            assertEquals(null, client.lastRequest!!.header("Content-Length"), "a callback body has unknown length")
        } finally {
            e.close()
        }
    }

    /** Junk on the control pipe after the head is a protocol violation — the exchange is torn
     *  down (even one parked in the transport) and the pipe RESET with the reason. */
    @Test
    fun junk_on_the_control_pipe_resets_the_exchange() = runTest {
        val aborted = CompletableDeferred<Unit>()
        val client = FakeHttpClient {
            try {
                awaitCancellation()
            } finally {
                aborted.complete(Unit)
            }
        }
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "ERR:E20010 unexpected data on the control pipe (5)",
                e.eval(
                    """
                    const io = ymix.open("http"); /* drive the wire raw: HttpRequest can't emit this */
                    let p;
                    p = io.sink.writeObjectAsJson({ method: "GET", url: "https://x", headers: [] }); if (p) await p;
                    p = io.sink.writeInt(5); if (p) await p;
                    p = io.sink.flush(); if (p) await p;
                    return await io.source.readJsonObject(); /* the RESET reason lands on this read */
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the violation aborts the parked exchange
                withTimeout(5_000) { aborted.await() }
            }
        } finally {
            e.close()
        }
    }

    /** A request body far past the pipe cap (64 KB) streams through: the transport pulls it off
     *  the pipe while the client is still writing — the server never holds the whole body. */
    @Test
    fun request_body_streams_past_the_pipe_cap() = runTest {
        val canned = CannedResponse.ofText("ok")
        var size = -1L
        var sum = 0L
        val client = FakeHttpClient { req ->
            val bytes = req.body!!.readAll()
            size = bytes.size.toLong()
            for (b in bytes) sum += b.toInt() and 0xFF
            canned.build(req)
        }
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                const big = new Uint8Array(200000);
                let sum = 0;
                for (let i = 0; i < big.length; i++) { big[i] = i & 0xff; sum += big[i]; }
                const res = await HttpRequest.post("https://x/upload").body(big).build().execute();
                return (await res.text()) + ":" + sum;
                """.trimIndent(),
            ).let { result ->
                assertEquals("ok:$sum", result) // same checksum on both sides
            }
            assertEquals(200_000L, size)
        } finally {
            e.close()
        }
    }

    /** A bodiless POST fails at build() — no silent empty-body substitution; an intentionally
     *  empty body is explicit. */
    @Test
    fun bodiless_post_fails_at_build() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "ERR:POST requires a body — use .body(\"\") to send an empty one",
                e.eval("""return HttpRequest.post("https://x").build();"""),
            )
            assertEquals(
                "ok",
                e.eval("""return await (await HttpRequest.post("https://x").body("").build().execute()).text();"""),
            )
            assertEquals("0", client.lastRequest!!.header("Content-Length"))
        } finally {
            e.close()
        }
    }

    /** Per-request opts cross in the head and reach the client call; absent, the defaults apply. */
    @Test
    fun request_opts_reach_the_client() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                await HttpRequest.get("https://x").timeout(5000).followRedirects(false).build().execute();
                """.trimIndent(),
            )
            assertEquals(5000.milliseconds, client.lastTimeout)
            assertEquals(false, client.lastOptions?.followRedirects)

            e.eval("""await HttpRequest.get("https://x").build().execute();""")
            assertEquals(client.defaultExecuteTimeout, client.lastTimeout)
            assertEquals(client.defaultRedirectOptions, client.lastOptions)
        } finally {
            e.close()
        }
    }

    /** The response head carries the final URL (Response.request.url — after redirects). */
    @Test
    fun response_url_is_the_final_url_after_redirects() = runTest {
        val canned = CannedResponse(
            chunks = listOf("ok".encodeToByteArray()),
            finalUrl = "https://cdn.example.com/real",
        )
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            assertEquals(
                "https://cdn.example.com/real",
                e.eval("""return (await HttpRequest.get("https://example.com/short").build().execute()).url;"""),
            )
        } finally {
            e.close()
        }
    }

    @Test
    fun closes_the_response_stream_after_text() = runTest {
        val canned = CannedResponse.ofText("body", chunkSize = 1)
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            e.eval("""return await (await HttpRequest.get("https://example.com").build().execute()).text();""")
            assertTrue(canned.closed, "the response body should be closed once it is read")
        } finally {
            e.close()
        }
    }

    @Test
    fun stream_reads_body_chunk_by_chunk() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("abcdefghij", chunkSize = 2))
        val e = DynamicEngine.create(client, 1)
        try {
            // Pull the body via stream().read() without ever building the whole body in JS.
            val total = e.eval(
                """
                const res = await HttpRequest.get("https://example.com/stream").build().execute();
                const s = res.stream();
                const buf = new Uint8Array(4);
                let total = 0;
                for (;;) { const n = await s.read(buf, 0, buf.length); if (n < 0) break; total += n; }
                return total;
                """.trimIndent(),
            )
            assertEquals("10", total)
        } finally {
            e.close()
        }
    }

    /** The JSON+length wire framing can't be desynced by header content — these checks exist to
     *  fail fast with a clear error instead of deep inside the real HTTP client. */
    @Test
    fun header_injection_is_rejected_at_the_builder() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "ERR:header value must not contain CR/LF/NUL",
                e.eval("""return HttpRequest.get("https://x").setHeader("X-A", "a\nInjected: 1");"""),
            )
            assertEquals(
                "ERR:invalid header name: \"X:B\"",
                e.eval("""return HttpRequest.get("https://x").setHeader("X:B", "v");"""),
            )
            assertEquals(
                "ERR:URL must not contain CR/LF",
                e.eval("""return HttpRequest.get("https://x/\n999 sneaky");"""),
            )
            assertEquals(null, client.lastRequest, "nothing may reach the client")
        } finally {
            e.close()
        }
    }

    /** headerSanitized coerces untrusted data into a legal header instead of throwing. */
    @Test
    fun sanitized_header_coerces_instead_of_throwing() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                await HttpRequest.get("https://x")
                    .setHeaderSanitized("X-A\n", " a\nInjected: 1 ")
                    .addHeaderSanitized("X:B", "v\u0000v")
                    .build().execute();
                """.trimIndent(),
            )
            val req = client.lastRequest!!
            assertEquals("a Injected: 1", req.header("X-A")) // CR/LF run folded to one space, edges trimmed
            assertEquals("vv", req.header("XB")) // ':' stripped from the name, NUL from the value
        } finally {
            e.close()
        }
    }

    /** setHeader replaces the same name (case-insensitive); addHeader appends alongside it. */
    @Test
    fun set_header_replaces_add_header_appends() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            e.eval(
                """
                await HttpRequest.get("https://x")
                    .setHeader("X-M", "old")
                    .addHeader("X-M", "kept")
                    .setHeader("x-m", "new")
                    .addHeader("X-M", "extra")
                    .build().execute();
                """.trimIndent(),
            )
            assertEquals(listOf("new", "extra"), client.lastRequest!!.headers("X-M"))
        } finally {
            e.close()
        }
    }

    /** v2 heads are JSON: a CR/LF in a response header value is just data — it crosses intact
     *  instead of desyncing (or having to be rejected by) the protocol. */
    @Test
    fun response_header_with_newline_crosses_intact() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("ok", headers = listOf("X-Bad" to "a\nInjected: 1")),
        )
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "a\nInjected: 1|ok",
                e.eval(
                    """
                    const res = await HttpRequest.get("https://x").build().execute();
                    return res.headers.get("X-Bad") + "|" + (await res.text());
                    """.trimIndent(),
                ),
            )
        } finally {
            e.close()
        }
    }

    /** A transport IOException (DNS/connect/TLS/timeout) is the ROUTINE failure: serveHttp reports it
     *  as an origin-classified `{code:"network", message}` RESET, and execute() rejects carrying both
     *  — the code is decided at the origin, not guessed from the message text downstream. */
    @Test
    fun transport_failure_rejects_execute_coded_network() = runTest {
        val client = FakeHttpClient { throw IOException("dns lookup failed") }
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "network|request failed: dns lookup failed",
                e.eval(
                    """
                    try { await HttpRequest.get("https://example.com").build().execute(); return "resolved"; }
                    catch (err) { return err.code + "|" + err.message; }
                    """.trimIndent(),
                ),
            )
        } finally {
            e.close()
        }
    }

    /** A non-IO crash in the client is a bug, not a network failure: it propagates out of
     *  serveHttp and the runtime's backstop RESETs the pipe with the raw message. */
    @Test
    fun handler_crash_surfaces_its_reason_to_js() = runTest {
        val client = FakeHttpClient { throw IllegalStateException("client blew up") }
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "ERR:client blew up",
                e.eval("""return await (await HttpRequest.get("https://example.com").build().execute()).text();"""),
            )
        } finally {
            e.close()
        }
    }

    /** The connection dies AFTER the head went out: the body must end in a RESET, never a clean
     *  EOF — a clean EOF would read as a complete (silently truncated) body. Depending on timing
     *  the RESET lands on the head read (discarding it) or the body read; the reason is the same. */
    @Test
    fun mid_body_failure_rejects_the_body_instead_of_clean_eof() = runTest {
        val canned = CannedResponse(failAfterChunks = true)
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            assertEquals(
                "ERR:response body failed: origin died",
                e.eval(
                    """
                    try {
                        const res = await HttpRequest.get("https://x/dies").build().execute();
                        await res.text();
                        return "clean-eof";
                    } catch (err) { return "ERR:" + err.message; }
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the response is still released on the failure path
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** A head the server can't parse RESETs with a structured `{code:"E20011", message}` reason — the
     *  client's fault (a site code), reported before anything reaches the HTTP client. Read raw here,
     *  so the reason is the JSON string itself (a real call gets it parsed onto err.code by http.js). */
    @Test
    fun malformed_head_resets_with_invalid_request() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            val r = e.eval(
                """
                const io = ymix.open("http"); /* drive the wire raw: HttpRequest can't emit this */
                let p;
                p = io.sink.writeUtf8("[]"); if (p) await p;
                p = io.sink.flush(); if (p) await p;
                return await io.source.readJsonObject(); /* the RESET reason lands on this read */
                """.trimIndent(),
            )
            assertTrue(r.startsWith("ERR:") && r.contains("E20011") && r.contains("invalid request"), r)
            assertEquals(null, client.lastRequest, "nothing may reach the client")
        } finally {
            e.close()
        }
    }

    /** A torn cancel int (EOF mid-int32) is a protocol violation like junk: the exchange is torn
     *  down via the backstop, not treated as a clean end. */
    @Test
    fun torn_cancel_int_resets_the_exchange() = runTest {
        val aborted = CompletableDeferred<Unit>()
        val client = FakeHttpClient {
            try {
                awaitCancellation()
            } finally {
                aborted.complete(Unit)
            }
        }
        val e = DynamicEngine.create(client, 1)
        try {
            val r = e.eval(
                """
                const io = ymix.open("http");
                let p;
                p = io.sink.writeObjectAsJson({ method: "GET", url: "https://x", headers: [] }); if (p) await p;
                p = io.sink.writeBytes(new Uint8Array([0, 0])); if (p) await p; /* half an int32 */
                p = io.sink.flush(); if (p) await p;
                p = io.sink.close(); if (p) await p; /* END mid-int */
                return await io.source.readJsonObject();
                """.trimIndent(),
            )
            assertTrue(r.startsWith("ERR:"), r)
            withContext(Dispatchers.Default) { // the violation aborts the parked exchange
                withTimeout(5_000) { aborted.await() }
            }
        } finally {
            e.close()
        }
    }

    /** cancel() abandons the body: the server's parked body write EPIPEs, its handler aborts and
     *  the response body is released — no leak while the runtime lives on. */
    @Test
    fun cancel_releases_a_streaming_response() = runTest {
        // > pipe cap (64 KB), so the server parks mid-body and would sit there forever otherwise.
        val canned = CannedResponse.ofText("x".repeat(200_000), chunkSize = 8192)
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            assertEquals(
                "done",
                e.eval(
                    """
                    const res = await HttpRequest.get("https://x/big").build().execute();
                    res.cancel();
                    return "done";
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the handler aborts asynchronously on the engine thread
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** HttpCall.cancel() mid-flight — while the server is parked inside client.execute and no
     *  Response exists yet — aborts the call and rejects execute(). The "entered" pipe gates the
     *  cancel so it provably lands while execute is in flight. */
    @Test
    fun call_cancel_aborts_a_parked_execute() = runTest {
        val entered = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        val client = FakeHttpClient {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                aborted.complete(Unit)
            }
        }
        val e = DynamicEngine.create(client, 1)
        e.onAccept("entered") { pipe ->
            entered.await()
            val b = Buffer().writeUtf8("go")
            pipe.sink.send(b, b.size)
            pipe.sink.close()
        }
        try {
            assertEquals(
                "ERR:request cancelled",
                e.eval(
                    """
                    const call = HttpRequest.get("https://x/slow").build();
                    const done = call.execute().then((r) => "resolved", (err) => "ERR:" + err.message);
                    await ymix.open("entered").source.readUtf8(); // the server is now parked in execute
                    call.cancel();
                    return await done;
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the watcher aborts the exchange asynchronously
                withTimeout(5_000) { aborted.await() }
            }
        } finally {
            e.close()
        }
    }

    /** The hole v3 exists to close: cancel a POST whose body the parked transport never pulled.
     *  In the single-pipe design the cancel sat queued behind the body; the control pipe carries
     *  nothing after the head, so the watcher sees it immediately. */
    @Test
    fun call_cancel_aborts_a_parked_execute_with_unpulled_body() = runTest {
        val entered = CompletableDeferred<Unit>()
        val aborted = CompletableDeferred<Unit>()
        val client = FakeHttpClient {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                aborted.complete(Unit)
            }
        }
        val e = DynamicEngine.create(client, 1)
        e.onAccept("entered") { pipe ->
            entered.await()
            val b = Buffer().writeUtf8("go")
            pipe.sink.send(b, b.size)
            pipe.sink.close()
        }
        try {
            assertEquals(
                "ERR:request cancelled",
                e.eval(
                    """
                    const call = HttpRequest.post("https://x/upload").body("never pulled").build();
                    const done = call.execute().then((r) => "resolved", (err) => "ERR:" + err.message);
                    await ymix.open("entered").source.readUtf8(); // the server is parked in execute; body unpulled
                    call.cancel();
                    return await done;
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the watcher aborts the exchange asynchronously
                withTimeout(5_000) { aborted.await() }
            }
        } finally {
            e.close()
        }
    }

    /** cancel() before execute(): the call refuses to start — nothing reaches the client. */
    @Test
    fun call_cancel_before_execute_refuses_to_start() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "ERR:request cancelled",
                e.eval(
                    """
                    const call = HttpRequest.get("https://x").build();
                    call.cancel();
                    try { await call.execute(); return "resolved"; } catch (err) { return "ERR:" + err.message; }
                    """.trimIndent(),
                ),
            )
            assertTrue(client.lastRequest == null, "nothing may reach the client")
        } finally {
            e.close()
        }
    }

    /** The in-band cancel (-1 on the still-open write side) reaches a server parked INSIDE the
     *  client — a stalled origin that never sends the next chunk — and aborts it. An EPIPE-only
     *  cancel could never interrupt this case: the server wasn't writing. */
    @Test
    fun cancel_aborts_a_transport_parked_mid_body() = runTest {
        val canned = CannedResponse(
            chunks = listOf("part1".encodeToByteArray()),
            hangAfterChunks = true,
        )
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            assertEquals(
                "part1",
                e.eval(
                    """
                    const res = await HttpRequest.get("https://x/stall").build().execute();
                    const s = res.stream();
                    const buf = new Uint8Array(16);
                    const n = await s.read(buf, 0, buf.length); // the one chunk that does arrive
                    res.cancel();
                    return ymix.utf8Decode(buf, 0, n);
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the watcher cancels the exchange asynchronously
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** ignoreResult() drains the body to nowhere in the background: the server completes
     *  normally (clean END, reusable connection) and the response body is released. */
    @Test
    fun ignore_result_drains_the_body_fire_and_forget() = runTest {
        val canned = CannedResponse.ofText("y".repeat(200_000), chunkSize = 8192)
        val e = DynamicEngine.create(FakeHttpClient(canned), 1)
        try {
            assertEquals(
                "200",
                e.eval(
                    """
                    const res = await HttpRequest.get("https://x/ping").build().execute();
                    res.ignoreResult();
                    return res.status;
                    """.trimIndent(),
                ),
            )
            withContext(Dispatchers.Default) { // the drain finishes in the background
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun reports_non_2xx_status_in_head() = runTest {
        val client = FakeHttpClient(
            CannedResponse(status = 404, message = "Not Found", chunks = listOf("nope".encodeToByteArray())),
        )
        val e = DynamicEngine.create(client, 1)
        try {
            assertEquals(
                "404 false nope",
                e.eval(
                    """
                    const res = await HttpRequest.get("https://example.com/missing").build().execute();
                    return res.status + " " + res.ok + " " + await res.text();
                    """.trimIndent(),
                ),
            )
        } finally {
            e.close()
        }
    }
}
