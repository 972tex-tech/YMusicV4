package io.ymusic.dynamicengine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The HTTP protocol end to end, driven via the `test/http/<scenario>` fixtures compiled into the
 *  test bundle (engine-js/src/test/fixtures/http.ts) — each scenario is the typed twin of the old
 *  inline snippet and returns the same result string ("ERR:" + message on a throw). */
class DynamicEngineTest {

    /** Drive a fixture scenario: send [args] as the JSON head, return the result text. */
    private suspend fun DynamicEngine.scenario(name: String, args: String = "{}"): String {
        val pipe = openPipe("test/http/$name")
        return try {
            val b = Buffer().writeUtf8(args)
            pipe.sink.send(b, b.size)
            val out = Buffer()
            while (pipe.source.receive(out, 4096L) != -1L) { /* to EOF */ }
            out.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    /** create() installs the frozen `versions` global from C before the bundle — the app version
     *  identifies the whole host surface, for the bundle's backward-compat branching. */
    @Test
    fun versions_are_exposed_to_js() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")), 42)
        try {
            assertEquals("app=42", e.scenario("versions"))
        } finally {
            e.close()
        }
    }

    @Test
    fun get_reads_text_body() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("Hello, world!", headers = listOf("Content-Type" to "text/plain")),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("Hello, world!", e.scenario("get-text"))
        } finally {
            e.close()
        }
    }

    @Test
    fun text_reassembles_utf8_split_across_chunks() = runTest {
        // 2- and 3-byte code points split across 3-byte transport chunks; text() decodes the whole
        // body at once, so boundaries reassemble.
        val full = "café — 世界 αβγ smörgåsbord 🎵"
        val client = FakeHttpClient(CannedResponse.ofText(full, chunkSize = 3))
        val e = createTestEngine(client)
        try {
            assertEquals(full, e.scenario("text", """{"url":"https://x/feed"}"""))
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
        val e = createTestEngine(client)
        try {
            assertEquals("3", e.scenario("json-object-items"))
        } finally {
            e.close()
        }
    }

    /** jsonArray() validates a `[…]` root and parses it (readJsonObject would reject the array). */
    @Test
    fun json_array_parses_a_top_level_array() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("""["a","b","c"]""", chunkSize = 4)))
        try {
            assertEquals("3", e.scenario("json-array-len"))
        } finally {
            e.close()
        }
    }

    /** jsonObject() streams the body then asserts the tail is blank to EOF — a misframed body (data
     *  after the value) is a SyntaxError (commandHandler classifies it "parse"), never silently eaten. */
    @Test
    fun json_rejects_non_whitespace_after_the_body() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("""{"id":"a"}JUNK""")))
        try {
            val r = e.scenario("json-tail-junk")
            assertTrue(r.startsWith("SyntaxError"), r)
            assertTrue(r.contains("0x4a") && r.contains("'J'"), "must name the offending byte: $r")
        } finally {
            e.close()
        }
    }

    /** Trailing whitespace after the JSON value is fine (matches JSON.parse) — the body still parses. */
    @Test
    fun json_tolerates_trailing_whitespace() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("{\"id\":\"a\"}\n  \n")))
        try {
            assertEquals("a", e.scenario("json-trailing-ws"))
        } finally {
            e.close()
        }
    }

    @Test
    fun response_headers_are_case_insensitive() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("x", headers = listOf("Content-Type" to "application/json; charset=utf-8")),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("application/json; charset=utf-8", e.scenario("header-get"))
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
        val e = createTestEngine(client)
        try {
            e.scenario("post-body")
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
    fun content_length_matches_the_encoded_bytes_for_every_utf8_width() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req ->
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = createTestEngine(client)
        try {
            e.scenario("post-body-unicode")
            // "aé€𝄞\uD800!": 1 + 2 + 3 + 4 + 3 (lone surrogate as WTF-8) + 1
            assertEquals(14, body?.size)
            assertEquals("14", client.lastRequest!!.header("Content-Length"))
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
        val e = createTestEngine(client)
        try {
            e.scenario("json-body")
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
        val e = createTestEngine(client)
        try {
            e.scenario("body-callback")
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
        val e = createTestEngine(client)
        try {
            assertEquals("ERR:E20010 unexpected data on the control pipe", e.scenario("raw-junk"))
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
        val e = createTestEngine(client)
        try {
            val result = e.scenario("big-upload")
            assertEquals("ok:$sum", result) // same checksum on both sides
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
        val e = createTestEngine(client)
        try {
            assertEquals(
                "ERR:POST requires a body — use .body(\"\") to send an empty one",
                e.scenario("bodiless-build-error"),
            )
            assertEquals("ok", e.scenario("empty-body-post"))
            assertEquals("0", client.lastRequest!!.header("Content-Length"))
        } finally {
            e.close()
        }
    }

    /** Per-request opts cross in the head and reach the client call; absent, the defaults apply. */
    @Test
    fun request_opts_reach_the_client() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = createTestEngine(client)
        try {
            e.scenario("opts-set")
            assertEquals(5000.milliseconds, client.lastTimeout)
            assertEquals(false, client.lastOptions?.followRedirects)

            e.scenario("opts-default")
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
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("https://cdn.example.com/real", e.scenario("final-url"))
        } finally {
            e.close()
        }
    }

    @Test
    fun closes_the_response_stream_after_text() = runTest {
        val canned = CannedResponse.ofText("body", chunkSize = 1)
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("body", e.scenario("text", """{"url":"https://example.com"}"""))
            assertTrue(canned.closed, "the response body should be closed once it is read")
        } finally {
            e.close()
        }
    }

    @Test
    fun stream_reads_body_chunk_by_chunk() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("abcdefghij", chunkSize = 2))
        val e = createTestEngine(client)
        try {
            assertEquals("10", e.scenario("stream-total"))
        } finally {
            e.close()
        }
    }

    /** The JSON+length wire framing can't be desynced by header content — these checks exist to
     *  fail fast with a clear error instead of deep inside the real HTTP client. */
    @Test
    fun header_injection_is_rejected_at_the_builder() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = createTestEngine(client)
        try {
            assertEquals("ERR:header value must not contain CR/LF/NUL", e.scenario("inject-value"))
            assertEquals("ERR:invalid header name: \"X:B\"", e.scenario("inject-name"))
            assertEquals("ERR:URL must not contain CR/LF", e.scenario("inject-url"))
            assertEquals(null, client.lastRequest, "nothing may reach the client")
        } finally {
            e.close()
        }
    }

    /** headerSanitized coerces untrusted data into a legal header instead of throwing. */
    @Test
    fun sanitized_header_coerces_instead_of_throwing() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = createTestEngine(client)
        try {
            e.scenario("sanitized")
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
        val e = createTestEngine(client)
        try {
            e.scenario("set-add-header")
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
        val e = createTestEngine(client)
        try {
            assertEquals("a\nInjected: 1|ok", e.scenario("header-newline"))
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
        val e = createTestEngine(client)
        try {
            assertEquals("network|request failed: dns lookup failed", e.scenario("err-code"))
        } finally {
            e.close()
        }
    }

    /** A non-IO crash in the client is a bug, not a network failure: it propagates out of
     *  serveHttp and the runtime's backstop RESETs the pipe with the raw message. */
    @Test
    fun handler_crash_surfaces_its_reason_to_js() = runTest {
        val client = FakeHttpClient { throw IllegalStateException("client blew up") }
        val e = createTestEngine(client)
        try {
            assertEquals("ERR:client blew up", e.scenario("text", """{"url":"https://example.com"}"""))
        } finally {
            e.close()
        }
    }

    /** The connection dies AFTER the head went out: the body must end in a RESET, never a clean
     *  EOF — a clean EOF would read as a complete (silently truncated) body. */
    @Test
    fun mid_body_failure_rejects_the_body_instead_of_clean_eof() = runTest {
        val canned = CannedResponse(failAfterChunks = true)
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("ERR:response body failed: origin died", e.scenario("text", """{"url":"https://x/dies"}"""))
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
     *  client's fault (a site code), reported before anything reaches the HTTP client. */
    @Test
    fun malformed_head_resets_with_invalid_request() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = createTestEngine(client)
        try {
            val r = e.scenario("raw-malformed-head")
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
        val e = createTestEngine(client)
        try {
            val r = e.scenario("raw-torn-cancel")
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
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("done", e.scenario("cancel-after-execute"))
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
        val e = createTestEngine(client)
        e.onAccept("entered") { pipe ->
            entered.await()
            val b = Buffer().writeUtf8("go")
            pipe.sink.send(b, b.size)
            pipe.sink.close()
        }
        try {
            assertEquals("ERR:request cancelled", e.scenario("cancel-parked"))
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
        val e = createTestEngine(client)
        e.onAccept("entered") { pipe ->
            entered.await()
            val b = Buffer().writeUtf8("go")
            pipe.sink.send(b, b.size)
            pipe.sink.close()
        }
        try {
            assertEquals("ERR:request cancelled", e.scenario("cancel-parked-body"))
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
        val e = createTestEngine(client)
        try {
            assertEquals("ERR:request cancelled", e.scenario("cancel-before"))
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
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("part1", e.scenario("cancel-stall"))
            withContext(Dispatchers.Default) { // the watcher cancels the exchange asynchronously
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** close() cancels an unused body: the host aborts the exchange and the response is released. */
    @Test
    fun close_cancels_the_unused_body() = runTest {
        val canned = CannedResponse.ofText("y".repeat(200_000), chunkSize = 8192)
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("200", e.scenario("close-response"))
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** timeoutMs also covers an unused body after headers: the host aborts when JS never reads. */
    @Test
    fun timeout_cancels_the_unused_body() = runTest {
        val canned = CannedResponse.ofText("y".repeat(200_000), chunkSize = 8192)
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("200", e.scenario("timeout-unused-body"))
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** execute() not awaited: unused-body timeout still aborts after headers. */
    @Test
    fun fire_and_forget_execute_cancels_the_unused_body() = runTest {
        val canned = CannedResponse.ofText("y".repeat(200_000), chunkSize = 8192)
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("started", e.scenario("fire-and-forget"))
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (!canned.closed) delay(10)
                }
            }
        } finally {
            e.close()
        }
    }

    /** XMLHttpRequest.timeout covers the whole send, including a body that never finishes. */
    @Test
    fun xhr_timeout_aborts_a_hanging_body() = runTest {
        val canned = CannedResponse(
            chunks = listOf("part1".encodeToByteArray()),
            hangAfterChunks = true,
        )
        val e = createTestEngine(FakeHttpClient(canned))
        try {
            assertEquals("timeout", e.scenario("xhr-timeout"))
            withContext(Dispatchers.Default) {
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
        val e = createTestEngine(client)
        try {
            assertEquals("404 false nope", e.scenario("status-text"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_reads_text_body() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("Hello, world!", headers = listOf("Content-Type" to "text/plain")),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("Hello, world!", e.scenario("fetch-get-text"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_parses_json() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText(
                """{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}""",
                headers = listOf("Content-Type" to "application/json"),
                chunkSize = 5,
            ),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("3", e.scenario("fetch-json"))
        } finally {
            e.close()
        }
    }

    @Test
    fun player_fetch_strips_ads_for_json_and_text() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText(
                """{"adPlacements":[{}],"playerAds":[1],"videoDetails":{"videoId":"abc"}}""",
                headers = listOf("Content-Type" to "application/json"),
            ),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("true|true|abc", e.scenario("player-fetch-strips-ads"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_arraybuffer_and_reader() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("Hello, world!", chunkSize = 4)))
        try {
            assertEquals("Hello, world!", e.scenario("fetch-arraybuffer"))
        } finally {
            e.close()
        }
        val streamed = createTestEngine(FakeHttpClient(CannedResponse.ofText("chunk-data", chunkSize = 3)))
        try {
            assertEquals("chunk-data|false|true", streamed.scenario("fetch-reader"))
        } finally {
            streamed.close()
        }
    }

    @Test
    fun fetch_clone_reads_independently() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("body")))
        try {
            assertEquals("body|body", e.scenario("fetch-clone"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_post_forwards_method_headers_body() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req ->
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = createTestEngine(client)
        try {
            e.scenario("fetch-post")
            val req = client.lastRequest!!
            assertEquals("https://example.com/post", req.url.toString())
            assertEquals("POST", req.method)
            assertEquals("payload", body?.decodeToString())
            assertEquals("1", req.header("X-Test"))
            assertEquals("text/html", req.header("Accept"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_headers_are_case_insensitive() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("x", headers = listOf("Content-Type" to "application/json; charset=utf-8")),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("application/json; charset=utf-8", e.scenario("fetch-headers"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_is_native_and_stream_globals_exist() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            assertEquals("function|true|function|function|true", e.scenario("fetch-native"))
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_abort_rejects_with_aborterror() = runTest {
        val client = FakeHttpClient(CannedResponse.ofText("ok"))
        val e = createTestEngine(client)
        try {
            assertEquals("AbortError", e.scenario("fetch-abort"))
            assertTrue(client.lastRequest == null, "nothing may reach the client")
        } finally {
            e.close()
        }
    }

    @Test
    fun fetch_network_error_is_failed_to_fetch() = runTest {
        val client = FakeHttpClient { throw IOException("dns lookup failed") }
        val e = createTestEngine(client)
        try {
            val r = e.scenario("fetch-failed")
            assertTrue(r.startsWith("TypeError Failed to fetch:"), r)
            assertTrue(r.contains("dns lookup failed"), r)
        } finally {
            e.close()
        }
    }

    @Test
    fun xhr_reads_text_body() = runTest {
        val client = FakeHttpClient(
            CannedResponse.ofText("Hello, world!", headers = listOf("Content-Type" to "text/plain")),
        )
        val e = createTestEngine(client)
        try {
            assertEquals("Hello, world!", e.scenario("xhr-get-text"))
        } finally {
            e.close()
        }
    }

    @Test
    fun xhr_post_forwards_method_headers_body() = runTest {
        val canned = CannedResponse.ofText("ok")
        var body: ByteArray? = null
        val client = FakeHttpClient { req ->
            body = req.body?.readAll()
            canned.build(req)
        }
        val e = createTestEngine(client)
        try {
            e.scenario("xhr-post")
            val req = client.lastRequest!!
            assertEquals("https://example.com/post", req.url.toString())
            assertEquals("POST", req.method)
            assertEquals("payload", body?.decodeToString())
            assertEquals("1", req.header("X-Test"))
            assertEquals("text/html", req.header("Accept"))
        } finally {
            e.close()
        }
    }

    @Test
    fun xhr_is_native() = runTest {
        val e = createTestEngine(FakeHttpClient(CannedResponse.ofText("")))
        try {
            assertEquals("function|true|0|0", e.scenario("xhr-native"))
        } finally {
            e.close()
        }
    }
}
